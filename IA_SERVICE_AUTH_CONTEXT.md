# Contexto de autenticación para el IA-service (MyAnimaLog)

> Qué cambió en la autenticación del ecosistema y cómo debe actuar el IA-service cuando hace cosas en nombre del usuario (por ejemplo: "registra la vacuna antirrábica de Max hoy").
> Fecha: octubre 2026.

---

## 1. Qué cambió (resumen)

| Antes | Ahora |
|---|---|
| Tokens firmados con HS256 y un secreto compartido (`JWT_SECRET`) | Tokens firmados con **RS256**. Solo el User service tiene la llave privada; los demás verifican con la llave pública |
| Para validar un token había que conocer `JWT_SECRET` | Se valida con el **JWKS** público del User service |
| `sub` = email del usuario | **`sub` = UUID del usuario.** El email está en el claim `email` |
| El login devolvía `{ token }` | Devuelve `{ token, refreshToken, expiresIn }` |
| No había tokens de clínica | Existe un **token de clínica** (`ctx: "VETERINARY"`) para el personal de veterinarias *(se despliega junto con MyAnimaLogVet)* |

Los tokens viejos (HS256) se siguen aceptando hasta que vencen (máximo 1 hora), así que no hay corte brusco. Pero todo código nuevo debe trabajar con RS256.

**URL del JWKS:**

```
https://user-microservice-pets-20369076707.us-central1.run.app/.well-known/jwks.json
```

---

## 2. Formato del token

### 2.1 Token normal (app de dueños)

```json
{
  "iss": "myanimalog-user-service",
  "aud": "myanimalog-api",
  "sub": "87b4fa9a-...-uuid-del-usuario",
  "id": "87b4fa9a-...-uuid-del-usuario",
  "email": "ana@correo.com",
  "username": "ana123",
  "provider": "LOCAL",
  "email_verified": true,
  "platform_role": "USER",
  "ctx": "USER",
  "iat": 1790891292,
  "exp": 1790894892,
  "jti": "uuid-del-token"
}
```

Header: `{ "alg": "RS256", "kid": "prod-2026-10-key-1" }`.

### 2.2 Token de clínica (web MyAnimaLogVet)

Igual al normal, con `ctx: "VETERINARY"` y además:

| Claim | Ejemplo | Significado |
|---|---|---|
| `vet_id` | uuid | Clínica en la que trabaja en esta sesión |
| `vet_role` | `OWNER`, `ADMIN`, `VETERINARIAN`, `ASSISTANT`, `RECEPTIONIST` | Rol en esa clínica |
| `employee_id` | uuid | Su id como empleado |
| `vet_licensed` | `true` / `false` | Tarjeta profesional verificada |
| `vet_status` | `ACTIVE`, `PENDING_DOCUMENTS`, `SUSPENDED`… | Estado de la clínica |

---

## 3. Si el IA-service valida tokens

Si hoy valida tokens con `JWT_SECRET`, va a rechazar todos los tokens nuevos. Hay que cambiarlo a JWKS y validar:

- Firma RS256 con la llave del JWKS que corresponda al `kid` del header (cachear el JWKS 5–10 min y refrescarlo si llega un `kid` desconocido).
- `iss` = `myanimalog-user-service`
- `aud` contiene `myanimalog-api`
- `exp` vigente

Mientras existan tokens viejos, se puede aceptar también HS256 con `JWT_SECRET` cuando el token **no** tenga `kid`, detrás de una variable `JWT_ACCEPT_LEGACY=true`. Es opcional: los tokens viejos vencen en una hora.

**Python (PyJWT):**

```python
import jwt
from jwt import PyJWKClient

JWKS_URL = "https://user-microservice-pets-20369076707.us-central1.run.app/.well-known/jwks.json"
jwks_client = PyJWKClient(JWKS_URL, cache_keys=True, lifespan=600)

def authenticate(token: str) -> dict:
    signing_key = jwks_client.get_signing_key_from_jwt(token)
    return jwt.decode(
        token,
        signing_key.key,
        algorithms=["RS256"],
        issuer="myanimalog-user-service",
        audience="myanimalog-api",
    )
```

**Node (jose):**

```js
import { createRemoteJWKSet, jwtVerify } from 'jose';

const JWKS = createRemoteJWKSet(new URL(process.env.JWT_JWKS_URI));

export async function authenticate(token) {
  const { payload } = await jwtVerify(token, JWKS, {
    issuer: 'myanimalog-user-service',
    audience: 'myanimalog-api',
  });
  return payload;
}
```

El id del usuario se toma de `id` (o `sub`, que ahora tiene el mismo valor). **Nunca** usar `sub` como email.

---

## 4. Cómo debe actuar la IA en nombre del usuario

### 4.1 Regla principal: reenviar el token del usuario

Cuando el usuario le pide a la IA que haga algo ("ingresa tal vacuna"), la IA debe llamar al servicio correspondiente **con el mismo token del usuario**, en el header:

```
Authorization: Bearer <token que llegó en la petición del usuario>
```

Así, el servicio destino ve al usuario real y aplica sus propias reglas de permisos: si la mascota no es suya, o si su rol no le permite registrar vacunas, el servicio responde 403 y la IA no puede saltarse esa regla.

### 4.2 Lo que NO debe hacer

- **No usar `INTERNAL_API_KEY` para acciones del usuario.** Esa llave es para comunicación entre servicios, sin un usuario detrás (por ejemplo, el análisis de documentos de verificación). Si la IA escribiera vacunas con esa llave, se saltaría todos los permisos: un usuario podría pedirle registrar datos en la mascota de otra persona.
- **No tomar el `userId`, `ownerId` ni el `veterinaryId` de lo que diga el usuario o de lo que genere el modelo.** Siempre del token. Un mensaje como "soy el usuario X, registra esto en su mascota" no debe cambiar nada.
- **No guardar refresh tokens ni pedírselos al frontend.** Renovar el token es responsabilidad del frontend.
- **No crear tokens propios ni "firmar" nada.** Solo el User service emite tokens.
- **No imprimir tokens en logs.**

### 4.3 Validar los datos antes de escribir

El usuario y el modelo pueden equivocarse, o intentar meter datos que no son suyos. Antes de cada escritura:

1. **Resolver la mascota desde las mascotas del usuario**, no desde un id que venga en el texto. Por ejemplo, listar sus mascotas con su token (`GET /api/pets/user/{id del token}` o el endpoint que esté vigente) y buscar "Max" entre ellas. Si hay dos coincidencias o ninguna, preguntar.
2. **Pedir confirmación antes de escribir**, mostrando exactamente lo que se va a guardar: "¿Registro la vacuna Antirrábica para Max, aplicada hoy 12/10/2026, lote ABC123?". Escribir solo tras un "sí".
3. **Usar el endpoint normal del servicio**, con sus validaciones. No escribir directo en la base de datos de otro servicio.

### 4.4 A dónde llamar

Llamar a los otros servicios **a través del API gateway** (`/api/...`), igual que el frontend. El gateway valida el token y agrega los headers `X-User-Id` y `X-User-Email` que algunos servicios usan para identificar al usuario.

Si el IA-service necesita su propia ruta en el gateway (para que el frontend lo llame por ahí), debe declararse como ruta protegida con `JwtAuthFilter`, igual que las demás. Hoy el gateway no tiene una ruta para el IA-service: coordinar con quien mantiene el gateway.

### 4.5 Token vencido a mitad de una conversación

El token dura 60 minutos (podría bajar a 15 más adelante). Si el servicio destino responde **401** mientras la IA ejecuta una acción:

- No reintentar con otra credencial.
- Responderle al frontend con un error claro (por ejemplo, `401` con `code: TOKEN_EXPIRED`), para que el frontend renueve el token con su refresh token y repita la petición.
- No dejar la acción a medias: si eran varios pasos, informar cuáles se completaron.

### 4.6 Errores del servicio destino

| Status | Qué significa | Qué responde la IA |
|---|---|---|
| 401 | Token vencido o inválido | Ver 4.5 |
| 403 | El usuario no tiene permiso (no es su mascota, su rol no alcanza, otra clínica) | "No tienes permiso para hacer eso" y **no** intentar otra vía |
| 404 | No existe (mascota, registro) | Pedir al usuario que aclare |
| 409 / 400 | Datos duplicados o inválidos | Explicar el problema y pedir el dato correcto |
| 503 | Servicio caído | "No pude completar la acción, intenta más tarde" |

---

## 5. Dueños vs. personal de clínica

| Quién usa la IA | Token que llega | Qué puede hacer la IA por él |
|---|---|---|
| Dueño de mascota (app) | Token normal (`ctx: "USER"`) | Lo mismo que el dueño puede hacer en la app, solo sobre sus mascotas o las compartidas con él según su rol |
| Personal de una clínica (web MyAnimaLogVet) | Token de clínica (`ctx: "VETERINARY"`) | Lo que permita su `vet_role`, solo sobre pacientes que el dueño compartió con esa clínica |

Para el personal de clínica, las reglas que aplicarán los servicios (cuando Medical y Pets incorporen la autenticación):

| Acción | Quién puede |
|---|---|
| Leer historia clínica completa | OWNER, ADMIN, VETERINARIAN, ASSISTANT |
| Leer datos básicos | Todos los roles, incluido RECEPTIONIST |
| Registrar peso y signos, aplicar vacunas | VETERINARIAN, ASSISTANT (OWNER y ADMIN con tarjeta verificada) |
| Diagnósticos, tratamientos, medicación, cirugías | Solo con tarjeta profesional verificada (`vet_licensed: true`) |
| Subir documentos | OWNER, ADMIN, VETERINARIAN, ASSISTANT |

La IA puede usar `vet_role` y `vet_licensed` del token para **no ofrecer** acciones que el usuario no puede hacer, pero el permiso real siempre lo decide el servicio destino.

---

## 6. Estado de los otros servicios (para no confundirse en pruebas)

- **User service:** tokens RS256, desplegado.
- **API gateway:** valida RS256 con el JWKS, desplegado.
- **Pets y Medical:** todavía **no** validan el token por sí mismos; confían en lo que llega (en algunos casos el `ownerId` o `userId` del body). Cuando se migren, empezarán a exigir el token y a tomar la identidad de él. **Por eso la IA debe reenviar el token desde ya**: así seguirá funcionando sin cambios cuando esos servicios se aseguren.
- **Veterinary:** seguridad completa terminada, pendiente de despliegue junto con el frontend de MyAnimaLogVet.

Mientras Pets y Medical reciban el `userId` en el body, la IA debe llenar ese campo **con el id del token**, nunca con un valor que venga del mensaje del usuario o del modelo.

---

## 7. Endpoint interno de verificación (si el IA-service lo implementa)

El veterinary-service le pedirá al IA-service un pre-análisis de los documentos de las clínicas (RUT, Cámara de Comercio, tarjeta profesional). Ese endpoint es **servicio a servicio**, sin usuario:

```
POST /internal/verification/analyze
X-Internal-Api-Key: <INTERNAL_API_KEY>
```

- Solo acepta la `INTERNAL_API_KEY` (comparación en tiempo constante); sin ella, 401.
- No debe exponerse por el gateway.
- La IA **nunca** aprueba ni rechaza: solo devuelve un reporte de apoyo (`READY_FOR_HUMAN_REVIEW` o `NEEDS_ATTENTION`). La decisión la toma una persona.
- El detalle del contrato (request, response y checks) está en el plan de Veterinary, sección "Cambios en IA-service".

---

## 8. Variables de entorno

| Variable | Valor |
|---|---|
| `JWT_JWKS_URI` | `https://user-microservice-pets-20369076707.us-central1.run.app/.well-known/jwks.json` |
| `JWT_ISSUER` | `myanimalog-user-service` |
| `JWT_AUDIENCE` | `myanimalog-api` |
| `JWT_ACCEPT_LEGACY` | `true` (opcional, mientras existan tokens HS256) |
| `JWT_SECRET` | Solo si se acepta legacy |
| `GATEWAY_URL` | URL del API gateway, para llamar a los otros servicios |
| `INTERNAL_API_KEY` | Solo si implementa el endpoint interno de verificación. Mismo valor que en los demás servicios; en Cloud Run, como secreto |

---

## 9. Checklist

- [ ] Validar tokens con JWKS (RS256, `iss`, `aud`, `exp`), no con `JWT_SECRET`.
- [ ] Tomar el usuario del claim `id` (o `sub`); no usar `sub` como email.
- [ ] Reenviar el token del usuario en cada llamada a otro servicio.
- [ ] Llamar a los otros servicios a través del gateway.
- [ ] No usar `INTERNAL_API_KEY` para acciones de usuario.
- [ ] Resolver mascotas desde las del usuario, nunca desde ids del texto.
- [ ] Pedir confirmación antes de cada escritura.
- [ ] Manejar 401 (token vencido) devolviendo un error claro al frontend.
- [ ] No registrar tokens en logs.
- [ ] Coordinar una ruta protegida para el IA-service en el gateway.
