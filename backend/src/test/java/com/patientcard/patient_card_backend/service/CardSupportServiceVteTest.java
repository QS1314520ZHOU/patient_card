package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.patient.Patient;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardSupportServiceVteTest {
    @Test
    @SuppressWarnings("unchecked")
    void matchesBothIdentifiersAndDoesNotUseOlderNonemptyValue() {
        MongoTemplate center = mock(MongoTemplate.class);
        ObjectProvider<MongoTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(center);
        CardSupportService service = new CardSupportService(mock(MongoTemplate.class), provider);
        ReflectionTestUtils.setField(service, "zybrCollection", "VI_ICU_ZYBR");
        ReflectionTestUtils.setField(service, "zybrPidField", "pid");
        ReflectionTestUtils.setField(service, "zybrMrnField", "mrn");
        ReflectionTestUtils.setField(service, "zybrVteField", "VTEMC");
        // 假设这里的顺序是 Mongo _id 倒序；相同 pid 的不同住院号不能串值。
        when(center.find(any(Query.class), eq(Document.class), eq("VI_ICU_ZYBR"))).thenReturn(Arrays.asList(
                new Document("pid", 123L).append("mrn", "M1").append("VTEMC", "新危急值"),
                new Document("pid", "123").append("mrn", "M2").append("VTEMC", "  "),
                new Document("pid", "123").append("mrn", "M2").append("VTEMC", "旧危急值"),
                new Document("pid", "456").append("mrn", "M1").append("VTEMC", "其他患者")));
        Patient a = patient("123", "M1"), b = patient("123", "M2"), c = patient("123", "M3"), d = patient("123", null);
        ReflectionTestUtils.invokeMethod(service, "enrichVteCritical", Arrays.asList(a, b, c, d));
        assertEquals("新危急值", a.getVteCritical());
        assertNull(b.getVteCritical());
        assertNull(c.getVteCritical());
        assertNull(d.getVteCritical());
        org.mockito.ArgumentCaptor<Query> captor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(center).find(captor.capture(), eq(Document.class), eq("VI_ICU_ZYBR"));
        assertTrue(captor.getValue().getQueryObject().containsKey("pid"));
        assertTrue(captor.getValue().getQueryObject().containsKey("mrn"));
        assertFalse(captor.getValue().getQueryObject().containsKey("VTEMC"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingIdentifiersDoNotQuery() {
        MongoTemplate center = mock(MongoTemplate.class);
        ObjectProvider<MongoTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(center);
        CardSupportService service = new CardSupportService(mock(MongoTemplate.class), provider);
        ReflectionTestUtils.invokeMethod(service, "enrichVteCritical", Collections.singletonList(patient("123", " ")));
        verifyNoInteractions(center);
    }

    private Patient patient(String pid, String mrn) {
        Patient p = new Patient(); p.setHisPid(pid); p.setMrn(mrn); return p;
    }
}
