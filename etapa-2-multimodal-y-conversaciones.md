# Etapa 2 — JWT, conversaciones persistentes y entrada multimodal

Lee `CLAUDE.md` antes de empezar. Rama: `feature/etapa-2-multimodal-y-conversaciones`.

## Objetivo

Al terminar esta etapa, el asistente **recuerda** y **ve**. Un usuario autenticado podrá
subir una foto de su mascota y preguntar por la raza, y en el siguiente mensaje decir
"¿y cuánto debería pesar?" sin repetir el contexto.

Esto completa las funcionalidades 1 y 2 del proyecto.

## Fuera de alcance (no implementar)

- Tool calling / function calling (etapa 3)
- La tabla `ai_pending_action` (etapa 3)
- Análisis de documentos con structured output (etapa 4)
- Pub/Sub y sugerencias de cuidado (etapa 5)
- Rate limiting con Redis

Si algo de esto parece necesario, detente y avísame.

## Trabajo previo del usuario (verificar antes de empezar)

- Las tablas ya existen en Supabase (`db/schema.sql`). **No se crean por código**:
  `ddl-auto` es `none`.
- El bucket `ai-attachments` debe existir en Supabase Storage, privado.
- Variables nuevas en el entorno: `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET`.

---

# Parte A — Autenticación JWT

Reemplaza el `SecurityConfig` temporal de la etapa 1.

## A1. Filtro

Un `JwtAuthenticationFilter` (`OncePerRequestFilter`) que:

- Lee el header `Authorization: Bearer <token>`.
- Valida la firma HS256 con `security.jwt.secret` usando jjwt (ya está en el pom).
- Extrae el identificador del usuario del claim configurable
  `security.jwt.user-id-claim` (default `sub`) y lo deja en el `SecurityContext`.
- Token ausente, expirado o inválido → **401** con el mismo formato de error del
  `ApiExceptionHandler`, sin stack trace al cliente.

> El nombre del claim es configurable a propósito: el token lo emite `user-service` y hay
> que confirmar en qué claim viaja el id. Añade la propiedad al `application.yaml` y al
> `.env.example`.

## A2. Contexto de usuario

Un componente `UserContextProvider` en infraestructura que expone:

```java
UserContext current();   // record UserContext(UUID userId, String rawJwt)
```

El `rawJwt` se guarda porque la etapa 3 lo necesita para propagarlo a `pet-service`.
`ChatService` recibe el `UserContext` como parámetro; **no** accede al `SecurityContext`
directamente, para que siga siendo testeable sin Spring Security.

## A3. Rutas

| Ruta | Acceso |
|---|---|
| `/api/ai/**` | Autenticado |
| `/actuator/health` | Público |
| `/v3/api-docs/**`, `/swagger-ui/**` | Público (por ahora) |

CSRF desactivado para `/api/**`, sesión `STATELESS`. Elimina el comentario `// TEMPORAL`.

---

# Parte B — Persistencia de conversaciones

## B1. Entidades

Mapear **exactamente** las tablas de `db/schema.sql`. No inventes columnas ni cambies tipos.

`ConversationEntity` → `ai_conversation`
`MessageEntity` → `ai_message`

Notas de mapeo:

- `adjuntos`, `tool_args`, `tool_result` son `jsonb`. En Hibernate 6 se mapean con
  `@JdbcTypeCode(SqlTypes.JSON)`.
- Las columnas `tool_*` existen en la tabla pero **no se usan en esta etapa**: déjalas
  mapeadas y nulas, las llena la etapa 3.
- `created_at` y `updated_at` tienen default en la base y un trigger; no los sobreescribas
  desde Java.
- Las entidades JPA viven en `infrastructure.adapters.out.persistence` y **no salen de
  infraestructura**. El dominio usa `Conversation` y `Message` propios, y el adapter mapea.

## B2. Puerto de salida

`domain.ports.out.ConversationRepositoryPort`:

```java
Conversation create(UUID userId, String titulo);
Optional<Conversation> findByIdAndUser(UUID conversationId, UUID userId);
List<Conversation> findByUser(UUID userId, int limit, int offset);
Message append(UUID conversationId, Message message);
List<Message> findRecentMessages(UUID conversationId, int limit);
```

`findRecentMessages` devuelve los últimos N **en orden cronológico ascendente**, que es
como los espera Gemini.

## B3. Regla de propiedad

Una conversación que no pertenece al usuario autenticado devuelve **404**, no 403.
Un 403 confirmaría que ese id existe.

## B4. Transacciones — importante

**No mantengas una transacción abierta mientras se llama a Gemini.** Esa llamada tarda
segundos y el Transaction Pooler de Supabase no está para sostener conexiones ociosas.

El flujo va en tres pasos separados:

1. Transacción corta: crear la conversación si hace falta y guardar el mensaje del usuario.
2. **Fuera de transacción**: leer el historial y llamar a Gemini.
3. Transacción corta: guardar la respuesta del modelo.

## B5. Título de la conversación

Al crear una conversación nueva, el título son los primeros 60 caracteres del primer
mensaje del usuario, recortados en el último espacio. Si no hay texto (solo adjuntos),
usa `"Conversación del <fecha>"`.

**No hagas una llamada extra a Gemini para generar títulos.** Cuesta dinero y latencia.

---

# Parte C — Archivos adjuntos

## C1. Adapter de Storage

`domain.ports.out.FileStoragePort`:

```java
StoredFile upload(byte[] content, String mimeType, UUID userId);
byte[] download(String objectPath);
String signedUrl(String objectPath, Duration ttl);
```

`StoredFile` lleva `objectPath` y `mimeType`.

Implementación `SupabaseStorageAdapter` contra la API REST de Supabase Storage, con
`Authorization: Bearer ${SUPABASE_SERVICE_KEY}`:

- Subir: `POST {SUPABASE_URL}/storage/v1/object/{bucket}/{path}`
- Descargar: `GET {SUPABASE_URL}/storage/v1/object/{bucket}/{path}`
- URL firmada: `POST {SUPABASE_URL}/storage/v1/object/sign/{bucket}/{path}`
  con cuerpo `{"expiresIn": <segundos>}`

Ruta del objeto: `{userId}/{uuid}.{extensión}`. Agrupar por usuario simplifica borrar
todo lo de alguien después.

> El bucket es privado. **En la base se guarda el `objectPath`, nunca una URL completa.**
> Las URLs firmadas se generan al leer, porque expiran. Guardar una URL firmada en la
> base sería guardar un enlace que deja de funcionar en una hora.

## C2. Validación de archivos

Rechazar con **415** lo que no esté en la lista blanca:

| Tipo | MIME aceptados |
|---|---|
| Imagen | `image/png`, `image/jpeg`, `image/webp`, `image/heic`, `image/heif` |
| Audio | `audio/wav`, `audio/mp3`, `audio/mpeg`, `audio/aac`, `audio/ogg`, `audio/flac` |
| Documento | `application/pdf` |

- El MIME se determina por el contenido real, no por la extensión ni por el header que
  manda el cliente.
- Máximo 5 archivos por petición.
- Suma total de adjuntos por petición: máximo 15 MB (los límites de `multipart` del yml
  son el tope duro; este es el de negocio). Excederlo → **413**.

## C3. Envío a Gemini

Las partes binarias van como `inline_data`:

```json
{
  "inline_data": {
    "mime_type": "image/jpeg",
    "data": "<base64>"
  }
}
```

Extiende `AiPart` del dominio para que soporte, además de texto, una parte binaria con
mime type y bytes. **No rompas la firma de `AiModelPort`**: sigue siendo
`AiResponse generate(AiRequest request)`.

## C4. Adjuntos en el historial — decisión importante

Reenviar todas las imágenes de la conversación en cada turno multiplica el costo y la
latencia, y revienta el límite de tamaño de la petición.

Regla: **los binarios solo se incluyen para los turnos más recientes**, según
`gemini.max-history-attachments` (default 2). Los turnos más antiguos aportan solo su
texto, y donde había un adjunto se inserta un marcador textual:

```
[el usuario adjuntó una imagen]
```

Así el modelo sabe que hubo una foto y puede referirse a lo que ya dijo de ella, sin
pagar por reenviarla.

## C5. Ventana de historial

Se envían los últimos `gemini.max-history-messages` mensajes (default 20), sin cortar un
turno a la mitad. El resto no se manda.

---

# Parte D — Endpoints

## D1. `POST /api/ai/chat` (multipart/form-data)

Sustituye a `POST /api/ai/chat/text`, que se **elimina**. Mantener dos caminos de código
para lo mismo solo duplica bugs.

| Campo | Tipo | Obligatorio |
|---|---|---|
| `conversationId` | uuid | No — si falta, se crea una conversación |
| `message` | text | No, si hay al menos un archivo |
| `files` | file[] | No, si hay `message` |

Petición sin texto y sin archivos → **400**.

```json
{
  "conversationId": "…",
  "reply": "Parece un mestizo con rasgos de labrador…",
  "model": "gemini-3.8-flash"
}
```

## D2. `GET /api/ai/conversations`

Las conversaciones del usuario autenticado, más recientes primero.
Parámetros `limit` (default 20, máximo 50) y `offset`.

```json
[ { "id": "…", "titulo": "…", "updatedAt": "…" } ]
```

## D3. `GET /api/ai/conversations/{id}/messages`

Mensajes de una conversación, cronológicos. Mismos parámetros de paginación.
Los adjuntos se devuelven como **URLs firmadas con 1 hora de validez**, generadas al
momento de responder.

```json
[
  {
    "id": "…",
    "rol": "user",
    "contenido": "¿qué raza es?",
    "adjuntos": [ { "url": "https://…", "mimeType": "image/jpeg" } ],
    "createdAt": "…"
  }
]
```

Conversación de otro usuario o inexistente → **404**.

---

# Configuración nueva

Al `application.yaml` y al `.env.example`:

```yaml
security:
  jwt:
    secret: ${JWT_SECRET}
    user-id-claim: ${JWT_USER_ID_CLAIM:sub}

supabase:
  url: ${SUPABASE_URL}
  service-key: ${SUPABASE_SERVICE_KEY}
  bucket: ${SUPABASE_BUCKET:ai-attachments}
  signed-url-ttl-seconds: ${SUPABASE_SIGNED_URL_TTL:3600}

gemini:
  max-history-messages: ${GEMINI_MAX_HISTORY:20}
  max-history-attachments: ${GEMINI_MAX_HISTORY_ATTACHMENTS:2}

uploads:
  max-files-per-request: 5
  max-total-bytes: 15728640
```

**Nunca loguear `SUPABASE_SERVICE_KEY`**: se salta todas las políticas de la base.

---

# Tests

Sin dependencias nuevas y sin tocar la base real: el perfil `test` de la etapa 1 sigue
excluyendo datasource, Redis y Pub/Sub.

- `JwtAuthenticationFilterTest` — token válido, expirado, firma inválida, header ausente,
  claim configurable.
- `ChatServiceTest` — mocks de `AiModelPort`, `ConversationRepositoryPort` y
  `FileStoragePort`. Casos: conversación nueva vs existente; que el historial llegue en
  orden ascendente; que la ventana de mensajes se respete; que los adjuntos antiguos se
  sustituyan por el marcador textual; que Gemini **no** se llame dentro de una transacción.
- `SupabaseStorageAdapterTest` — `ExchangeFunction` simulada, como en la etapa 1.
  Subida correcta, error de Supabase, y que la service key no aparezca en ningún log.
- `AiChatControllerTest` — `@WebMvcTest`, `@MockitoBean`, `.with(csrf())`. Multipart con
  texto, con archivo, con ambos, sin nada (400), MIME no permitido (415), demasiados bytes
  (413), sin token (401), conversación ajena (404).
- Tests de repositorio contra Postgres real: **no** en esta etapa. Anota Testcontainers en
  `CLAUDE.md` como mejora futura.

---

# Definición de terminado

- [ ] `./mvnw clean test` pasa sin ninguna variable de entorno real
- [ ] Con un JWT válido emitido por `user-service`, `POST /api/ai/chat` con una foto de
      perro y el texto "¿qué raza es?" devuelve una sugerencia de raza en lenguaje probable
- [ ] Un segundo mensaje en la misma conversación, "¿y cuánto debería pesar?", se responde
      con contexto, sin repetir la foto
- [ ] `GET /api/ai/conversations` lista esa conversación con su título
- [ ] `GET /api/ai/conversations/{id}/messages` devuelve los cuatro mensajes y una URL
      firmada que abre la imagen en el navegador
- [ ] Sin header `Authorization` → 401
- [ ] Un `.txt` o un `.exe` → 415
- [ ] En Supabase: las filas están en `ai_conversation` y `ai_message`, y el objeto está
      en el bucket bajo `{userId}/`
- [ ] `CLAUDE.md` actualizado: etapa 2 marcada, decisiones nuevas, trampas encontradas
- [ ] Resumen de máximo 10 líneas: qué implementaste, qué decidiste fuera del brief, qué
      quedó pendiente

---

# Orden sugerido

A (JWT) → B (persistencia, solo texto) → C (adjuntos) → D (endpoints de lectura).

Después de A y B ya se puede probar una conversación con memoria usando solo texto. Deja
esa prueba funcionando antes de tocar archivos: si algo falla luego, sabrás que el
problema está en los adjuntos y no en el historial.
