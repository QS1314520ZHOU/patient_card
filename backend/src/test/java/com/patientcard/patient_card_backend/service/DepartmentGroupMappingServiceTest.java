package com.patientcard.patient_card_backend.service;

import org.junit.jupiter.api.Test;
import org.bson.Document;
import java.util.*;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DepartmentGroupMappingServiceTest {
    @Test void displayMatchesExactTrimmedSourceOnly() {
        Map<String,String> a = Map.of("陈晓东", "一组");
        assertEquals("一组", DepartmentGroupMappingService.displayName(" 陈晓东 ", a));
        assertEquals("陈晓东组", DepartmentGroupMappingService.displayName("陈晓东组", a));
        assertEquals("陈晓东", DepartmentGroupMappingService.displayName("陈晓东", Map.of()));
        assertNull(DepartmentGroupMappingService.displayName(null, a));
    }
    @Test void validationRejectsDuplicatesAndReservedGroup() {
        assertThrows(IllegalArgumentException.class, () -> DepartmentGroupMappingService.validate(List.of(
            Map.of("source", "A", "display", "一组"), Map.of("source", " A ", "display", "二组"))));
        assertThrows(IllegalArgumentException.class, () -> DepartmentGroupMappingService.validate(List.of(Map.of("source", "A", "display", "未分组"))));
        assertThrows(IllegalArgumentException.class, () -> DepartmentGroupMappingService.validate(List.of(Map.of("source", "A", "display", " "))));
    }
    @Test void validationAcceptsManyToOneAndPunctuation() {
        List<Document> rows = DepartmentGroupMappingService.validate(List.of(
                Map.of("source", "A.1", "display", "一组"), Map.of("source", "B", "display", "一组")));
        assertEquals(2, rows.size()); assertEquals("A.1", rows.get(0).getString("source"));
    }
    @Test void departmentsAreIndependentAndOptimisticUpdatesAreScoped() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        DepartmentGroupMappingService service = new DepartmentGroupMappingService(mongo, "admin-key");
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("patientCardGroupMappings"))).thenAnswer(inv -> {
            Query q = inv.getArgument(0);
            String id = q.getQueryObject().getString("_id");
            String label = "dept:A".equals(id) ? "一组" : "二组";
            return new Document("revision", 1L).append("mappings", List.of(new Document("source", "陈晓东").append("display", label)));
        });
        assertEquals("一组", service.mapping("A").get("陈晓东"));
        assertEquals("二组", service.mapping("B").get("陈晓东"));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq("patientCardGroupMappings")))
            .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        assertThrows(ConcurrentModificationException.class, () -> service.save("A", 1, List.of()));
        org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).updateFirst(captor.capture(), any(Update.class), eq("patientCardGroupMappings"));
        assertEquals("dept:A", captor.getValue().getQueryObject().getString("_id"));
        assertEquals(1L, captor.getValue().getQueryObject().get("revision"));
    }
    @Test void editingRequiresConfiguredCorrectKey() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        assertFalse(new DepartmentGroupMappingService(mongo, "").checkKey(""));
        DepartmentGroupMappingService service = new DepartmentGroupMappingService(mongo, "strong-key");
        assertFalse(service.checkKey(null)); assertFalse(service.checkKey("wrong")); assertTrue(service.checkKey("strong-key"));
    }
}
