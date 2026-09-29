package com.myanimal.org.IA_service.domain.ports.out;

import java.util.Map;

import com.myanimal.org.IA_service.domain.model.VaccineRecord;

public interface MedicalServicePort {

    Map<String, Object> registerVaccine(VaccineRecord record, String rawJwt);
}
