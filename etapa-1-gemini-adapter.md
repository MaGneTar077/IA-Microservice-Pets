# Etapa 1 — Conexión con Gemini y endpoint de texto

Lee `CLAUDE.md` antes de empezar. Este brief define **qué** construir y **qué contratos**
respetar; el cuerpo de los métodos lo decides tú.

## Objetivo

Un endpoint que recibe un texto, lo manda a Gemini y devuelve la respuesta. Nada más.
Es la base sobre la que se montan multimodal (etapa 2) y tool calling (etapa 3), así que
los contratos importan más que la cantidad de funcionalidad.

## Fuera de alcance (no implementar)

- Persistencia, entidades JPA, repositorios
- Imágenes, audio, PDFs
- Tool calling / function calling
- Validación de JWT (etapa siguiente)
- Redis, rate limiting, Pub/Sub

Si algo de esto parece necesario para terminar, detente y avísame en vez de implementarlo.

## Paquete base

Usa el paquete base que ya existe en el proyecto (el de la clase `@SpringBootApplication`).
Respeta la estructura hexagonal de `CLAUDE.md`.

---

## 1. Modelo de dominio — `domain.model`

Sin ninguna dependencia de Gemini, Jackson ni Spring. Son las clases que el resto del
sistema entiende; el JSON de Gemini vive solo en infraestructura.

- `AiRole` — enum: `USER`, `MODEL`
- `AiPart` — por ahora solo texto (`String text`). Diseñado para que en la etapa 2 se le
  agreguen partes binarias (mime type + datos) sin romper nada.
- `AiMessage` — `AiRole role`, `List<AiPart> parts`
- `AiRequest` — `String systemInstruction`, `List<AiMessage> messages`
- `AiResponse` — `String text`, `String finishReason`, `String modelUsed`, `AiUsage usage`
- `AiUsage` — `int promptTokens`, `int outputTokens`, `int thoughtsTokens`, `int totalTokens`

Usa `@Builder(toBuilder = true)` según la convención. Records son aceptables si prefieres.

## 2. Puertos

**Salida — `domain.ports.out.AiModelPort`**

```java
AiResponse generate(AiRequest request);
```

Un único método. No expone nombres de modelo, URLs ni nada de Gemini: es el contrato que
permitiría cambiar a Vertex AI escribiendo otro adapter.

**Entrada — `domain.ports.in.ChatUseCase`**

```java
ChatResult chat(String userMessage);
```

`ChatResult` (en `application.dto`): `String reply`, `String model`.

## 3. Servicio — `application.service.ChatService`

Implementa `ChatUseCase`. Arma el `AiRequest` con la instrucción de sistema y un único
mensaje `USER`, llama a `AiModelPort` y mapea a `ChatResult`.

La instrucción de sistema **no va como String en Java**: se carga al arrancar desde
`src/main/resources/prompts/system-prompt.txt` (créalo con el contenido del anexo).
Así se puede iterar el prompt sin recompilar lógica.

## 4. Configuración — `infrastructure.config`

`GeminiProperties` con `@ConfigurationProperties(prefix = "gemini")`:

| Campo | Propiedad |
|---|---|
| `apiKey` | `gemini.api-key` |
| `model` | `gemini.model` |
| `modelFallback` | `gemini.model-fallback` |
| `baseUrl` | `gemini.base-url` |
| `timeoutSeconds` | `gemini.timeout-seconds` |
| `maxRetries` | `gemini.max-retries` (default 3) |

Agrega al `application.yml` lo que falte:

```yaml
gemini:
  model-fallback: ${GEMINI_MODEL_FALLBACK:gemini-3.6-flash}
  max-retries: ${GEMINI_MAX_RETRIES:3}
```

Un `WebClient` dedicado para Gemini, configurado con el `baseUrl` y el timeout.

## 5. Adapter — `infrastructure.adapters.out.GeminiAdapter`

Implementa `AiModelPort`. Los DTOs del JSON de Gemini (request y response) van en un
subpaquete propio del adapter y **no salen de infraestructura**.

### Petición

```
POST {baseUrl}/models/{model}:generateContent
Header: x-goog-api-key: {apiKey}
Content-Type: application/json
```

La key va en el **header**, no como `?key=` en la URL: las URLs terminan en logs.

```json
{
  "systemInstruction": { "parts": [ { "text": "..." } ] },
  "contents": [
    { "role": "user", "parts": [ { "text": "..." } ] }
  ]
}
```

Roles de Gemini: `"user"` y `"model"` (minúsculas). Mapear desde `AiRole`.

### Respuesta

```json
{
  "candidates": [
    {
      "content": { "role": "model", "parts": [ { "text": "..." } ] },
      "finishReason": "STOP"
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 5,
    "candidatesTokenCount": 1,
    "thoughtsTokenCount": 100,
    "totalTokenCount": 106
  },
  "modelVersion": "gemini-3.8-flash"
}
```

- Concatenar el `text` de todas las `parts` del primer candidato, **ignorando** cualquier
  part que tenga `"thought": true`.
- Los campos de `usageMetadata` pueden faltar: tratarlos como 0.
- Si no hay candidatos, o el `finishReason` es `SAFETY` / `PROHIBITED_CONTENT` o similar,
  lanzar la excepción de contenido bloqueado (ver sección 6).
- Configurar Jackson para ignorar propiedades desconocidas: la API agrega campos seguido.

### Reintentos y fallback — obligatorio

Probado en la práctica: el modelo principal devuelve 503 en picos de demanda.

1. Llamar con `model`.
2. Si responde **503 o 429**: reintentar con backoff exponencial (1s, 2s, 4s), hasta
   `maxRetries` intentos.
3. Si se agotan los reintentos: **un** intento con `modelFallback`.
4. Si también falla: lanzar `AiModelUnavailableException`.
5. **400, 401, 403 y 404 no se reintentan nunca**: lanzar `AiModelException` de inmediato.
   No se arreglan solos.

`AiResponse.modelUsed` debe reflejar el modelo que efectivamente respondió.

### Logging

- Por cada llamada: modelo usado, latencia en ms, tokens (incluidos `thoughtsTokens`),
  número de intento.
- Cuando se active el fallback: un `WARN` explícito.
- **Nunca** loguear la API key, ni completa ni truncada. Tampoco el body completo de la
  petición en nivel INFO (llevará datos del usuario en etapas siguientes).

## 6. Errores

Excepciones en `domain` (o `application`), mapeadas por un `@RestControllerAdvice`:

| Excepción | HTTP | Cuándo |
|---|---|---|
| `AiModelUnavailableException` | 503 | Reintentos y fallback agotados |
| `AiModelException` | 502 | 4xx de Gemini, respuesta malformada |
| `AiContentBlockedException` | 422 | Respuesta bloqueada por filtros de seguridad |

Cuerpo de error: `{ "error": "<código>", "message": "<mensaje para el usuario>" }`.
El mensaje al cliente es genérico y en español; el detalle técnico va solo al log.

## 7. Controller — `infrastructure.adapters.in.AiChatController`

```
POST /api/ai/chat/text
Content-Type: application/json

{ "message": "¿Cada cuánto debo desparasitar a mi perro?" }
```

```json
{ "reply": "...", "model": "gemini-3.8-flash" }
```

- `message` obligatorio, no vacío, máximo 4000 caracteres (`@Valid`).
- El controller solo delega en `ChatUseCase`. Sin lógica.

## 8. Seguridad — provisional

Como la validación de JWT es la etapa siguiente, crea un `SecurityConfig` mínimo que
permita `/api/ai/**` y `/actuator/health` sin autenticación y deshabilite CSRF para `/api/**`.

Márcalo con un comentario `// TEMPORAL — reemplazar por filtro JWT antes de desplegar`.
**Este estado no se despliega a Cloud Run.**

## 9. Tests

- `ChatServiceTest` — `@ExtendWith(MockitoExtension.class)`, mock de `AiModelPort`.
  Verifica que se incluye la instrucción de sistema y el mensaje del usuario.
- `GeminiAdapterTest` — **sin dependencias nuevas**. Construye el `WebClient` con una
  `ExchangeFunction` que devuelve respuestas simuladas. Casos mínimos:
  - respuesta 200 correcta → texto y tokens bien mapeados
  - parts con `thought: true` → se ignoran
  - 503, 503, 200 → éxito tras reintentos
  - 503 hasta agotar → responde el modelo de fallback, `modelUsed` es el fallback
  - 403 → excepción inmediata, **una sola** llamada realizada
  - `finishReason: SAFETY` → `AiContentBlockedException`
  - Para que no tarden: el backoff debe poder configurarse en 0 en los tests.
- `AiChatControllerTest` — `@WebMvcTest`, `@MockitoBean` del use case, `.with(csrf())`.
  Mensaje válido → 200; mensaje vacío → 400.

## Definición de terminado

- [ ] `./mvnw clean test` pasa completo
- [ ] La app arranca y el log muestra el modelo configurado (sin la key)
- [ ] Esta petición devuelve una respuesta real de Gemini:
  ```powershell
  Invoke-RestMethod -Method Post -ContentType "application/json" `
    -Uri "http://localhost:8083/api/ai/chat/text" `
    -Body '{"message":"¿Cada cuánto debo desparasitar a mi perro?"}'
  ```
- [ ] Poniendo temporalmente un modelo inexistente en `GEMINI_MODEL`, la petición devuelve
      502 inmediato (404 de Gemini no se reintenta)
- [ ] `CLAUDE.md` actualizado: etapa 1 marcada, modelos confirmados, y cualquier trampa
      nueva que hayas encontrado
- [ ] Al terminar, resume en máximo 10 líneas: qué implementaste, qué decidiste que no
      estaba en este brief, y qué quedó pendiente

---

## Anexo — `prompts/system-prompt.txt` (versión inicial)

```
Eres el asistente de MyAnimaLog, una aplicación para dueños de mascotas.

Respondes en español, de forma cálida, clara y breve.

Puedes orientar sobre cuidado general, alimentación, comportamiento, higiene y
prevención. No eres veterinario: no diagnosticas enfermedades ni recetas
medicamentos o dosis. Ante síntomas preocupantes, recomienda acudir a un
veterinario, y si describen una emergencia (dificultad para respirar,
convulsiones, envenenamiento, sangrado abundante), di que vayan de inmediato.

Cuando te pregunten por la raza de un animal, habla en términos de
probabilidad ("parece un mestizo con rasgos de labrador"), nunca como una
afirmación categórica.

Si no sabes algo, dilo.
```
