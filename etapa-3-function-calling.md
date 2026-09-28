# Etapa 3 — Function calling: la IA ejecuta acciones

Lee `CLAUDE.md` antes de empezar. Rama: `feature/etapa-3-function-calling`.

## Objetivo

La IA deja de solo responder y empieza a **hacer**: crear mascotas, registrar vacunas y
agendar citas en los microservicios que ya existen, a partir de lo que el usuario pide en
lenguaje natural.

Completa las funcionalidades 3 y 4 del proyecto.

## Fuera de alcance

- Análisis de documentos con structured output (etapa 4)
- Pub/Sub y sugerencias de cuidado (etapa 5)
- Rate limiting con Redis

---

# ⚠️ Principio de seguridad — leer primero

**Lo que devuelve el modelo es entrada no confiable.** Un modelo puede alucinar
identificadores, y un usuario puede inducirlo con instrucciones en su mensaje.

Dos reglas que no se negocian:

**1. Ningún identificador de propiedad viene del modelo.**
`ownerId` y `userId` se inyectan desde el `UserContext` del JWT. Si el modelo los incluye
en los argumentos, **se descartan en silencio**. No se declaran en el JSON schema de
ningún tool, para que el modelo ni siquiera sepa que existen.

**2. Todo `petId` se verifica antes de usarse.**
Antes de registrar una vacuna o agendar una cita con `petId`, el tool comprueba que esa
mascota pertenezca al usuario autenticado (contra `GET /pets/user/{userId}`). Si no, la
llamada no se ejecuta y se le devuelve al modelo un `functionResponse` de error.

Esto importa especialmente porque `POST /api/vaccines` **no recibe ningún dato del dueño**:
la autorización tiene que hacerla este servicio.

---

# Parte A — Infraestructura de tools

## A1. La interfaz

En `infrastructure.tools`:

```java
public interface AiTool {
    String name();
    Map<String, Object> declaration();   // JSON schema para Gemini
    boolean requiresConfirmation();
    Object execute(Map<String, Object> args, UserContext ctx);
}
```

## A2. Registry y executor

- `ToolRegistry` inyecta `List<AiTool>` y expone `declarations()` y `findByName(String)`.
  Agregar un tool nuevo debe ser crear una clase, nada más.
- `ToolExecutor` resuelve por nombre y ejecuta. Un nombre desconocido no lanza excepción:
  devuelve un resultado de error para que el modelo lo lea y se corrija.

## A3. Resultado de un tool

```java
record ToolResult(boolean ok, Object data, String errorMessage) {}
```

**Los errores de un tool no son excepciones que rompan la petición.** Un 403, un 404 o una
validación fallida se devuelven al modelo como `functionResponse` para que se lo explique
al usuario en lenguaje natural. Solo un fallo de infraestructura (no hay red, el servicio
no responde) corta el flujo.

---

# Parte B — El loop de function calling

## B1. DTOs de Gemini

Extender los DTOs existentes:

- **Request**: `tools` con `function_declarations`; partes de tipo `functionCall` y
  `functionResponse`.
- **Response**: una part puede traer `functionCall` con `name`, `args` y **`id`**.

Verificado en pruebas reales, dos detalles que hay que respetar:

- El `functionCall` trae un `id` (ej. `"call_1599361"`). **Hay que devolverlo en el
  `functionResponse`** para que el modelo empareje la respuesta con su llamada.
- La respuesta puede traer `thoughtSignature` junto al `functionCall`. **Reenvíalo tal cual
  en el turno del modelo** al continuar la conversación. Si al probar resulta que no hace
  falta, documéntalo en `CLAUDE.md` y quítalo.

## B2. El ciclo

```
1. Llamar a Gemini con historial + declaraciones de tools
2. ¿La respuesta trae functionCall?
     NO  -> texto final: persistir y devolver. FIN.
     SÍ  -> para cada functionCall:
              requiresConfirmation == false -> ejecutar ahora
              requiresConfirmation == true  -> crear pending action y PARAR el loop
            si se ejecutó algo:
              persistir un mensaje rol='tool' por cada ejecución
              agregar el turno del modelo (con functionCall y thoughtSignature)
              agregar los functionResponse
              volver al paso 1
3. Máximo gemini.max-tool-iterations vueltas (default 5).
   Al agotarse: responder con un mensaje claro al usuario, no un error técnico.
```

Reglas del paso 2:

- Si una respuesta trae **varios** `functionCall`, ejecuta todos los de solo lectura.
- Si hay alguno que requiere confirmación, crea **una sola** pending action (la primera) y
  detén el loop. Nada de confirmaciones en cadena.

## B3. Persistencia de los turnos de tool

La tabla `ai_message` ya tiene `rol='tool'`, `tool_name`, `tool_args` y `tool_result` desde
la etapa 2. Ahora sí se usan.

Al reconstruir el historial para Gemini, los mensajes `tool` se traducen a sus
`functionCall` y `functionResponse` correspondientes.

## B4. Instrucción de sistema

Agregar a `prompts/system-prompt.txt`, con sustitución en tiempo de ejecución:

```
Hoy es {fecha} ({dia_semana}). La zona horaria del usuario es America/Bogota (UTC-5).
El usuario se llama {username}.
```

`username` sale del claim `username` del JWT, que ya está disponible.

> **Por qué la fecha:** el modelo no sabe qué día es. Sin esto no puede resolver "el
> martes" ni "mañana".

Y estas reglas de comportamiento:

```
Cuando el usuario mencione una mascota por su nombre, usa listar_mascotas para
obtener su identificador. Nunca inventes identificadores.

Antes de crear o registrar algo, asegúrate de tener los datos obligatorios. Si
falta alguno, pregúntalo en lugar de suponerlo.

Nunca reveles identificadores internos (UUID) al usuario: habla de las mascotas
por su nombre.
```

---

# Parte C — Clientes HTTP de los microservicios

Un `WebClient` por servicio, con base URL configurable:

```yaml
services:
  pet-service-url: ${PET_SERVICE_URL:http://localhost:8081}
  medical-service-url: ${MEDICAL_SERVICE_URL:http://localhost:3000}
  calendar-service-url: ${CALENDAR_SERVICE_URL:https://calendar-microservice-20369076707.us-central1.run.app}
```

> Base URL por servicio en vez de todo a través del `api-gateway`: en desarrollo cada uno
> corre en un sitio distinto y el gateway puede no enrutar los de Node. En producción se
> apunta al gateway cambiando variables de entorno, sin tocar código.

**Propagación del JWT.** Cada llamada saliente lleva el `Authorization: Bearer <rawJwt>`
del usuario, que ya está en `UserContext` desde la etapa 2.

Puertos en `domain.ports.out`: `PetServicePort`, `MedicalServicePort`, `CalendarServicePort`.
Adapters en `infrastructure.adapters.out`. Timeout de 10 segundos por llamada; estos
servicios son rápidos, no hay razón para esperar como con Gemini.

## Contratos confirmados

### pet-service (Java, 8081)

```
GET  /pets/user/{userId}     → lista de mascotas del usuario
GET  /pets/{petId}           → una mascota
POST /pets
```

```json
{
  "ownerId": "uuid",
  "name": "Firulais",
  "species": "Perro",
  "breed": "Labrador",
  "sex": "MALE",
  "birthDate": "2022-01-15",
  "height": 45.5,
  "weight": 12.3
}
```

`sex`: `MALE` | `FEMALE`. `species` y `breed` son texto libre.
`birthDate` es fecha sin hora.

### medical-service (Node, 3000)

```
POST /api/vaccines
```

```json
{
  "petId": "uuid",
  "name": "Rabia",
  "lotNumber": "LOT-2024-001",
  "applicationDate": "2026-01-15T00:00:00.000Z",
  "nextDoseDate": "2027-01-15T00:00:00.000Z",
  "veterinarian": "Dr. Pérez",
  "notes": "Primera dosis anual"
}
```

### calendar-service (Node, Cloud Run)

```
POST /api/calendar-events
```

```json
{
  "userId": "uuid",
  "petId": "uuid",
  "title": "Cita control Paco",
  "description": "…",
  "eventType": "VET_APPOINTMENT",
  "startDate": "2026-09-19T10:00:00.000Z",
  "endDate": "2026-09-19T11:00:00.000Z",
  "location": "…",
  "reminderAt": "2026-09-18T10:00:00.000Z",
  "reminderEnabled": true
}
```

`petId` es opcional (eventos del usuario sin mascota asociada).

---

# Parte D — Los cuatro tools

Todos en `infrastructure.tools`. Ninguno declara `ownerId` ni `userId` en su schema.

## D1. `listar_mascotas` — sin confirmación

Sin parámetros. Llama a `GET /pets/user/{userId del JWT}`.

Devuelve al modelo solo lo necesario: `id`, `name`, `species`, `breed`, `sex`, `birthDate`.
No le pases el objeto completo: son tokens que se pagan en cada vuelta.

## D2. `crear_mascota` — con confirmación

| Parámetro | Tipo | Obligatorio |
|---|---|---|
| `name` | string | sí |
| `species` | string | sí |
| `breed` | string | no |
| `sex` | enum `MALE`/`FEMALE` | no |
| `birthDate` | string `yyyy-MM-dd` | no |
| `height` | number (cm) | no |
| `weight` | number (kg) | no |

`ownerId` se inyecta desde el JWT.

## D3. `registrar_vacuna` — con confirmación

| Parámetro | Tipo | Obligatorio |
|---|---|---|
| `petId` | string uuid | sí |
| `name` | string | sí |
| `applicationDate` | string ISO | sí |
| `nextDoseDate` | string ISO | no |
| `lotNumber` | string | no |
| `veterinarian` | string | no |
| `notes` | string | no |

**Verificar propiedad del `petId` antes de llamar.** Si la mascota no es del usuario,
devolver un `ToolResult` de error, nunca hacer la petición.

## D4. `agendar_cita` — con confirmación

| Parámetro | Tipo | Obligatorio |
|---|---|---|
| `title` | string | sí |
| `startDate` | string ISO con zona | sí |
| `petId` | string uuid | no |
| `description` | string | no |
| `eventType` | string | no (default `VET_APPOINTMENT`) |
| `endDate` | string ISO | no |
| `location` | string | no |
| `reminderAt` | string ISO | no |

`userId` desde el JWT. Si hay `petId`, verificar propiedad.

**Normalización de fechas — obligatoria.** En pruebas reales el modelo devolvió
`2026-09-29T10:00:00Z` cuando el usuario dijo "las 10 de la mañana": son las 5 a.m. en
Colombia. El tool debe:

- Interpretar una fecha sin zona, o con `Z` sospechosa, como hora local `America/Bogota`,
  y convertirla a UTC antes de enviarla.
- `endDate` ausente → `startDate` + 1 hora.
- `reminderAt` ausente → `startDate` − 1 día, con `reminderEnabled: true`.
- Fecha en el pasado → error del tool, para que el modelo pregunte.

> Los ejemplos de Postman tienen un evento que empieza el 19 y termina el 24. No copies esa
> lógica: una cita veterinaria dura una hora.

---

# Parte E — Confirmación de acciones

## E1. Tabla

`ai_pending_action` ya existe en `db/schema.sql`. Mapear la entidad respetando el
`check` de `estado`: `PENDIENTE`, `CONFIRMADA`, `RECHAZADA`, `EXPIRADA`.

## E2. Respuesta del chat con acción pendiente

`POST /api/ai/chat` puede devolver:

```json
{
  "conversationId": "…",
  "reply": "Voy a registrar a Luna como labradora. ¿Confirmas?",
  "model": "gemini-3.6-flash",
  "pendingAction": {
    "id": "…",
    "tool": "crear_mascota",
    "descripcion": "Registrar a Luna (Perro, Labrador)",
    "args": { "name": "Luna", "species": "Perro", "breed": "Labrador" },
    "expiresAt": "…"
  }
}
```

`descripcion` es texto legible que el móvil pinta en la tarjeta. Los `args` van para que el
usuario pueda revisarlos; **no muestres UUID**.

## E3. Endpoints

```
POST /api/ai/actions/{id}/confirm
POST /api/ai/actions/{id}/reject
```

**Confirmar:**
1. Verificar que la acción exista, sea del usuario y esté `PENDIENTE` → si no, 404.
2. Si `expires_at` ya pasó → marcar `EXPIRADA` y responder **410**.
3. Marcar `CONFIRMADA`, ejecutar el tool, persistir el mensaje `rol='tool'`.
4. Continuar el loop para que el modelo redacte la respuesta final.
5. Devolver el mismo formato que `/api/ai/chat`.

**Rechazar:** marcar `RECHAZADA`, persistir un mensaje de sistema indicando que el usuario
canceló, y responder con un texto fijo. **No llames a Gemini para esto**: es gastar dinero
en decir "de acuerdo".

## E4. Acciones expiradas

No hace falta un job de limpieza. Basta con que al leer una acción vencida se marque
`EXPIRADA` y se rechace la confirmación.

---

# Configuración nueva

```yaml
services:
  pet-service-url: ${PET_SERVICE_URL:http://localhost:8081}
  medical-service-url: ${MEDICAL_SERVICE_URL:http://localhost:3000}
  calendar-service-url: ${CALENDAR_SERVICE_URL:https://calendar-microservice-20369076707.us-central1.run.app}
  timeout-seconds: ${SERVICES_TIMEOUT:10}

app:
  timezone: ${APP_TIMEZONE:America/Bogota}

gemini:
  max-tool-iterations: ${GEMINI_MAX_TOOL_ITERATIONS:5}
```

Actualizar `.env.example`.

---

# Tests

Sin dependencias nuevas, sin tocar servicios reales. `ExchangeFunction` simulada como en
las etapas anteriores.

**Seguridad (los más importantes):**
- El modelo manda `ownerId` en los args de `crear_mascota` → se descarta y se usa el del JWT.
- `registrar_vacuna` con un `petId` que no es del usuario → no se llama a `medical-service`
  y el modelo recibe un error.
- Confirmar una acción de otro usuario → 404.
- Confirmar una acción ya `CONFIRMADA` → 404.
- Confirmar una acción vencida → 410 y queda `EXPIRADA`.

**Loop:**
- Una vuelta: `functionCall` → ejecución → `functionResponse` → texto final.
- Dos vueltas encadenadas: `listar_mascotas` y después `agendar_cita`.
- Tope de iteraciones alcanzado → mensaje al usuario, no excepción.
- Un tool que requiere confirmación detiene el loop y crea la pending action.
- El `id` del `functionCall` viaja de vuelta en el `functionResponse`.

**Fechas:**
- `"2026-09-29T10:00:00Z"` del modelo con el usuario en Bogotá → se envía como las 10 de la
  mañana hora local.
- `endDate` ausente → `startDate` + 1 h.
- Fecha en el pasado → error del tool.

**Tools:**
- Cada tool con respuesta correcta, con 4xx del servicio destino, y con timeout.
- Nombre de tool desconocido → error para el modelo, sin excepción.

---

# Definición de terminado

Con un JWT real y los servicios corriendo:

- [ ] "¿Cuáles son mis mascotas?" → las lista por nombre, sin mostrar UUID
- [ ] "Registra a Luna, es una labradora hembra que nació el 15 de enero de 2022" →
      devuelve `pendingAction`; al confirmar, la mascota aparece en `pet-service`
- [ ] "Agéndame la vacuna de Luna el próximo martes a las 10 de la mañana" → el modelo
      llama primero a `listar_mascotas`, luego propone `agendar_cita`; al confirmar, el
      evento queda en `calendar-service` **a las 10:00 hora Colombia**
- [ ] Rechazar una acción no crea nada y no llama a Gemini
- [ ] Una acción vencida devuelve 410
- [ ] En `ai_message` quedan las filas con `rol='tool'`, `tool_name` y `tool_result`
- [ ] `./mvnw clean test` pasa sin variables de entorno reales
- [ ] `CLAUDE.md` actualizado, incluido si el `thoughtSignature` hizo falta o no
- [ ] Resumen de máximo 10 líneas

---

# Orden sugerido

A → B con **solo** `listar_mascotas` → probar → D (resto de tools) → E (confirmación).

`listar_mascotas` es de solo lectura: si el loop falla, no escribe nada en ningún lado.
**Detente después de que `listar_mascotas` funcione de punta a punta** para que yo lo
pruebe antes de que agregues los tools que escriben.
