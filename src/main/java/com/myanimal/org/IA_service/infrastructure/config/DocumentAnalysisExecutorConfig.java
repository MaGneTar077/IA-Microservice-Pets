package com.myanimal.org.IA_service.infrastructure.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hilo aparte para poder aplicar un tiempo máximo real a toda la petición (varios
 * documentos en paralelo, cada uno con descarga + Gemini + checks) con Future.get(timeout)
 * — no cancela las llamadas HTTP en curso, solo deja de esperarlas, que es lo que necesita
 * este endpoint síncrono.
 */
@Configuration
public class DocumentAnalysisExecutorConfig {

    // Con 7 tipos de documento posibles por solicitud, un pool acotado evita que una sola
    // petición de verificación se lleve todos los hilos del proceso, que comparte con el
    // chat. 4 es un número fijo en código (no hay variable de entorno para esto en el
    // checkpoint); se puede subir si en producción las 7 solicitudes en paralelo real no
    // alcanzan a terminar dentro del presupuesto de 50s.
    private static final int VERIFICATION_WORKER_POOL_SIZE = 4;

    @Bean(destroyMethod = "shutdown")
    public ExecutorService documentAnalysisExecutor() {
        return Executors.newCachedThreadPool();
    }

    @Bean(name = "verificationWorkerExecutor", destroyMethod = "shutdown")
    public ExecutorService verificationWorkerExecutor() {
        return Executors.newFixedThreadPool(VERIFICATION_WORKER_POOL_SIZE);
    }
}
