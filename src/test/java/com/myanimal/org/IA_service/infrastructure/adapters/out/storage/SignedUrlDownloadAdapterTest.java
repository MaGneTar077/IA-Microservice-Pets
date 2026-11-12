package com.myanimal.org.IA_service.infrastructure.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import com.myanimal.org.IA_service.domain.exception.AnalysisUnavailableException;
import com.myanimal.org.IA_service.domain.exception.FileTooLargeException;

import reactor.core.publisher.Mono;

class SignedUrlDownloadAdapterTest {

    private ExchangeStrategies strategiesWithLimit(int maxBytes) {
        return ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
    }

    private SignedUrlDownloadAdapter buildAdapter(ClientResponse response, ExchangeStrategies strategies) {
        WebClient webClient = WebClient.builder()
                .exchangeStrategies(strategies)
                .exchangeFunction(request -> Mono.just(response))
                .build();
        return new SignedUrlDownloadAdapter(webClient);
    }

    @Test
    void descargaElContenidoCuandoLaRespuestaEsExitosa() {
        ExchangeStrategies strategies = strategiesWithLimit(1024 * 1024);
        ClientResponse response = ClientResponse.create(HttpStatus.OK, strategies).body("contenido").build();
        SignedUrlDownloadAdapter adapter = buildAdapter(response, strategies);

        byte[] result = adapter.download("https://storage.test/signed-url");

        assertThat(new String(result)).isEqualTo("contenido");
    }

    @Test
    void cuerpoMasGrandeQueElLimiteLanzaFileTooLargeException() {
        ExchangeStrategies strategies = strategiesWithLimit(1024);
        String bigBody = "x".repeat(2048);
        ClientResponse response = ClientResponse.create(HttpStatus.OK, strategies).body(bigBody).build();
        SignedUrlDownloadAdapter adapter = buildAdapter(response, strategies);

        assertThatThrownBy(() -> adapter.download("https://storage.test/signed-url"))
                .isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void errorDeLaUrlFirmadaLanzaAnalysisUnavailableException() {
        ExchangeStrategies strategies = strategiesWithLimit(1024 * 1024);
        ClientResponse response = ClientResponse.create(HttpStatus.FORBIDDEN, strategies).build();
        SignedUrlDownloadAdapter adapter = buildAdapter(response, strategies);

        assertThatThrownBy(() -> adapter.download("https://storage.test/signed-url"))
                .isInstanceOf(AnalysisUnavailableException.class);
    }
}
