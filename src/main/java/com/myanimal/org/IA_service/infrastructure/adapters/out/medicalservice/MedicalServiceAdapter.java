package com.myanimal.org.IA_service.infrastructure.adapters.out.medicalservice;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.myanimal.org.IA_service.domain.exception.ToolExecutionException;
import com.myanimal.org.IA_service.domain.exception.UpstreamSessionExpiredException;
import com.myanimal.org.IA_service.domain.model.VaccineRecord;
import com.myanimal.org.IA_service.domain.ports.out.MedicalServicePort;
import com.myanimal.org.IA_service.infrastructure.adapters.out.medicalservice.dto.VaccineRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MedicalServiceAdapter implements MedicalServicePort {

    private final WebClient medicalServiceWebClient;

    public MedicalServiceAdapter(@Qualifier("medicalServiceWebClient") WebClient medicalServiceWebClient) {
        this.medicalServiceWebClient = medicalServiceWebClient;
    }

    @Override
    public Map<String, Object> registerVaccine(VaccineRecord record, String rawJwt) {
        VaccineRequest request = VaccineRequest.builder()
                .petId(record.petId())
                .name(record.name())
                .lotNumber(record.lotNumber())
                .applicationDate(record.applicationDate())
                .nextDoseDate(record.nextDoseDate())
                .veterinarian(record.veterinarian())
                .notes(record.notes())
                .build();
        try {
            return medicalServiceWebClient.post()
                    .uri("/api/vaccines")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawJwt)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                throw new UpstreamSessionExpiredException("La sesión expiró al llamar a medical-service.", ex);
            }
            log.warn("medical-service respondió {} al registrar una vacuna", ex.getStatusCode().value());
            throw new ToolExecutionException("No se pudo registrar la vacuna. Revisa los datos e intenta de nuevo.");
        }
    }
}
