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

## Convenciones de código

- Un controller y un service por funcionalidad.
- DTOs planos con `@Data @Builder @AllArgsConstructor @NoArgsConstructor`.
- `@Builder(toBuilder = true)` en modelos de dominio.
- Tests: `@MockitoBean`, `@WithMockUser`, `.with(csrf())`, `@ExtendWith(MockitoExtension.class)`.
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
- `IaServiceApplicationTests.contextLoads` (`@SpringBootTest` completo) depende de
  conectividad viva a Supabase (Transaction Pooler), Upstash Redis y GCP: es intermitente
  en local. En una corrida falló con `Unable to determine Dialect without JDBC metadata`
  al construir el `EntityManagerFactory` — no relacionado con esta etapa (no toca JPA),
  parece un hiccup del pooler. Si se repite seguido, vale la pena aislar ese test con un
  perfil o datasource de prueba en vez de pegarle a Supabase real en cada `mvn test`.

## Estado actual

- [x] Repo, `pom.xml`, `.gitignore`, `README.md`, `application.yml`, `.env.example`
- [x] **Etapa 1** — esqueleto hexagonal + `AiModelPort` y `GeminiAdapter` con WebClient,
      endpoint de texto plano.
- [ ] Etapa 2 — multimodal (`inline_data` imagen y audio) + persistencia → F1, F2 ← siguiente
- [ ] Etapa 3 — `AiTool`, registry, executor, loop con guard de 5 iteraciones → F3, F4
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
