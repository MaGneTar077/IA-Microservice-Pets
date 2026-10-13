# Etapa 3.5 — Migración de autenticación a RS256 + JWKS

Lee `CLAUDE.md` antes de empezar. Rama: `feature/etapa-3-5-auth-rs256-jwks`.

Documento de referencia del ecosistema: `IA_SERVICE_AUTH_CONTEXT.md` (guardarlo en `docs/`).

## Por qué

`user-service` migró de HS256 con secreto compartido a **RS256 con JWKS público**. El
`JwtAuthenticationFilter` actual valida con `Keys.hmacShaKeyFor(JWT_SECRET.getBytes())`, así
que rechazará todos los tokens nuevos. Sin esta migración el servicio deja de funcionar por
completo cuando venzan los últimos tokens viejos (máximo 1 hora).

## Fuera de alcance

- El endpoint interno `/internal/verification/analyze` (es trabajo de MyAnimaLogVet, etapa
  posterior)
- Aplicar reglas de permisos por `vet_role` (el permiso real lo decide el servicio destino)
- Análisis de documentos (etapa 4)
- Despliegue

---

# Parte A — Validación con JWKS

## A1. Elección de implementación

Dos caminos. **Recomendado: `spring-boot-starter-oauth2-resource-server`.**

Trae validación RS256 contra un JWKS con caché de llaves, manejo de rotación por `kid` y
validación de `iss`/`aud`/`exp` ya resueltos, sin escribir esa lógica a mano. Es una
dependencia nueva, la primera del proyecto desde el `pom` inicial, pero reemplaza código que
de otro modo tendrías que escribir y mantener.

El camino alternativo —seguir con jjwt y resolver el JWKS a mano— implica descargar el
documento, cachearlo, refrescarlo cuando llegue un `kid` desconocido, y mapear JWK a
`PublicKey`. Es bastante más código propio para el mismo resultado.

**Si eliges el camino alternativo, dímelo antes de implementarlo.**

## A2. Qué se valida

| Comprobación | Valor |
|---|---|
| Algoritmo | RS256, con la llave del JWKS que corresponda al `kid` del header |
| `iss` | `myanimalog-user-service` |
| `aud` | debe contener `myanimalog-api` |
| `exp` | vigente |

JWKS: `https://user-microservice-pets-20369076707.us-central1.run.app/.well-known/jwks.json`

Cachear el JWKS 5–10 minutos y refrescar si llega un `kid` desconocido.

## A3. Tokens legacy

**No implementes `JWT_ACCEPT_LEGACY`.** Los tokens HS256 vencen en una hora como máximo;
sostener dos caminos de validación por eso no vale el código ni los tests. Elimina el uso de
`JWT_SECRET` por completo y quítalo de la configuración.

Si durante las pruebas te encuentras con un token viejo, se pide uno nuevo y ya.

## A4. `UserContext`

Hoy tiene `userId`, `rawJwt` y `username`. Agregar, leyéndolos del token:

| Campo | Claim | Notas |
|---|---|---|
| `email` | `email` | **Nunca** tomar el email de `sub`: `sub` ahora es el UUID |
| `platformRole` | `platform_role` | |
| `ctx` | `ctx` | `USER` o `VETERINARY` |
| `vetId` | `vet_id` | null en tokens de usuario |
| `vetRole` | `vet_role` | null en tokens de usuario |
| `vetLicensed` | `vet_licensed` | null en tokens de usuario |

El `userId` se sigue tomando del claim `id` (que ahora coincide con `sub`). Los campos de
clínica se guardan pero **no se usan todavía** — llegan con MyAnimaLogVet. Se capturan ahora
porque el filtro ya se está tocando.

`rawJwt` se mantiene: es lo que se propaga a los otros servicios.

## A5. Nunca en logs

Ni el token completo, ni truncado, ni ningún claim sensible. Verifica que el logging que
agregaste en etapas anteriores siga cumpliendo esto después del cambio.

---

# Parte B — Llamadas salientes por el gateway

## B1. Verificación previa — hazla antes de cambiar nada

El documento de autenticación indica llamar a los demás servicios **a través del API
gateway** (`/api/...`), que valida el token y agrega los headers `X-User-Id` y
`X-User-Email`.

Pero hoy apuntamos directo a cada servicio. **Antes de cambiar las URLs, confirma que el
gateway tenga rutas hacia `pet-service`, `medical-service` y `calendar-service`.** Si alguna
no existe, cambiarla rompe lo que ya funciona.

Reporta qué encontraste antes de tocar la configuración. Si el gateway solo enruta algunos,
movemos esos y dejamos los demás directos hasta que exista la ruta.

## B2. Configuración

Como las base URLs ya son variables de entorno por servicio, el cambio es de valores, no de
código:

```yaml
services:
  pet-service-url: ${PET_SERVICE_URL:http://localhost:8090}
  medical-service-url: ${MEDICAL_SERVICE_URL:http://localhost:8090}
  calendar-service-url: ${CALENDAR_SERVICE_URL:http://localhost:8090}
```

Lo que cambia además son las **rutas** dentro de cada adapter: por el gateway van con el
prefijo `/api/...`. Confírmalo contra las rutas reales del gateway, no lo asumas.

## B3. Propagación del token

Ya se hace y no cambia: cada llamada saliente lleva `Authorization: Bearer <rawJwt>`.

Esto importa más ahora: `pet-service` y `medical-service` todavía **no** validan el token y
confían en el `userId`/`ownerId` del body. Cuando se aseguren, empezarán a exigirlo. Como ya
lo reenviamos, no habrá que cambiar nada entonces.

Y mientras tanto, el `userId` del body se sigue llenando **desde el token**, nunca desde el
mensaje del usuario ni de lo que genere el modelo.

---

# Parte C — Manejo de errores de servicios destino

Ajustes sobre lo que ya existe.

| Status del destino | Comportamiento |
|---|---|
| **401** | Cortar el loop. Responder al frontend con 401 y cuerpo `{"error":"TOKEN_EXPIRED","message":"Tu sesión expiró, vuelve a intentarlo"}`. No reintentar con otra credencial. Si eran varios pasos, decir cuáles se completaron |
| **403** | No es error de infraestructura: devolverlo al modelo como `functionResponse` para que le explique al usuario que no tiene permiso. **No intentar otra vía ni reintentar** |
| **404** | Al modelo, para que pida aclaración |
| **400 / 409** | Al modelo, para que explique el problema y pida el dato correcto |
| **5xx** | Fallo temporal: mensaje al usuario, sin reintentar la misma acción (ya implementado en la etapa 3) |

El 401 es el único que cambia respecto a hoy: hace falta el código `TOKEN_EXPIRED` en el
cuerpo para que el frontend sepa que debe renovar con su refresh token.

**No guardes refresh tokens ni los pidas al frontend.** Renovar es responsabilidad de la app.

---

# Configuración

```yaml
security:
  jwt:
    jwks-uri: ${JWT_JWKS_URI}
    issuer: ${JWT_ISSUER:myanimalog-user-service}
    audience: ${JWT_AUDIENCE:myanimalog-api}
```

Eliminar `security.jwt.secret` y `security.jwt.user-id-claim` (el claim `id` queda fijo).

`.env.example`:

```bash
JWT_JWKS_URI=https://user-microservice-pets-20369076707.us-central1.run.app/.well-known/jwks.json
JWT_ISSUER=myanimalog-user-service
JWT_AUDIENCE=myanimalog-api
```

Quitar `JWT_SECRET` y `JWT_USER_ID_CLAIM`.

---

# Tests

Sin dependencias nuevas más allá de la de la parte A, y **sin llamar al JWKS real**: genera
un par de llaves RSA en el propio test y sirve un JWKS simulado.

- Token RS256 válido → autentica, `UserContext` con el UUID del claim `id`
- `iss` incorrecto → 401
- `aud` incorrecto → 401
- Token expirado → 401
- Firma inválida → 401
- `kid` desconocido → se refresca el JWKS; si sigue sin aparecer, 401
- **Token HS256 (legacy) → 401** (confirma que el camino viejo quedó cerrado)
- Token de clínica (`ctx: VETERINARY`) → autentica y los campos `vet_*` quedan poblados
- Token de usuario → los campos `vet_*` quedan nulos
- Que ningún log contenga el token

Y revisa los tests existentes: los que firman tokens HS256 de prueba (etapa 2 en adelante)
hay que migrarlos a RS256.

---

# Definición de terminado

- [ ] `./mvnw clean test` pasa sin variables de entorno reales ni acceso al JWKS real
- [ ] Con un token RS256 real de `user-service`, `POST /api/ai/chat` responde normal
- [ ] El `user_id` que queda en `ai_conversation` es el UUID correcto, no un email
- [ ] Los tools siguen funcionando: listar mascotas, y una acción con confirmación
- [ ] Un token sin `Authorization` → 401; uno manipulado → 401
- [ ] `JWT_SECRET` no aparece en ningún lado del proyecto
- [ ] Reporte de qué rutas tiene el gateway hacia pet, medical y calendar
- [ ] `CLAUDE.md` actualizado con el nuevo esquema de autenticación
- [ ] Resumen de máximo 10 líneas

---

# Orden sugerido

A (validación JWKS) → **parar** para que yo pruebe con un token real → C (errores) →
B (gateway, solo si las rutas existen).

La parada después de A es importante: si la validación falla contra el `user-service` real,
nada de lo demás importa.
