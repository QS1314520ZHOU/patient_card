package com.patientcard.patient_card_backend.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.patientcard.patient_card_backend.model.patient.RiskAssessment;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PATIENT_CARD_LOCAL_DB_CHECK", matches = "true")
class LocalPressureReadOnlyTest {
    @Test
    void verifiesLatestBradenScoresAgainstLocalSmartCareWithoutWrites() {
        try (MongoClient client = MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=4000")) {
            MongoTemplate mongo = new MongoTemplate(client, "SmartCare");
            PatientRiskService service = new PatientRiskService(mongo, null);
            ReflectionTestUtils.setField(service, "scoreCollection", "score");
            ReflectionTestUtils.setField(service, "pressureScoreTypesRaw", "bradenScore,pressureDamageScore,bradenNurseScore");
            ReflectionTestUtils.setField(service, "pressureExtremeMax", 9);
            ReflectionTestUtils.setField(service, "pressureHighMax", 12);
            ReflectionTestUtils.setField(service, "pressureMediumMax", 14);
            List<Document> patients = mongo.getCollection("patient").find(new Document("status", "admitted"))
                    .projection(new Document("_id", 1)).into(new ArrayList<>());
            Set<String> ids = patients.stream().map(p -> p.getObjectId("_id").toHexString()).collect(Collectors.toSet());
            Map<String, Map<String, RiskAssessment>> scores = service.findLatestScores(ids);
            Map<String, Document> raw = new HashMap<>();
            mongo.getCollection("score").find(new Document("pid", new Document("$in", ids))
                    .append("scoreType", "bradenScore").append("valid", true))
                    .sort(new Document("time", -1)).forEach(d -> raw.putIfAbsent(d.getString("pid"), d));
            assertFalse(raw.isEmpty());
            int high = 0, boundary = 0;
            for (Map.Entry<String, Document> en : raw.entrySet()) {
                int total = ((Number) en.getValue().get("total")).intValue();
                RiskAssessment risk = service.pickPressure(scores.get(en.getKey()));
                assertNotNull(risk);
                assertEquals(String.valueOf(total), risk.getScore());
                assertEquals(total <= 12 ? "high" : total <= 14 ? "medium" : "low", risk.getLevel());
                if (total <= 12) high++;
                if (total == 12) boundary++;
            }
            System.out.println("Read-only local Braden verification: patients=" + ids.size()
                    + ", assessed=" + raw.size() + ", high=" + high + ", score12=" + boundary);
        }
    }
}
