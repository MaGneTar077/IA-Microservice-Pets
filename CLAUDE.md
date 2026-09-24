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

## Estado actual

- [x] Repo, `pom.xml`, `.gitignore`, `README.md`, `application.yml`, `.env.example`
- [x] **Etapa 1** — esqueleto hexagonal + `AiModelPort` y `GeminiAdapter` con WebClient,
      endpoint de texto plano.
- [x] **Etapa 2** — JWT, conversaciones persistentes, adjuntos multimodales
      (`inline_data` imagen/audio) y endpoints de lectura → F1, F2.
- [ ] Etapa 3 — `AiTool`, registry, executor, loop con guard de 5 iteraciones → F3, F4 ← siguiente
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
