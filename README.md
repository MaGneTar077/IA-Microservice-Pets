# MyAnimaLog — ai-service

Microservicio orquestador de IA del ecosistema MyAnimaLog. Expone un asistente conversacional
multimodal (texto, audio, imágenes y documentos) que responde preguntas, sugiere razas a partir
de fotos, ejecuta acciones sobre los demás microservicios mediante *function calling*, extrae
información estructurada de documentos veterinarios y genera sugerencias de cuidado reactivas
a eventos de negocio.

> **Principio de diseño:** este servicio **no contiene lógica de negocio**. No valida vacunas,
> no calcula fechas de cita, no decide permisos. Traduce intenciones del usuario en llamadas
> HTTP a los servicios que ya son dueños de esos datos. Si cambia una regla de negocio en
> `pet-service`, el `ai-service` no se entera.

---

## Tabla de contenidos

1. [Lugar en el ecosistema](#lugar-en-el-ecosistema)
2. [Stack tecnológico](#stack-tecnológico)
3. [Funcionalidades](#funcionalidades)
4. [Arquitectura](#arquitectura)
5. [Estructura de paquetes](#estructura-de-paquetes)
6. [El catálogo de tools](#el-catálogo-de-tools)
7. [El loop de chat](#el-loop-de-chat)
8. [Endpoints](#endpoints)
9. [Eventos de Pub/Sub](#eventos-de-pubsub)
10. [Modelo de datos](#modelo-de-datos)
11. [Configuración](#configuración)
12. [Ejecución local](#ejecución-local)
13. [Despliegue en Cloud Run](#despliegue-en-cloud-run)
14. [Convenciones de código](#convenciones-de-código)
15. [Roadmap](#roadmap)

---

## Lugar en el ecosistema

MyAnimaLog es un ecosistema de dos productos: una app móvil Ionic (B2C, dueños de mascotas) y
un panel web Next.js (B2B por suscripción, clínicas veterinarias), sobre microservicios
backend. Este servicio pertenece al lado B2C; la integración con MyAnimaLogVet queda para una
fase posterior.

| Servicio | Puerto | Tecnología | Relación con ai-service |
|---|---|---|---|
| `api-gateway` | 8090 | Spring Boot | Enruta el tráfico entrante y valida el JWT |
| `user-service` | 8080 | Spring Boot | Identidad del usuario |
| `pet-service` | 8081 | Spring Boot | Destino de `crear_mascota`, `registrar_vacuna`, `listar_mascotas` |
| `veterinary-service` | 8082 | Spring Boot | Consulta de veterinarias (fase 2) |
| **`ai-service`** | **8083** | **Spring Boot** | **Este repo** |
| `medical-service` | 3000 | Node.js | Emite eventos de tratamiento/examen/cirugía |
| `calendar-service` | 3001 | Node.js | Destino de `agendar_cita` (`/api/calendar-events`) |
| `notification-service` | — | Node.js | Subscriber de `ai-suggestion-generated` |

---

## Stack tecnológico

- Java 17 + Spring Boot 3.5.16
- Arquitectura hexagonal (ports & adapters)
- Google AI Studio / Gemini API (modelo multimodal con *function calling* y *structured output*)
- PostgreSQL en Supabase, Transaction Pooler (`prepareThreshold=0`), `ddl-auto: none`
- Google Cloud Pub/Sub (suscripciones **push**)
- Upstash Redis para rate limiting de llamadas al modelo
- Supabase Storage para adjuntos
- Google Cloud Run para despliegue

---

## Funcionalidades

| # | Funcionalidad | Mecanismo | Servicios que toca |
|---|---|---|---|
| 1 | Chat con preguntas, foto, audio y archivos | HTTP síncrono | — |
| 2 | Sugerencia de raza a partir de foto | HTTP síncrono | — |
| 3 | Agendar citas en el calendario | Function calling | `calendar-service` |
| 4 | Añadir mascota y registrar vacuna | Function calling | `pet-service` |
| 5 | Extraer examen, tratamiento y cirugía de un documento veterinario | Structured output | `medical-service` (vía confirmación del usuario) |
| 6 | Sugerencias de cuidado tras registrar un tratamiento | Evento de Pub/Sub | `medical-service`, `notification-service` |

Las funcionalidades 1 y 2 comparten el mismo endpoint: si el usuario sube una foto sin texto,
la instrucción de sistema hace que el modelo infiera la raza.

---

## Arquitectura

```
App móvil (Ionic)
      │
      ▼
api-gateway (8090)  ──valida JWT──►  ai-service (8083)
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
             Gemini API           Servicios de negocio      Pub/Sub
        (genera y elige tools)   (HTTP con el JWT del      (push subscriptions)
                                      usuario)
```

**Reglas de frontera:**

- El `ai-service` **nunca decide permisos**. Propaga el JWT del usuario en cada tool call; si
  el modelo alucina un `petId` ajeno, `pet-service` responde 403 y ese 403 vuelve al modelo
  como `functionResponse` para que se lo explique al usuario. La IA es un cliente más, no un
  *security boundary*.
- Las llamadas salientes van **al `api-gateway`**, no a cada servicio por separado: una sola
  base URL y la autenticación ya montada. El hop extra es despreciable frente a la latencia
  del modelo.
- La ruta `/internal/**` la invoca Pub/Sub con un ID token de Google, no un usuario. Debe
  quedar fuera del filtro de JWT del gateway y no expuesta públicamente.

---

## Estructura de paquetes

Base: `com.MyAnimaLog.AI`

```
com.MyAnimaLog.AI
├── domain
│   ├── model/              Conversation, Message, AiSuggestion, ExtractedRecord
│   └── ports
│       ├── in/             ChatUseCase, AnalyzeDocumentUseCase, GenerateCareSuggestionUseCase
│       └── out/            AiModelPort, PetServicePort, CalendarServicePort,
│                           MedicalServicePort, ConversationRepositoryPort, EventPublisherPort
├── application
│   ├── dto/                ChatRequest, ChatResponse, ToolCall, ToolResult, *Event
│   └── service/            ChatService, AnalyzeDocumentService, GenerateCareSuggestionService
└── infrastructure
    ├── adapters
    │   ├── in/             AiChatController, PubSubPushController
    │   └── out/            GeminiAdapter, PetServiceHttpAdapter, CalendarServiceHttpAdapter,
    │                       MedicalServiceHttpAdapter, JpaConversationRepository,
    │                       GooglePubSubAiEventAdapter
    ├── tools/              AiTool, ToolRegistry, ToolExecutor, CrearMascotaTool,
    │                       RegistrarVacunaTool, AgendarCitaTool, ListarMascotasTool
    └── config/             WebClientConfig, SecurityConfig, GeminiProperties
```

---

## El catálogo de tools

Cada acción que la IA puede ejecutar es un `@Component` que implementa `AiTool`. El
`ToolRegistry` inyecta `List<AiTool>` y arma el catálogo automáticamente, así que agregar una
funcionalidad nueva es crear una clase más.

```java
public interface AiTool {
    String name();
    Map<String, Object> declaration();   // JSON schema que entiende Gemini
    boolean requiresConfirmation();      // true = propone, no ejecuta directo
    Object execute(Map<String, Object> args, UserContext ctx);
}
```

| Tool | Confirmación | Destino |
|---|---|---|
| `listar_mascotas` | no | `GET /api/pets` |
| `crear_mascota` | sí | `POST /api/pets` |
| `registrar_vacuna` | sí | `POST /api/pets/{id}/vaccines` |
| `agendar_cita` | sí | `POST /api/calendar-events` |

Los tools con `requiresConfirmation() == true` no ejecutan de una: devuelven un `pendingAction`
al móvil, la app pinta una tarjeta con los datos propuestos y un botón, y al confirmar el
cliente reenvía la petición con `confirmedActionId` para que el servicio retome el loop.

---

## El loop de chat

```
1. POST /api/ai/chat  (multipart: texto, audio, imágenes, conversationId)
2. ChatService carga el historial de la conversación desde Postgres
3. Arma el request a Gemini:
      systemInstruction = instrucción de sistema de MyAnimaLog
      contents          = historial + turno nuevo (text + inline_data)
      tools             = ToolRegistry.declarations()
4. Llamada a Gemini
5. ¿La respuesta trae functionCall?
      NO  -> texto final: persistir y devolver. FIN.
      SÍ  -> ToolExecutor resuelve el tool por nombre
              requiresConfirmation && !confirmado -> devolver pendingAction. FIN.
              si no -> ejecutar (HTTP con el JWT del usuario)
                       agregar el resultado como functionResponse
                       volver al paso 4  (máximo 5 iteraciones)
```

El guard de 5 iteraciones no es opcional: sin él, un modelo que entre en bucle llamando el
mismo tool cuelga el request y quema la cuota.

---

## Endpoints

### `POST /api/ai/chat`

`multipart/form-data`. Resuelve chat, sugerencia de raza y todas las acciones por tool calling.

| Campo | Tipo | Obligatorio |
|---|---|---|
| `conversationId` | uuid | no (si falta, se crea una nueva) |
| `message` | text | no |
| `files` | file[] | no (imágenes, audio, PDF) |
| `confirmedActionId` | uuid | no (para confirmar un `pendingAction`) |

Respuesta:

```json
{
  "conversationId": "…",
  "reply": "Parece un mestizo con rasgos de labrador…",
  "pendingAction": {
    "id": "…",
    "tool": "crear_mascota",
    "args": { "nombre": "Luna", "especie": "PERRO", "raza": "Labrador" }
  },
  "executedActions": []
}
```

### `POST /api/ai/documents/analyze`

Recibe un PDF o foto de un documento veterinario y devuelve JSON estructurado usando
`responseMimeType: application/json` más un `responseSchema` que refleja el DTO de
`medical-service`. **No escribe nada.** La app pinta un formulario prellenado, el usuario
revisa y corrige, y el móvil hace el `POST` a `medical-service`.

El esquema incluye un campo `confianza` por sección y el `textoOriginal`, para que la app
pueda resaltar lo que el modelo no leyó bien.

> Son datos médicos. Nada se escribe sin confirmación humana.

### `POST /internal/events/{tipo}`

Endpoint push de Pub/Sub. Fuera del filtro de JWT, protegido por IAM.

---

## Eventos de Pub/Sub

### Entrantes (suscripciones push)

| Topic | Emisor | Efecto |
|---|---|---|
| `treatment-registered` | `medical-service` | Genera sugerencias de cuidado |
| `exam-registered` | `medical-service` | Genera sugerencias de cuidado |
| `surgery-registered` | `medical-service` | Genera sugerencias de cuidado |

Payload mínimo esperado: `petId`, `userId`, `tipo`, `descripcion`, `medicamentos`, `occurredAt`.

### Salientes

| Topic | Consumidor |
|---|---|
| `ai-suggestion-generated` | `notification-service` |

### Por qué push y no pull

En Cloud Run el contenedor escala a cero y la CPU se estrangula fuera de un request, así que
una conexión `StreamingPull` se muere. Push es el patrón idiomático y permite seguir escalando
a cero.

```bash
gcloud pubsub subscriptions create ai-treatment-registered-push \
  --topic=treatment-registered \
  --push-endpoint=https://ai-service-XXXX.us-central1.run.app/internal/events/treatment-registered \
  --push-auth-service-account=ai-service-sa@PROJECT_ID.iam.gserviceaccount.com \
  --ack-deadline=60
```

### Idempotencia

Pub/Sub garantiza *at-least-once*. Antes de llamar al modelo se verifica el `messageId` contra
`ai_processed_event`; si ya existe, se responde 200 y se descarta. Sin esto se generan
sugerencias duplicadas y se pagan llamadas dobles.

---

## Modelo de datos

`ddl-auto: none`. Las tablas se crean a mano en Supabase.

```sql
create table ai_conversation (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null,
  titulo     text,
  created_at timestamptz not null default now()
);

create table ai_message (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references ai_conversation(id) on delete cascade,
  rol             text not null,            -- user | model | tool
  contenido       text,
  tool_name       text,
  tool_payload    jsonb,
  created_at      timestamptz not null default now()
);
create index on ai_message (conversation_id, created_at);

create table ai_suggestion (
  id         uuid primary key default gen_random_uuid(),
  pet_id     uuid not null,
  origen     text not null,                 -- TREATMENT | EXAM | SURGERY
  origen_id  uuid,
  contenido  text not null,
  created_at timestamptz not null default now()
);

create table ai_processed_event (
  message_id   text primary key,
  processed_at timestamptz not null default now()
);
```

Los adjuntos no se guardan en Postgres: van a Supabase Storage y en `ai_message` queda la URL.

---

## Configuración

### Variables de entorno

| Variable | Descripción | Origen en Cloud Run |
|---|---|---|
| `PORT` | Puerto HTTP. Cloud Run lo inyecta — **nunca usar `SERVER_PORT`** | automático |
| `GEMINI_API_KEY` | API key de Google AI Studio | Secret Manager |
| `GEMINI_MODEL` | Identificador del modelo | env var |
| `JWT_SECRET` | **Idéntico** al de user, pet, veterinary y gateway | Secret Manager |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Supabase Transaction Pooler | Secret Manager |
| `GCP_PROJECT_ID` | Proyecto de Google Cloud | env var |
| `GATEWAY_URL` | Base URL del api-gateway para tool calls | env var |
| `REDIS_HOST`, `REDIS_PASSWORD` | Upstash, para rate limiting | Secret Manager |

### `application.yml`

```yaml
server:
  port: ${PORT:8083}

spring:
  application:
    name: ai-service
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      data-source-properties:
        prepareThreshold: 0
  jpa:
    hibernate:
      ddl-auto: none
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 25MB
  cloud:
    gcp:
      project-id: ${GCP_PROJECT_ID}
      pubsub:
        enabled: true

gemini:
  api-key: ${GEMINI_API_KEY}
  model: ${GEMINI_MODEL}
  base-url: https://generativelanguage.googleapis.com/v1beta
  max-tool-iterations: 5
  timeout-seconds: 60

services:
  gateway-url: ${GATEWAY_URL}
```

---

## Ejecución local

Requisitos: JDK 17, Maven 3.9+, `gcloud` CLI.

```bash
# Credenciales para los SDKs (NO 'gcloud auth login', que solo autentica el CLI)
gcloud auth application-default login

cp .env.example .env      # y completar valores
./mvnw spring-boot:run
```

Swagger queda en `http://localhost:8083/swagger-ui.html`, health en `/actuator/health`.

---

## Despliegue en Cloud Run

`Dockerfile` multi-stage. La imagen runtime debe ser **Ubuntu (glibc), no Alpine**: las
librerías nativas de gRPC/Netty que trae `spring-cloud-gcp-starter-pubsub` son incompatibles
con musl libc y el contenedor muere con `Uncaught signal: 6 (SIGABRT)` al arrancar.

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests
RUN mv target/*.jar app.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
COPY --from=build /app/app.jar app.jar
RUN chown -R appuser:appgroup /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

El `mv target/*.jar app.jar` con wildcard evita el clásico `COPY failed: no source files were
specified` cuando el `artifactId` del pom no coincide con el nombre que se asumió.

Secretos:

```bash
echo "valor" | gcloud secrets create gemini-api-key --data-file=-
gcloud secrets add-iam-policy-binding gemini-api-key \
  --member="serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

Y en el deploy: `--set-secrets="GEMINI_API_KEY=gemini-api-key:latest,JWT_SECRET=jwt-secret:latest,…"`.

La service account necesita `roles/pubsub.publisher` sobre `ai-suggestion-generated`, y la
cuenta que usa Pub/Sub para el push necesita `roles/run.invoker` sobre este servicio.

---

## Convenciones de código

Las mismas del resto del ecosistema:

- Un controller y un service por funcionalidad.
- DTOs planos con Lombok: `@Data`, `@Builder`, `@AllArgsConstructor`, `@NoArgsConstructor`.
- `@Builder(toBuilder = true)` en los modelos de dominio.
- Tests con `@MockitoBean`, `@WithMockUser`, `.with(csrf())`, `@ExtendWith(MockitoExtension.class)`.
- En tests, preferir `.builder()...build()` sobre constructores posicionales: aguanta mejor que
  se agreguen campos al modelo.
- Al agregar un parámetro nuevo a un constructor con `@RequiredArgsConstructor`, revisar los
  tests que instancian la clase a mano (`new XxxService(mockA)`) — rompen con
  `cannot be applied to given types`.

### Reglas de contenido para el modelo

- La IA **no diagnostica ni prescribe**. Sugiere y recomienda acudir al veterinario.
- La raza siempre en lenguaje probable ("parece un mestizo con rasgos de labrador"), nunca
  como afirmación categórica.
- La zona horaria del usuario (`America/Bogota`) va en la instrucción de sistema, o todas las
  citas terminan agendadas en UTC.

---

## Roadmap

| Etapa | Alcance | Estado |
|---|---|---|
| 1 | Esqueleto hexagonal + `GeminiAdapter` con WebClient, endpoint de texto plano | ⬜ |
| 2 | Multimodal (`inline_data` para imagen y audio) + persistencia de conversaciones → F1, F2 | ⬜ |
| 3 | `AiTool`, registry, executor, loop con guard → F3, F4 | ⬜ |
| 4 | Structured output para documentos → F5 | ⬜ |
| 5 | Suscripciones push y sugerencias de cuidado → F6 | ⬜ |
| 6 | Integración con MyAnimaLogVet | ⬜ |

La etapa 5 depende de que `medical-service` publique sus eventos. Plan B mientras tanto: que
llame por HTTP a `POST /api/ai/suggestions`.

Empezar por la etapa 1: hasta que una llamada real a Gemini funcione desde el contenedor, todo
lo demás es especulación.
