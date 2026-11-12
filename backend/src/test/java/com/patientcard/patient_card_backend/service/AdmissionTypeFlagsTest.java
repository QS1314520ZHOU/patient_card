package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.patient.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdmissionTypeFlagsTest {
    @Test
    void matchesAllProvidedAdmissionTypesBySubstring() {
        List<String> matches = Arrays.asList("入院", "入院，外院", "入院，手术", "入院，手术入院",
                "入院，转入", "外院", "外院，入院", "外院，转入", "手术入院", "手术入院，转入",
                "手术，入院", "手术，外院", "手术，手术入院", "转入，入院", "转入，外院", "，入院");
        List<String> nonMatches = Arrays.asList("手术", "手术，转入", "转入", "转入，手术", "转科", "，手术", "，转入", "", "   ");
        matches.forEach(s -> assertTrue(AdmissionTypeFlags.hospitalAdmission(s), s));
        nonMatches.forEach(s -> assertFalse(AdmissionTypeFlags.hospitalAdmission(s), s));
        assertFalse(AdmissionTypeFlags.hospitalAdmission(null));
    }

    @Test
    void patientDashboardKeepsSurgeryAndTransferRulesAndAddsHospitalFlag() {
        PatientRiskService service = new PatientRiskService(mock(MongoTemplate.class), mock(VteAssessmentService.class));
        Patient a = patient("手术，入院"), b = patient("外院，转入"), c = patient("手术"), d = patient(null);
        service.enrich(Arrays.asList(a, b, c, d));
        assertTrue(a.getHospitalAdmission()); assertFalse(a.getSurgeryAdmission()); assertFalse(a.getTransferIn());
        assertTrue(b.getHospitalAdmission()); assertFalse(b.getSurgeryAdmission()); assertTrue(b.getTransferIn());
        assertFalse(c.getHospitalAdmission()); assertTrue(c.getSurgeryAdmission()); assertFalse(c.getTransferIn());
        assertFalse(d.getHospitalAdmission()); assertFalse(d.getSurgeryAdmission()); assertFalse(d.getTransferIn());
    }
    private static Patient patient(String type) { Patient p = new Patient(); p.setAdmissionType(type); return p; }
}
