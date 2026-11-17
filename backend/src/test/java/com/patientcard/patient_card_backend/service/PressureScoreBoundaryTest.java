package com.patientcard.patient_card_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.patientcard.patient_card_backend.model.patient.RiskAssessment;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class PressureScoreBoundaryTest {
    @Test
    void matchesLocalBradenConfigurationIncludingTwelve() {
        PatientRiskService service = new PatientRiskService(null, null);
        ReflectionTestUtils.setField(service, "pressureExtremeMax", 9);
        ReflectionTestUtils.setField(service, "pressureHighMax", 12);
        ReflectionTestUtils.setField(service, "pressureMediumMax", 14);
        ReflectionTestUtils.setField(service, "pressureScoreTypesRaw", "bradenScore,pressureDamageScore");
        for (int total : new int[]{6, 9, 10, 11, 12, 13, 14, 15, 18, 19}) {
            RiskAssessment risk = service.assessmentFor("bradenScore", total, new Date());
            assertEquals(total <= 12 ? "high" : total <= 14 ? "medium" : "low", risk.getLevel());
            if (total <= 9) assertEquals("极度危险", risk.getResult());
        }
        assertEquals("high", service.assessmentFor("bradenScore", null, new Date(), "极度危险").getLevel());
        assertEquals("极度风险", service.assessmentFor("bradenScore", 15, new Date(), "极度风险").getResult());
    }
}
