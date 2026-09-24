package com.myanimal.org.IA_service.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.myanimal.org.IA_service.domain.exception.AttachmentStorageException;
import com.myanimal.org.IA_service.domain.model.StoredFile;
import com.myanimal.org.IA_service.infrastructure.config.SupabaseProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

class SupabaseStorageAdapterTest {

    private static final String SIGN_RESPONSE_BODY = "{\"signedURL\":\"/object/sign/ai-attachments/u/f.jpg?token=abc\"}";

    private SupabaseProperties properties() {
        SupabaseProperties properties = new SupabaseProperties();
        properties.setUrl("http://supabase.test");
        properties.setServiceKey("super-secreta-service-key");
        properties.setBucket("ai-attachments");
        return properties;
    }

    private SupabaseStorageAdapter buildAdapter(Queue<ClientResponse> responses) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://supabase.test")
                .exchangeFunction(request -> Mono.just(responses.poll()))
                .build();
        return new SupabaseStorageAdapter(webClient, properties());
    }

    private ClientResponse ok(String body) {
        ClientResponse.Builder builder = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json");
        if (body != null) {
            builder.body(body);
        }
        return builder.build();
    }

    private ClientResponse status(HttpStatus status) {
        return ClientResponse.create(status).build();
    }

    @Test
    void subidaCorrectaDevuelveElObjectPathYElMimeType() {
        Queue<ClientResponse> responses = new ConcurrentLinkedQueue<>(List.of(ok(null)));
        SupabaseStorageAdapter adapter = buildAdapter(responses);
        UUID userId = UUID.randomUUID();

        StoredFile stored = adapter.upload(new byte[] { 1, 2, 3 }, "image/png", userId);

        assertThat(stored.objectPath()).startsWith(userId + "/");
        assertThat(stored.objectPath()).endsWith(".png");
        assertThat(stored.mimeType()).isEqualTo("image/png");
    }

    @Test
    void errorDeSupabaseAlSubirLanzaAttachmentStorageExceptionYNuncaLoguéaLaServiceKey() {
        Logger logger = (Logger) LoggerFactory.getLogger(SupabaseStorageAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        Queue<ClientResponse> responses = new ConcurrentLinkedQueue<>(List.of(status(HttpStatus.FORBIDDEN)));
        SupabaseStorageAdapter adapter = buildAdapter(responses);

        try {
            assertThatThrownBy(() -> adapter.upload(new byte[] { 1 }, "image/png", UUID.randomUUID()))
                    .isInstanceOf(AttachmentStorageException.class);

            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("403"));
            assertThat(appender.list).noneMatch(
                    event -> event.getFormattedMessage().contains("super-secreta-service-key"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void descargaDevuelveLosBytes() {
        Queue<ClientResponse> responses = new ConcurrentLinkedQueue<>(List.of(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/octet-stream")
                        .body("contenido")
                        .build()));
        SupabaseStorageAdapter adapter = buildAdapter(responses);

        byte[] content = adapter.download("user/archivo.png");

        assertThat(new String(content)).isEqualTo("contenido");
    }

    @Test
    void urlFirmadaAnteponeLaBaseUrlAlPathRelativoDeSupabase() {
        Queue<ClientResponse> responses = new ConcurrentLinkedQueue<>(List.of(ok(SIGN_RESPONSE_BODY)));
        SupabaseStorageAdapter adapter = buildAdapter(responses);

        String url = adapter.signedUrl("u/f.jpg", Duration.ofHours(1));

        assertThat(url).isEqualTo("http://supabase.test/object/sign/ai-attachments/u/f.jpg?token=abc");
    }
}
