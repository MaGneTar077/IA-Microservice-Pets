package com.myanimal.org.IA_service.domain.exception;

/**
 * Un servicio destino (pet-service, medical-service, calendar-service) respondió 401 a
 * mitad del loop de function calling: el JWT del usuario expiró mientras conversábamos
 * con Gemini (los tokens duran 1 hora). No es un error de negocio — nunca se traduce a un
 * functionResponse, porque el modelo lo leería como "el recurso no existe". Corta el loop.
 */
public class UpstreamSessionExpiredException extends RuntimeException {

    public UpstreamSessionExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
