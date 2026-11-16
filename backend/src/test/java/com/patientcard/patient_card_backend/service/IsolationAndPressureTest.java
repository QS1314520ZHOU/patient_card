package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.overview.IsolationTag;
import com.patientcard.patient_card_backend.model.patient.RiskAssessment;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IsolationAndPressureTest {
    @Test
    void preservesIsolationKeywordsAcrossBothServices() {
        CardSupportService support = new CardSupportService(null, null);
        CardDetailService detail = new CardDetailService(null, null);
        for (String label : Arrays.asList("严密隔离", "接触隔离", "接触性隔离", "保护性隔离",
                "保护性接触隔离", "预防性接触隔离", "床旁接触隔离", "床旁隔离",
                "呼吸道隔离", "消化道隔离", "肠道隔离", "空气隔离", "飞沫隔离")) {
            List<Document> orders = Collections.singletonList(new Document("orderName", label + "护理 1 qd 乙肝 {医嘱执行自动记账}"));
            assertEquals(label, ReflectionTestUtils.invokeMethod(support, "joinActiveIsolationLabels", orders));
            List<IsolationTag> tags = ReflectionTestUtils.invokeMethod(detail, "resolveIsolationTags", orders);
            assertEquals(1, tags.size());
            assertEquals(label, tags.get(0).getLabel());
        }
    }

    @Test
    void releaseAndStopOverrideOlderActiveOrders() {
        for (String stop : Arrays.asList("停*严密隔离", "解除严密隔离")) {
            List<Document> orders = Arrays.asList(new Document("orderName", stop), new Document("orderName", "严密隔离护理"));
            assertNull(ReflectionTestUtils.invokeMethod(new CardSupportService(null, null), "joinActiveIsolationLabels", orders));
            assertNull(ReflectionTestUtils.invokeMethod(new CardDetailService(null, null), "resolveIsolationTags", orders));
        }
    }

    @Test
    void pressureConclusionPreservesExtremeRiskEvenWithoutScore() {
        PatientRiskService service = new PatientRiskService(null, null);
        for (String conclusion : Arrays.asList("极度风险", "极高危", "高风险", "高危")) {
            for (Integer score : Arrays.asList(null, 8, 15)) {
                RiskAssessment risk = service.assessmentFor(PatientRiskService.PRESSURE, score, new Date(), conclusion);
                assertEquals("high", risk.getLevel());
                assertEquals(conclusion, risk.getResult());
            }
        }
        assertEquals("medium", service.assessmentFor(PatientRiskService.PRESSURE, 8, new Date(), "中度风险").getLevel());
        assertEquals("low", service.assessmentFor(PatientRiskService.PRESSURE, 8, new Date(), "低度风险").getLevel());
    }
}
