# CLAUDE.md — IA-service (MyAnimaLog)

Contexto y convenciones para trabajar en este repo. La arquitectura completa, los endpoints y
el modelo de datos están en `README.md` — léelo antes de proponer cambios estructurales.

## Qué es esto

Microservicio orquestador de IA del ecosistema MyAnimaLog (proyecto de grado, expo en
noviembre). Expone un asistente multimodal que responde preguntas, sugiere razas desde fotos,
ejecuta acciones sobre los demás microservicios vía *function calling* de Gemini, extrae datos
de documentos veterinarios y genera sugerencias de cuidado reactivas a eventos.

## Regla número uno

**Este servicio no tiene lógica de negocio.** No valida vacunas, no calcula fechas, no decide
permisos. Traduce intenciones en llamadas HTTP a los servicios que ya son dueños de esos datos.
Si te encuentras escribiendo una validación de dominio aquí, va en el microservicio equivocado.

## Coordenadas del proyecto

- Java 17, Spring Boot **3.5.16** (no 4.x — el resto del ecosistema está en 3.5)
- `groupId`: `com.myanimal.org`, `artifactId`: `IA-service`, puerto **8083**
- Arquitectura hexagonal: `domain` (model, ports.in, ports.out) → `application` (dto, service)
  → `infrastructure` (adapters.in, adapters.out, tools, config)
- PostgreSQL en Supabase con Transaction Pooler, `ddl-auto: none`
- Las tablas se crean a mano por SQL en Supabase. El DDL está en el README.
- Modelos confirmados en `.env` (principal / fallback): `gemini-3.8-flash` / `gemini-3.6-flash`.
  Vienen de `GEMINI_MODEL` y `GEMINI_MODEL_FALLBACK`, nunca hardcoded en el `GeminiAdapter`.
- `GEMINI_TIMEOUT=20` (antes 60) y `GEMINI_MAX_RETRIES=1` (antes 3): con reintentos +
  fallback encima, un timeout por intento de 60s hacía que el peor caso superara los 5
  minutos — el móvil y Cloud Run se rinden mucho antes. Ver también la entrada de
  timeouts en "Trampas conocidas".

## Servicios vecinos

| Servicio | Puerto | Rol frente a este |
|---|---|---|
| `api-gateway` | 8090 | Punto de salida para todos los tool calls |
| `pet-service` | 8081 | `crear_mascota`, `registrar_vacuna`, `listar_mascotas` |
| `calendar-service` | 3001 | `agendar_cita` en `/api/calendar-events` |
| `medical-service` | 3000 | Emite los eventos que disparan las sugerencias |
| `notification-service` | — | Consume `ai-suggestion-generated` |

Las llamadas salientes van **al api-gateway**, no a cada servicio directo. Una sola base URL,
la autenticación ya montada.

## Decisiones ya tomadas (no re-litigar sin motivo)

- **HTTP síncrono, no eventos**, para chat, raza, agendar y registrar. Pub/Sub no tiene canal
  de respuesta; solo la funcionalidad 6 (sugerencias de cuidado) es reactiva por naturaleza.
- **Function calling** para las acciones, no endpoints propios por funcionalidad. Cada acción
  es un `@Component` que implementa `AiTool`; el `ToolRegistry` inyecta `List<AiTool>`.
- **Structured output** (`responseMimeType: application/json` + `responseSchema`) para extraer
  documentos veterinarios, no tool calling. El servicio extrae y devuelve; **no escribe**.
  El usuario confirma en un formulario y el móvil hace el POST a `medical-service`.
- **Human-in-the-loop obligatorio** para datos médicos y para toda acción de escritura. Los
  tools con `requiresConfirmation() == true` devuelven un `pendingAction` en vez de ejecutar.
- **Suscripciones push, no pull.** En Cloud Run el contenedor escala a cero y `StreamingPull`
  se muere.
- **Idempotencia por `messageId`** en `ai_processed_event` antes de llamar al modelo.
- **WebClient directo contra la REST API de Gemini**, no Spring AI. Menos magia, más control.

## Seguridad

El JWT del usuario se propaga en cada tool call. **El ai-service nunca decide permisos**: si
el modelo alucina un `petId` ajeno, `pet-service` responde 403 y ese 403 vuelve al modelo como
`functionResponse`. La IA es un cliente más, no un *security boundary*.

`JWT_SECRET` debe ser idéntico al de user, pet, veterinary y gateway.
`/internal/**` lo invoca Pub/Sub con un ID token de Google: fuera del filtro de JWT.

**Detalles confirmados contra `user-service` (etapa 2):**
- La clave HS256 se construye con `Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))`
  — **no** es base64. `user-service` usa jjwt 0.11.x (`parserBuilder`/`setSigningKey`); nuestro
  `pom` tiene 0.12.6, API distinta (`Jwts.parser().verifyWith(key).build().parseSignedClaims(...)`)
  pero misma clave/algoritmo, tokens compatibles entre versiones.
- El UUID del usuario viaja en el claim **`id`**, no en `sub` (`sub` es el email). Configurable
  vía `security.jwt.user-id-claim` (default `id`). Si el valor de ese claim no parsea como UUID,
  401 inmediato y se loguea el nombre del claim y el valor recibido (nunca el token completo).
- Tokens duran 1 hora.

## Convenciones de código

- Un controller y un service por funcionalidad.
- DTOs planos con `@Data @Builder @AllArgsConstructor @NoArgsConstructor`.
- `@Builder(toBuilder = true)` en modelos de dominio.
- Tests: `@MockitoBean`, `@WithMockUser`, `.with(csrf())`, `@ExtendWith(MockitoExtension.class)`.
  Excepción: en `@WebMvcTest` de controllers bajo `/api/ai/**`, `@WithMockUser` no sirve
  (ver "Trampas conocidas" — el `JwtAuthenticationFilter` real siempre corre ahí); hay que
  importar `SecurityConfig`+`JwtProperties` y mandar un JWT real. CSRF ya está desactivado
  para `/api/**`, así que `.with(csrf())` no hace falta en esos tests.
- En tests preferir `.builder()...build()` sobre constructores posicionales.
- Al agregar un parámetro a un constructor con `@RequiredArgsConstructor`, revisar los tests
  que instancian la clase a mano — rompen con `cannot be applied to given types`.

## Reglas de contenido para el modelo

- La IA **no diagnostica ni prescribe**. Sugiere y recomienda acudir al veterinario.
- La raza siempre en lenguaje probable ("parece un mestizo con rasgos de labrador"), nunca
  como afirmación categórica.
- Zona horaria del usuario (`America/Bogota`) en la instrucción de sistema, o las citas se
  agendan en UTC.

## Trampas conocidas del despliegue

- Imagen runtime **Ubuntu, no Alpine**: gRPC/Netty de Pub/Sub es incompatible con musl libc
  (`Uncaught signal: 6 (SIGABRT)` al arrancar).
- `${PORT}`, nunca `${SERVER_PORT}`. Cloud Run inyecta `PORT`.
- Dockerfile multi-stage con `RUN mv target/*.jar app.jar` — el `artifactId` es `IA-service`
  y el wildcard evita `COPY failed: no source files were specified`.
- En local, `gcloud auth application-default login` (no `gcloud auth login`).
- Secretos en Secret Manager, nunca como env vars planas.
- Hibernate autodetectaba el dialecto consultando metadatos JDBC contra el Transaction
  Pooler, y esa consulta fallaba intermitentemente (`Unable to determine Dialect without
  JDBC metadata`). Fix: dialecto explícito (`hibernate.dialect: PostgreSQLDialect`) +
  `hibernate.boot.allow_jdbc_metadata_access: false` en `application.yaml`.
- Los tests (`src/test/resources/application-test.yaml`, perfil `test`) excluyen
  autoconfig de datasource/JPA, Redis y GCP Pub/Sub. `IaServiceApplicationTests` no toca
  Supabase, Upstash ni GCP reales — corre 100% offline.
- Un timeout de Gemini (Gemini no responde nada, a diferencia de un 503) llega como
  `WebClientRequestException` con causa `ReadTimeoutException`, no como
  `WebClientResponseException` — hay que capturarlo aparte. `GeminiAdapter` lo trata
  como retryable (mismo camino que 503/429) y distingue en el log "Timeout de lectura"
  de "Fallo de conexión" (DNS, conexión rechazada, etc., que sí se loguea distinto pero
  no se reintenta — se lanza `AiModelException` de inmediato, igual que un 4xx).
- **Idea futura, no implementada**: usar un timeout más generoso en el primer intento
  (ej. 20s) y uno más corto en los reintentos (ej. 8-10s), ya que si Gemini está lento
  una vez es probable que siga lento. Se descartó por ahora para no complicar el timeout
  único de `gemini.timeout-seconds`; revisar si `GEMINI_TIMEOUT=20` + `GEMINI_MAX_RETRIES=1`
  no es suficiente en producción.
- **`@WebMvcTest` incluye automáticamente cualquier bean `Filter`** (aunque no se
  `@Import`ee), porque el allowlist de tipos de esa slice trata los filtros como parte
  de la capa web. `JwtAuthenticationFilter` (un `@Component` + `OncePerRequestFilter`)
  se cuela en **cualquier** `@WebMvcTest` del proyecto y exige que `JwtProperties` esté
  disponible — si no, `UnsatisfiedDependencyException` al arrancar el slice. Por eso los
  tests de controllers bajo `/api/ai/**` importan `SecurityConfig` + `JwtProperties` y
  mandan un JWT real firmado con el `security.jwt.secret` del perfil `test`, en vez de
  `@WithMockUser` (que no sirve de nada: el filtro real corre igual y rechaza la
  petición si no trae un `Authorization` válido).
- `ConversationRepositoryPort` tiene dos implementaciones: `JpaConversationRepositoryAdapter`
  (`@Profile("!test")`, la real) y `NoopConversationRepositoryPort` en `src/test`
  (`@Profile("test")`, lanza `UnsupportedOperationException` si algo intenta usarla de
  verdad). Existe solo para que `IaServiceApplicationTests` arranque el contexto completo
  sin datasource/JPA — ningún test hace aserciones sobre ella. Los tests de `ChatService`
  usan mocks de Mockito, no este fake.
- **Testcontainers, mejora futura**: los repositorios JPA (`JpaConversationRepositoryAdapter`)
  no tienen test de integración contra Postgres real en esta etapa — solo `ChatServiceTest`
  con `ConversationRepositoryPort` mockeado. Cuando se necesite probar el mapeo de
  entidades/jsonb de verdad, usar Testcontainers con Postgres en vez de pegarle a Supabase.
- **Adjuntos (etapa 2, parte C)**: `GeminiTextPart` se renombró a `GeminiPart` y ahora
  también carga `GeminiInlineData` (`inline_data`/`mime_type`/`data` en base64) — ambos con
  `@JsonInclude(NON_NULL)` puesto directamente en la clase, porque `GeminiWebClientConfig`
  arma su `WebClient` con `WebClient.builder()` estático (no con el `WebClient.Builder` bean
  autoconfigurado de Boot), así que no hay garantía de heredar `spring.jackson.default-
  property-inclusion: non_null` — sin el `@JsonInclude` explícito se mandaría `"text":null`
  o `"inline_data":null` en cada parte según cuál campo esté vacío.
- El MIME de un adjunto se detecta por los primeros bytes (`MagicByteMimeDetector`), sin
  librerías nuevas. Dos límites conocidos de esa heurística: HEIC vs. HEIF se distingue por
  el "brand" de 4 letras del box `ftyp` (`heic`/`heix`/... vs. `mif1`/`msf1`), que no es
  100% confiable en archivos exóticos; y el detector **nunca** emite el MIME
  `audio/mp3` (solo `audio/mpeg`, que es el estándar) aunque `audio/mp3` siga en la lista
  blanca por si algún día se acepta ese valor de otra fuente.
- `FileStoragePort.upload/download` suben y bajan bytes crudos contra la REST API de
  Supabase Storage; la key nunca se loguea (solo el código de estado). Los adjuntos se
  suben a Storage **antes** de abrir la transacción corta que guarda el mensaje del
  usuario (mismo principio que con Gemini: nada de I/O lento con una transacción abierta).
- `ChatService` reenvía el binario real de un adjunto solo si su turno está dentro de las
  últimas `gemini.max-history-attachments` posiciones de la ventana ya recortada por
  `gemini.max-history-messages` (no "las últimas N que tengan adjunto": son las últimas N
  posiciones, tengan o no adjunto). Los adjuntos fuera de esa ventana se re-descargan de
  Supabase Storage en **cada** turno nuevo — incluido el adjunto que se acaba de subir en
  la misma petición, que técnicamente ya tenemos en memoria. No se optimizó ese caso por
  simplicidad (un único code path); si el costo del round-trip extra importa, se puede
  reutilizar el `byte[]` original del turno actual en vez de descargarlo de nuevo.
- El endpoint multipart `POST /api/ai/chat` (sin `/text`) se adelantó de la Parte D a la
  Parte C porque, sin él, los adjuntos no eran probables de punta a punta. `POST
  /api/ai/chat/text` (JSON, solo texto) sigue vivo — su eliminación es explícitamente
  trabajo de la Parte D, no de esta.
- **Parte D**: `POST /api/ai/chat/text` y su `ChatRequestDto` se eliminaron (el multipart
  de la parte C ya cubre todo). El `AiChatControllerTest` que lo probaba se borró entero
  — `AiChatMultipartControllerTest` ya cubre lo mismo por el endpoint que queda.
- `ConversationRepositoryPort` ganó un 6º método, `findMessages(conversationId, limit,
  offset)`, que **no** estaba en el contrato original de la parte B. `findRecentMessages`
  solo sirve para "los últimos N para mandarle a Gemini" (sin offset); `GET
  /conversations/{id}/messages` necesita paginación cronológica ascendente de verdad con
  offset arbitrario, que es una consulta distinta.
- `ConversationQueryService` (nuevo, aplica a `GET /api/ai/conversations` y
  `GET /api/ai/conversations/{id}/messages`) recorta `limit` a `[1, 50]` (default 20 si
  viene `<1`) y `offset` a `>=0` — el brief solo pide "máximo 50", así que se interpretó
  como un recorte silencioso, no como un 400.
- Los adjuntos del listado de mensajes se firman **al momento de responder**
  (`FileStoragePort.signedUrl` con `supabase.signed-url-ttl-seconds`), nunca se guarda una
  URL. `ConversationNotFoundException` (ya existía desde la parte B) se reutiliza para
  "conversación ajena o inexistente" en ambos endpoints de lectura → siempre 404, nunca 403
  (un 403 confirmaría que el id existe).
- **Etapa 3, parte A-D1 (checkpoint, `listar_mascotas` de punta a punta)**:
  - `AiTool.describe(args)` es un método `default` que no estaba en la firma que dio el
    brief (solo 4 métodos) — se agregó porque E2 pide un `descripcion` legible para la
    tarjeta de confirmación y ningún método existente lo genera. El default humaniza el
    nombre técnico (`crear_mascota` → "Crear mascota"); los tools con confirmación lo
    sobrescriben. Nunca debería llegar el nombre técnico crudo a una tarjeta.
  - `UserContext` ganó `username` (claim `username` del JWT, no configurable — a
    diferencia del claim del id, el brief no pidió que este lo fuera). Rompe cualquier
    `new UserContext(userId, jwt)` con 2 argumentos; ahora son 3
    (`userId, username, rawJwt`).
  - El system prompt pasó de string fijo a plantilla: se sigue cargando una vez al
    arrancar (`systemPromptText`), pero `ChatLoopRunner.renderSystemPrompt` sustituye
    `{fecha}`/`{dia_semana}`/`{username}` en cada llamada, con un `Clock` inyectado
    (bean `Clock.systemUTC()`) para que sea testeable sin pegarle al reloj real.
  - `thoughtSignature` **sí hizo falta**: Gemini lo devuelve junto al `functionCall` y
    hay que reenviarlo tal cual en el turno del modelo al continuar la conversación
    (`GeminiPart.thoughtSignature`, `AiPart.ofFunctionCall(..., thoughtSignature)`).
  - Un `ai_message` con `rol='tool'` se reconstruye como **dos** turnos de Gemini al
    armar el historial: uno `model` con el `functionCall` (id + thoughtSignature) y uno
    `user` con el `functionResponse` — en vez de intentar preservar la agrupación
    original si el modelo pidió varias funciones en la misma vuelta. `tool_args` guarda
    un sobre `{callId, thoughtSignature, arguments}`; `tool_result` guarda
    `{ok, data}` o `{ok: false, error}` — mismo shape que `ToolResult`.
  - `Message.toolArgs`/`toolResult` (y `MessageEntity`) pasaron de `String` (placeholder
    nulo de la etapa 2) a `Map<String, Object>` con `@JdbcTypeCode(SqlTypes.JSON)`,
    porque ahora sí se escriben de verdad.
  - El loop completo vive en `ChatLoopRunner` (`application.service`), no en
    `ChatService`: lo comparten un mensaje de chat nuevo y (más adelante) la
    confirmación de una pending action, que solo hace su paso inicial propio y termina
    delegando aquí. `ChatService` quedó reducido a resolver/crear la conversación, subir
    adjuntos y guardar el turno del usuario.
  - **Un 401 de un servicio destino (pet-service ahora; medical/calendar-service
    cuando existan) nunca se traduce a `functionResponse`.** Es la sesión expirando a
    mitad del loop (los tokens duran 1h), no "el recurso no existe" — el modelo lo
    malinterpretaría. `PetServiceAdapter` lo distingue con
    `UpstreamSessionExpiredException` (NO la atrapa `ToolExecutor`, que solo atrapa
    `ToolExecutionException`); `ChatLoopRunner` sí la atrapa alrededor de
    `toolExecutor.execute(...)`, corta el loop y responde "tu sesión expiró" sin volver
    a llamar a Gemini. Cualquier adapter de servicio destino nuevo debe seguir el mismo
    patrón: 401 → `UpstreamSessionExpiredException`; otro 4xx/5xx con cuerpo →
    `ToolExecutionException`; sin respuesta (timeout/conexión) → se deja propagar tal
    cual, sin capturar nada.
  - `PendingAction` (dominio + puerto + entidad JPA + adapter + fake de test) está
    completo aunque los endpoints `POST /api/ai/actions/{id}/confirm|reject` **no**
    — el loop ya crea la pending action de verdad (probado con un tool de prueba con
    `requiresConfirmation=true`, ya que ningún tool real la pide todavía), pero nadie
    puede confirmarla por HTTP hasta la parte E.
  - Parte C acotada a `PetServicePort`/`PetServiceAdapter`. `ServicesWebClientConfig`
    ya expone los 3 beans (`petServiceWebClient`, `medicalServiceWebClient`,
    `calendarServiceWebClient`) porque es barato, pero `MedicalServicePort` y
    `CalendarServicePort` no existen todavía — llegan con `registrar_vacuna`/
    `agendar_cita` (D3/D4).
  - Pendiente explícitamente para la próxima parada: D2-D4 (`crear_mascota`,
    `registrar_vacuna`, `agendar_cita` — los tools que escriben) y la Parte E completa
    (`POST /api/ai/actions/{id}/confirm|reject`).
- **Etapa 3, parte D2-D4 (checkpoint, tools que escriben con confirmación)**:
  - `PetServicePort` ganó `getPet(petId, rawJwt)` (verificación de propiedad) y
    `createPet(pet, rawJwt)`. `MedicalServicePort`/`CalendarServicePort` +
    `MedicalServiceAdapter`/`CalendarServiceAdapter` nuevos, mismo patrón que
    `PetServiceAdapter`: 401 → `UpstreamSessionExpiredException`; otro 4xx/5xx con
    cuerpo → `ToolExecutionException`; sin respuesta → se propaga tal cual. Sus
    respuestas se deserializan como `Map<String, Object>` genérico (el brief no da el
    contrato de respuesta de esos dos servicios, solo el de la petición).
  - `ToolArgs` (package-private, `infrastructure.tools`): helper compartido por los 3
    tools que escriben para leer/validar args crudos del modelo (`requireString`,
    `number`, `uuid`, `localDate`) — evita repetir el mismo parseo defensivo 3 veces.
  - **Verificación de propiedad de `petId`** (`registrar_vacuna`, y `agendar_cita`
    cuando trae `petId`): `getPet(petId, jwt)` y comparar `ownerId` contra
    `ctx.userId()`. Un 404 de pet-service (petId inventado) y un ownerId distinto
    (petId real pero de otro usuario) dan el mismo resultado — `ToolExecutionException`,
    nunca se llama a medical/calendar-service. **Importante para quien construya la
    parte E**: esta verificación vive en `execute()`, que el loop NO ejecuta hasta que
    la acción se confirma — al crear la pending action todavía no se verificó nada;
    la verificación real ocurre recién al confirmar.
  - **Normalización de fechas de `agendar_cita`** (`AgendarCitaTool.normalizeToUtc`):
    con offset explícito distinto de `Z` (ej. `-05:00`), se respeta tal cual. Sin
    offset, o con `Z` puesta ahí — la "Z sospechosa" del brief, el patrón real de
    Gemini es tacharla sin pensar en la zona del usuario —, se reinterpretan esos
    mismos números de reloj como hora local (`app.timezone`, `America/Bogota`) y se
    convierten a UTC de verdad. `endDate` ausente → `startDate` + 1h; `reminderAt`
    ausente → `startDate` − 1 día con `reminderEnabled: true`; fecha de inicio en el
    pasado → `ToolExecutionException` antes de intentar nada.
  - **`describe()` de estos 3 tools nunca menciona la mascota por nombre ni por
    UUID** — no se extendió la firma para recibir `UserContext`/hacer un lookup contra
    pet-service solo para mostrar un nombre bonito (eso hubiese significado una
    llamada HTTP dentro de `describe()`, sin JWT disponible en la firma actual). Para
    `registrar_vacuna`/`agendar_cita`, la tarjeta describe solo la acción en sí
    (vacuna + fecha; título + fecha) y deja que la app móvil resuelva `petId` → nombre
    con lo que ya tiene, si quiere mostrarlo.
  - **calendar-service rechaza offset explícito, solo acepta "Z" con milisegundos**:
    verificado con POST directo — `"...T10:00:00-05:00"` (ISO-8601 válido) → 500;
    `"...T15:00:00.000Z"` (mismo instante) → 201. `CalendarEventRequest.startDate/
    endDate/reminderAt` pasaron de `Instant` a `String`; `CalendarServiceAdapter`
    las formatea a mano con `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)` antes de armar el DTO, en vez de confiar en la
    serialización por defecto de Jackson para `Instant`. La normalización a hora
    local `America/Bogota` sigue intacta en `AgendarCitaTool` — este cambio es solo
    de formato de salida, no de zona horaria. Los args de la `pendingAction` (lo que
    ve el usuario en la tarjeta) siguen guardando el offset `-05:00` vía
    `AgendarCitaTool.validate()`; la conversión a "Z" ocurre únicamente dentro del
    adapter, justo antes del POST.
  - **Los 3 adapters de servicios destino (`PetServiceAdapter`,
    `MedicalServiceAdapter`, `CalendarServiceAdapter`) ahora loguean
    `ex.getResponseBodyAsString()` junto al código de estado.** Antes solo se
    logueaba "respondió 500" sin más contexto — diagnosticar el bug de arriba costó
    una llamada directa a calendar-service porque el log no decía qué venía mal en
    el cuerpo. No aplica a `SupabaseStorageAdapter`: ahí el cuerpo puede reflejar la
    key del objeto y se sigue logueando solo el código, a propósito (ver Seguridad).
  - **`registrar_vacuna` — `userId` no estaba en el contrato del brief pero
    medical-service lo exige** (`{"error": "userId es requerido para
    notificaciones"}`, encontrado en producción). Mismo patrón que `ownerId` en
    `crear_mascota` y `userId` en `agendar_cita`: `VaccineRecord` ganó un campo
    `userId` que `RegistrarVacunaTool.execute()` llena con `ctx.userId()` — nunca de
    `args`, y no se declara en `declaration()`. `VaccineRequest`/
    `MedicalServiceAdapter` lo propagan igual que `petId`/`name`.
  - **`IsoUtcDateFormatter`** (`infrastructure.adapters.out`, nuevo): extrae el
    formateador `"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"` que ya usaba `CalendarServiceAdapter`
    a un lugar compartido, porque `MedicalServiceAdapter` lo necesita también.
    `VaccineRequest.applicationDate/nextDoseDate` pasaron de `Instant` a `String` por
    la misma razón que `CalendarEventRequest`: medical-service es del mismo
    compañero que calendar-service, así que se asume el mismo rechazo de offsets
    explícitos aunque no esté confirmado con un POST directo (a diferencia de
    calendar-service, que sí se verificó).
  - **`RegistrarVacunaTool.parseIsoOrDate` no aceptaba offset explícito** — el
    modelo mandó `applicationDate` como `"2026-01-15T00:00:00-05:00"` en producción y
    la validación lanzaba `ToolExecutionException` porque `Instant.parse(...)` solo
    acepta literal `"Z"`, no un offset arbitrario. Cambiado a
    `OffsetDateTime.parse(value).toInstant()` como primer intento (acepta cualquier
    offset, incluida "Z"), con el mismo fallback a `LocalDate` para fechas de
    calendario sin hora. A diferencia de `agendar_cita`, acá no hay "Z sospechosa"
    que reinterpretar como hora local: la fecha de aplicación de una vacuna no es
    sensible a la zona horaria del usuario, así que cualquier offset explícito se
    respeta tal cual.
- **Etapa 3, parte E (checkpoint, confirmación de acciones)**:
  - **Bug encontrado al probar D2-D4**: una cita con fecha pasada (o una mascota
    ajena) creaba la pending action igual, porque `execute()` es lo único que
    validaba y no corre hasta confirmar — el usuario veía la tarjeta, confirmaba, y
    recién ahí fallaba. Fix: `AiTool` ganó un 5º método, `validate(args, ctx)`
    (`default` que devuelve los args tal cual — solo lo sobrescriben los 3 tools con
    confirmación), que corre las mismas validaciones de negocio que `execute()`
    (campos obligatorios, fecha en el pasado, propiedad del `petId`) pero sin
    escribir nada. `ChatLoopRunner` lo llama antes de crear la pending action; si
    falla, no se crea nada y el error vuelve al modelo como `functionResponse` (igual
    que cualquier otro tool de solo lectura), no se detiene el loop.
    `ToolExecutor.validate(...)` es el espejo de `execute(...)`: mismo contrato
    (nombre desconocido y `ToolExecutionException` → `ToolResult`;
    `UpstreamSessionExpiredException` se propaga sin convertirse). `execute()` sigue
    validando también — entre proponer y confirmar puede pasar hasta una hora.
  - **Los defaults calculados viajan en los args de la pending action.**
    `AgendarCitaTool` se partió en `prepare()` (parseo + normalización + validación,
    compartido por `execute()` y `validate()`) y `validate()` devuelve los args
    originales con `endDate`/`reminderAt`/`eventType` agregados, para que la tarjeta
    de confirmación muestre a qué hora termina la cita y cuándo llega el
    recordatorio, no solo lo que escribió el usuario. Esas fechas se serializan con
    el offset explícito de `app.timezone` (ej. `-05:00`), nunca en UTC/"Z": si se
    guardaran como "Z", `normalizeToUtc` las volvería a tratar como la "Z
    sospechosa" del modelo al confirmar y las desplazaría una segunda vez. Bogotá no
    tiene horario de verano, así que ese offset es siempre correcto de vuelta.
  - **La pending action no tiene columnas propias para `callId`/`thoughtSignature`.**
    En vez de migrar el esquema, `tool_args` guarda el mismo sobre que ya usan los
    mensajes `rol='tool'` desde la parte A-D1: `{callId, thoughtSignature,
    arguments}`. `PendingActionDto.args` (lo que ve el móvil) es solo `arguments`,
    desenvuelto; el sobre completo es lo que se persiste y lo que
    `ChatLoopRunner.confirmPendingAction` desenvuelve para reconstruir el
    `AiFunctionCall` original y reenviar el `thoughtSignature` tal cual al confirmar,
    sea cual sea el tiempo que pasó desde que se propuso.
  - `ChatLoopRunner` ganó `confirmPendingAction(ctx, pending)`: ejecuta el tool,
    persiste el turno `tool` (éxito o error — si la mascota dejó de ser del usuario
    en la última hora, por ejemplo) y sigue el loop normal (`run(...)`) para que el
    modelo redacte la respuesta final. Un 401 ahí se trata igual que en el resto del
    loop: `sessionExpiredResult(...)`, sin seguir.
  - `PendingActionService` (nuevo, implementa `PendingActionUseCase`) es dueño de
    las transiciones de estado — `ChatLoopRunner` no decide `PENDIENTE`/`CONFIRMADA`/
    `EXPIRADA`. `findByIdAndUser(id, userId)` vacío, o `estado != PENDIENTE`
    (confirmada, rechazada o ya expirada antes), son el mismo 404
    (`PendingActionNotFoundException`) — nunca un 403, mismo principio que
    `ConversationNotFoundException`. Solo si está `PENDIENTE` pero
    `expiresAt` ya pasó se marca `EXPIRADA` ahí mismo y se lanza
    `PendingActionExpiredException` → 410.
  - Rechazar marca `RECHAZADA`, persiste un mensaje `rol='model'` con un texto fijo
    (no hay `rol='system'` en el check de `ai_message`) y devuelve ese texto sin
    tocar `ChatLoopRunner` ni Gemini.
  - **Bug encontrado al probar confirm/reject en caliente**: `confirmPendingAction`
    marcaba la acción `CONFIRMADA` y ejecutaba el tool con éxito (la mascota SÍ se
    creaba), pero si Gemini fallaba justo después redactando el texto final (429 por
    cuota, contenido bloqueado, lo que sea) esa excepción se propagaba igual hasta el
    controller y el usuario recibía un error — con la mascota ya creada. Reintentar
    desde el móvil pensando que falló duplicaba la escritura.
  - **Primer intento de fix, incompleto**: envolver en el catch solo la rama
    `result.ok() == true` y dejar que el error del tool (`!result.ok()`) siguiera
    fluyendo "para que el modelo lo explique" — pero si Gemini también está caído no
    hay quién lo explique, y esa excepción seguía saliendo como 500 al cliente. Se
    detectó al probar el caso real (tool falla + Gemini caído), no en tests: los
    tests solo cubrían tool-ok+Gemini-falla.
  - **Fix real**: un único `try { return run(...); } catch (AiModelException |
    AiModelUnavailableException | AiContentBlockedException ex)` envuelve *ambos*
    desenlaces del tool. Si `result.ok()`, arma `"Listo: " + tool.describe(args)`
    (`localConfirmationReply`); si no, arma `"No pude completar esta acción: " +
    result.errorMessage()` (`localFailureReply`) — en ningún caso se propaga una
    falla de Gemini como error HTTP después de haber consumido la pending action,
    haya escrito algo o no.
  - **Pregunta abierta, sin resolver todavía**: `PendingActionService` marca
    `CONFIRMADA` *antes* de ejecutar el tool (orden que ya pedía el brief de la
    parte E: "Marcar CONFIRMADA, ejecutar el tool..."). Si el tool falla, la acción
    queda `CONFIRMADA` sin haber escrito nada y no se puede volver a confirmar la
    misma pending action — el usuario tiene que volver a pedírselo al asistente
    desde cero. Detectado al revisar el fix de arriba; falta decidir si se mantiene
    (ver conversación) o se cambia el orden/estados.

## Estado actual

- [x] Repo, `pom.xml`, `.gitignore`, `README.md`, `application.yml`, `.env.example`
- [x] **Etapa 1** — esqueleto hexagonal + `AiModelPort` y `GeminiAdapter` con WebClient,
      endpoint de texto plano.
- [x] **Etapa 2** — JWT, conversaciones persistentes, adjuntos multimodales
      (`inline_data` imagen/audio) y endpoints de lectura → F1, F2.
- [x] **Etapa 3** — `AiTool`, registry, executor, loop con guard de 5 iteraciones,
      los 4 tools (incluidos los que escriben con confirmación) y
      `POST /api/ai/actions/{id}/confirm|reject` → F3, F4.
- [ ] Etapa 4 — structured output para documentos → F5
- [ ] Etapa 5 — suscripciones push y sugerencias de cuidado → F6
- [ ] Etapa 6 — integración con MyAnimaLogVet

La etapa 5 depende de que `medical-service` publique `treatment-registered`,
`exam-registered` y `surgery-registered`. Plan B: que llame por HTTP a
`POST /api/ai/suggestions`.

## Al trabajar aquí

- No inventes el identificador del modelo de Gemini: viene de `GEMINI_MODEL`, nunca hardcoded.
- No agregues dependencias sin versión explícita si no están en el BOM del parent.
- Antes de tocar el esquema de base de datos, recuerda que `ddl-auto` es `none`: hay que
  escribir el SQL para Supabase también.
