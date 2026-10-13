# Etapa 4 — Análisis de documentos veterinarios

Lee `CLAUDE.md` antes de empezar. Rama: `feature/etapa-4-analisis-documentos`.

## Objetivo

El usuario sube el PDF o la foto del documento que le dio la veterinaria (examen,
tratamiento o cirugía) y recibe los datos ya estructurados, listos para revisar y
confirmar en un formulario.

Completa la funcionalidad 5 del proyecto.

## Decisión de diseño — leer primero

**Este endpoint no escribe nada.** No usa function calling ni llama a `medical-service`.

Son datos médicos: un modelo que alucina una dosis o una fecha y escribe directo a la base
es un riesgo real, y además una pregunta difícil en la sustentación. El flujo es:

```
1. El móvil sube el documento a POST /api/ai/documents/analyze
2. El ai-service extrae los datos con structured output de Gemini
3. Devuelve JSON estructurado + nivel de confianza por sección
4. La app pinta un formulario prellenado
5. El usuario revisa, corrige y confirma
6. El MÓVIL hace el POST a medical-service
```

El paso 6 no es nuestro. Nosotros extraemos, el humano valida.

## Fuera de alcance

- Escribir en `medical-service`
- Pub/Sub y sugerencias de cuidado (etapa 5)
- Rate limiting con Redis
- Despliegue

---

# Parte A — Structured output en el adapter

## A1. Extensión de `AiModelPort`

El puerto actual es `AiResponse generate(AiRequest request)`. Esta etapa necesita pedirle
al modelo que responda con un JSON que cumpla un esquema.

Agrega a `AiRequest` dos campos opcionales:

- `responseMimeType` (ej. `"application/json"`)
- `responseSchema` (`Map<String, Object>`)

Cuando vengan presentes, el `GeminiAdapter` los manda dentro de `generationConfig`:

```json
{
  "contents": [ … ],
  "generationConfig": {
    "responseMimeType": "application/json",
    "responseSchema": { … }
  }
}
```

**No rompas la firma de `AiModelPort`.** Los reintentos, el fallback de modelo y el manejo
de timeouts de la etapa 1 siguen aplicando igual.

> Verificar al implementar: si el modelo no acepta `responseSchema` junto con `tools` en la
> misma petición, no importa — aquí no se mandan tools. Pero si falla por otro motivo,
> documéntalo en `CLAUDE.md`.

## A2. Respuesta

Con `responseMimeType: application/json`, el texto del candidato es JSON. Puede venir
envuelto en un bloque de código markdown según el modelo: límpialo antes de parsear.

Si el JSON no parsea o no cumple el esquema, **no lances una excepción al usuario sin más**:
reintenta una vez con el mismo prompt (los modelos fallan el formato de forma intermitente)
y solo si vuelve a fallar, responde con el error.

---

# Parte B — El esquema de extracción

## B1. Tipos de documento

```java
public enum DocumentType { EXAM, TREATMENT, SURGERY, UNKNOWN }
```

El modelo determina el tipo a partir del contenido. `UNKNOWN` cuando el documento no es
veterinario o no se puede clasificar.

## B2. Esquema

El JSON schema vive en `src/main/resources/schemas/medical-document.json`, no embebido en
Java, para poder iterarlo sin recompilar. Estructura:

```json
{
  "type": "object",
  "properties": {
    "documentType": { "type": "string", "enum": ["EXAM","TREATMENT","SURGERY","UNKNOWN"] },
    "confianzaGeneral": { "type": "string", "enum": ["ALTA","MEDIA","BAJA"] },
    "fecha": { "type": "string", "description": "yyyy-MM-dd, o null si no aparece" },
    "veterinario": { "type": "string" },
    "clinica": { "type": "string" },
    "mascotaNombre": { "type": "string", "description": "el nombre que aparece en el documento" },
    "diagnostico": { "type": "string" },
    "observaciones": { "type": "string" },

    "examen": {
      "type": "object",
      "properties": {
        "tipoExamen": { "type": "string" },
        "resultados": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "parametro": { "type": "string" },
              "valor": { "type": "string" },
              "unidad": { "type": "string" },
              "rangoReferencia": { "type": "string" },
              "fueraDeRango": { "type": "boolean" }
            }
          }
        }
      }
    },

    "tratamiento": {
      "type": "object",
      "properties": {
        "medicamentos": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "nombre": { "type": "string" },
              "dosis": { "type": "string" },
              "frecuencia": { "type": "string" },
              "duracion": { "type": "string" },
              "via": { "type": "string" }
            }
          }
        },
        "indicaciones": { "type": "string" }
      }
    },

    "cirugia": {
      "type": "object",
      "properties": {
        "procedimiento": { "type": "string" },
        "anestesia": { "type": "string" },
        "complicaciones": { "type": "string" },
        "cuidadosPostoperatorios": { "type": "string" },
        "proximoControl": { "type": "string" }
      }
    },

    "camposIlegibles": {
      "type": "array",
      "items": { "type": "string" },
      "description": "nombres de campos que aparecían en el documento pero no se pudieron leer"
    },

    "textoOriginal": { "type": "string", "description": "el texto crudo extraído del documento" }
  },
  "required": ["documentType", "confianzaGeneral"]
}
```

Solo se rellena la sección que corresponda al `documentType`; las otras van nulas.

## B3. Reglas para el modelo

En `src/main/resources/prompts/document-extraction-prompt.txt`:

```
Eres un extractor de datos de documentos veterinarios. Tu única tarea es
transcribir lo que el documento dice, con precisión literal.

Reglas:
- NUNCA inventes un valor. Si un campo no aparece en el documento, déjalo null.
- NUNCA infieras dosis, frecuencias o diagnósticos que no estén escritos.
- Si un campo aparece pero no se lee con claridad, déjalo null y agrega su
  nombre a camposIlegibles.
- Transcribe las dosis exactamente como aparecen, incluidas unidades.
- Si el documento no es veterinario, devuelve documentType UNKNOWN y deja
  todo lo demás null.
- confianzaGeneral: ALTA si el documento es legible y completo; MEDIA si
  faltan datos o hay partes borrosas; BAJA si apenas se puede leer.
- textoOriginal debe contener todo el texto que logres leer, sin resumir.
```

> `camposIlegibles` y `textoOriginal` existen para que la app pueda resaltar lo dudoso y el
> usuario compare contra el original. Sin eso, el formulario prellenado es una caja negra.

---

# Parte C — El endpoint

## C1. `POST /api/ai/documents/analyze`

`multipart/form-data`, autenticado con JWT.

| Campo | Tipo | Obligatorio |
|---|---|---|
| `file` | file | sí |
| `petId` | uuid | no |

Si viene `petId`, **verificar propiedad** contra `pet-service`, igual que en la etapa 3.
Mascota ajena → 404.

Tipos aceptados: `application/pdf` y las imágenes de la lista blanca que ya existe desde
la etapa 2. Un solo archivo. Reutiliza `MagicByteMimeDetector` y los límites de tamaño.

Respuesta:

```json
{
  "documentType": "TREATMENT",
  "confianzaGeneral": "ALTA",
  "fecha": "2026-10-05",
  "veterinario": "Dra. Gómez",
  "clinica": "Veterinaria Cat-Dog",
  "mascotaNombre": "Thor",
  "diagnostico": "Dermatitis alérgica",
  "tratamiento": {
    "medicamentos": [
      { "nombre": "Apoquel", "dosis": "16 mg", "frecuencia": "cada 12 horas", "duracion": "14 días", "via": "oral" }
    ],
    "indicaciones": "Administrar con comida"
  },
  "camposIlegibles": [],
  "documentoUrl": "https://… (URL firmada)",
  "analysisId": "uuid"
}
```

## C2. Persistencia

El documento se sube a Supabase Storage igual que los adjuntos de la etapa 2, y el análisis
se guarda para que el usuario pueda volver a verlo sin reanalizar (cada análisis cuesta
tokens).

Tabla nueva — **el SQL va en `db/schema.sql` y lo ejecuto yo en Supabase, no lo crea el
código** (`ddl-auto: none`):

```sql
create table if not exists public.ai_document_analysis (
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid        not null,
    pet_id         uuid,
    object_path    text        not null,
    document_type  text        not null,
    confianza      text        not null,
    extraccion     jsonb       not null,
    created_at     timestamptz not null default now()
);

create index if not exists idx_ai_document_analysis_user
    on public.ai_document_analysis (user_id, created_at desc);

alter table public.ai_document_analysis enable row level security;
```

## C3. `GET /api/ai/documents/{analysisId}`

Devuelve un análisis guardado, con la URL firmada regenerada al momento. De otro usuario o
inexistente → 404.

## C4. Relación con el chat

Esta etapa **no toca el chat**. Es un endpoint aparte, con su propio flujo en la app.

Si al implementarlo ves que conviene dejar rastro del análisis en la conversación, dímelo
antes de hacerlo; no lo implementes por tu cuenta.

---

# Parte D — Errores

| Caso | HTTP |
|---|---|
| Sin archivo | 400 |
| MIME no permitido | 415 |
| Archivo muy grande | 413 |
| `petId` ajeno o inexistente | 404 |
| El JSON del modelo no parsea tras el reintento | 502 |
| Gemini no disponible | 503 |

`documentType: UNKNOWN` **no es un error**: se devuelve 200 con el resultado, y la app le
dice al usuario que no parece un documento veterinario.

---

# Configuración

```yaml
documents:
  max-file-size-bytes: ${DOCUMENTS_MAX_BYTES:15728640}
  schema-path: classpath:schemas/medical-document.json
  prompt-path: classpath:prompts/document-extraction-prompt.txt
```

Actualizar `.env.example`.

---

# Tests

Sin dependencias nuevas, `ExchangeFunction` simulada como en las etapas anteriores.

- `GeminiAdapterTest`: que `responseMimeType` y `responseSchema` lleguen dentro de
  `generationConfig` en el payload saliente; que un JSON envuelto en markdown se limpie
  bien; que un JSON inválido reintente una vez y luego falle.
- `DocumentAnalysisServiceTest`: extracción de examen, de tratamiento y de cirugía;
  `UNKNOWN` devuelve 200; `petId` ajeno no llega a llamar a Gemini.
- `DocumentsControllerTest`: `@WebMvcTest`, multipart con PDF, con imagen, sin archivo
  (400), MIME no permitido (415), sin token (401), análisis ajeno (404).
- Un test que verifique que **nunca** se llama a `MedicalServicePort` desde este flujo.

---

# Definición de terminado

- [ ] `./mvnw clean test` pasa sin variables de entorno reales
- [ ] Un PDF de examen de sangre devuelve los parámetros con sus valores y unidades
- [ ] Una foto de una receta devuelve los medicamentos con dosis y frecuencia
- [ ] Un documento con partes borrosas devuelve `confianzaGeneral: MEDIA` y lista los
      campos ilegibles en vez de inventarlos
- [ ] Una foto cualquiera (un paisaje) devuelve `UNKNOWN` con 200
- [ ] El documento queda en Supabase Storage bajo `{userId}/` y la fila en
      `ai_document_analysis`
- [ ] `GET /api/ai/documents/{analysisId}` devuelve el análisis con URL firmada que abre
- [ ] En ningún caso se llama a `medical-service`
- [ ] `CLAUDE.md` actualizado
- [ ] Resumen de máximo 10 líneas

---

# Orden sugerido

A (structured output) → B (esquema y prompt) → C (endpoint y persistencia).

**Detente después de A y B**, con un endpoint mínimo que reciba un PDF y devuelva el JSON
sin persistir nada, para que yo pruebe la calidad de la extracción con documentos reales.
Si el modelo no extrae bien, el resto del trabajo cambia.
