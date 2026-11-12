package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.patient.Patient;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardSupportServiceDietTest {
    @Test
    @SuppressWarnings("unchecked")
    void selectsLatestPerPatientAndUsesLongestMatchingKeyword() {
        MongoTemplate center = mock(MongoTemplate.class);
        ObjectProvider<MongoTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(center);
        CardSupportService service = new CardSupportService(mock(MongoTemplate.class), provider);
        // 与数据库查询的 orderTime 倒序一致；后面的旧医嘱不可覆盖新医嘱。
        when(center.find(any(Query.class), eq(Document.class), eq("VI_ICU_ZYYZ"))).thenReturn(Arrays.asList(
                order("1", "半流质饮食", "执行中"), order("2", "禁食禁饮", "执行中"),
                order("3", "流质饮食", "执行中"), order("4", "普通饮食", "执行中"),
                order("5", "糖尿病饮食", "执行中"), order("6", "鼻饲流质", "执行中"),
                order("1", "普通饮食", "执行中"), order("2", "流质饮食", "执行中")));
        List<Patient> patients = Arrays.asList(patient("1"), patient("2"), patient("3"),
                patient("4"), patient("5"), patient("6"), patient("7"));
        ReflectionTestUtils.invokeMethod(service, "enrichDiet", patients);
        assertEquals(Arrays.asList("半流质", "禁食禁饮", "流质", "普通", "糖尿病", "鼻饲流质", null),
                patients.stream().map(Patient::getDiet).collect(java.util.stream.Collectors.toList()));
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(center).find(captor.capture(), eq(Document.class), eq("VI_ICU_ZYYZ"));
        Document query = captor.getValue().getQueryObject();
        assertTrue(query.containsKey("pid"));
        assertTrue(query.containsKey("orderTime"));
        Pattern pattern = (Pattern) query.get("orderName");
        assertTrue(pattern.matcher("半流质饮食").find());
        assertFalse(pattern.matcher("低盐饮食").find());
        assertEquals(new Document("orderTime", -1).append("_id", -1), captor.getValue().getSortObject());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stoppedLatestDoesNotFallBackToOlderOrder() {
        MongoTemplate center = mock(MongoTemplate.class);
        ObjectProvider<MongoTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(center);
        CardSupportService service = new CardSupportService(mock(MongoTemplate.class), provider);
        when(center.find(any(Query.class), eq(Document.class), eq("VI_ICU_ZYYZ"))).thenReturn(Arrays.asList(
                order("1", "停*普通饮食", "执行中"), order("2", "流质饮食", "已停止"),
                order("1", "普通饮食", "执行中"), order("2", "半流质饮食", "执行中")));
        Patient a = patient("1"), b = patient("2");
        ReflectionTestUtils.invokeMethod(service, "enrichDiet", Arrays.asList(a, b));
        assertNull(a.getDiet());
        assertNull(b.getDiet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearsPatientDietWhenCenterUnavailableEvenWithoutPatientId() {
        ObjectProvider<MongoTemplate> provider = mock(ObjectProvider.class);
        CardSupportService service = new CardSupportService(mock(MongoTemplate.class), provider);
        Patient p = patient("1");
        p.setDiet("旧数据");
        service.enrich(Collections.singletonList(p));
        assertNull(p.getDiet());
    }

    private static Patient patient(String pid) {
        Patient p = new Patient(); p.setHisPid(pid); return p;
    }

    private static Document order(String pid, String name, String status) {
        return new Document("pid", pid).append("orderName", name).append("status", status);
    }
}
