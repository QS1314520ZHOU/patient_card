package com.patientcard.patient_card_backend.handover;
import com.patientcard.patient_card_backend.model.patient.Patient;
import com.patientcard.patient_card_backend.service.PatientRiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandoverDataTest {
 @SuppressWarnings("unchecked") @Test void queryRestrictsToDepartmentAndPatientAndRejectsEncounterChange() {
  MongoTemplate mongo=mock(MongoTemplate.class);BlbcjlClient client=mock(BlbcjlClient.class);HandoverConfig cfg=new HandoverConfig();cfg.setEnabled(true);
  ObjectProvider<MongoTemplate> provider=mock(ObjectProvider.class);PatientRiskService risk=mock(PatientRiskService.class);when(risk.cardScoreTypes()).thenReturn(List.of());
  HandoverData d=new HandoverData(mongo,client,cfg,provider,risk);ReflectionTestUtils.setField(d,"patients","patient");ReflectionTestUtils.setField(d,"bedside","bedside");ReflectionTestUtils.setField(d,"scoreCollection","score");ReflectionTestUtils.setField(d,"defaultBqdm","W1");
  Patient one=new Patient();one.setId("p1");one.setMrn("synthetic-1");one.setAdmissionTime(Date.from(Instant.now().minusSeconds(86400*5)));one.setIcuAdmissionTime(Date.from(Instant.now().minusSeconds(86400*4)));
  Patient changed=new Patient();changed.setId("p1");changed.setMrn("synthetic-2");changed.setAdmissionTime(one.getAdmissionTime());changed.setIcuAdmissionTime(one.getIcuAdmissionTime());
  when(mongo.findOne(any(Query.class),eq(Patient.class),eq("patient"))).thenReturn(one,changed);
  when(client.fetch("synthetic-1","W1")).thenReturn("{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8));
  assertThrows(IllegalStateException.class,()->d.load("A","p1"));
  ArgumentCaptor<Query> q=ArgumentCaptor.forClass(Query.class);verify(mongo,atLeastOnce()).findOne(q.capture(),eq(Patient.class),eq("patient"));
  assertEquals("p1",q.getValue().getQueryObject().get("_id"));assertEquals("A",q.getValue().getQueryObject().get("deptCode"));assertEquals("admitted",q.getValue().getQueryObject().get("status"));
 }
 @SuppressWarnings("unchecked") @Test void featureIsDisabledByDefault(){HandoverData d=new HandoverData(mock(MongoTemplate.class),mock(BlbcjlClient.class),new HandoverConfig(),mock(ObjectProvider.class),mock(PatientRiskService.class));assertThrows(IllegalStateException.class,()->d.load("A","p1"));}
 @SuppressWarnings("unchecked") @Test void usesPerDepartmentBqdmAndReportsUnknownNoteTime(){
  MongoTemplate mongo=mock(MongoTemplate.class);BlbcjlClient client=mock(BlbcjlClient.class);HandoverConfig cfg=new HandoverConfig();cfg.setEnabled(true);cfg.setBqdmByDept(Map.of("A","WARD-A","B","WARD-B"));
  ObjectProvider<MongoTemplate> provider=mock(ObjectProvider.class);PatientRiskService risk=mock(PatientRiskService.class);HandoverData d=new HandoverData(mongo,client,cfg,provider,risk);
  ReflectionTestUtils.setField(d,"patients","patient");ReflectionTestUtils.setField(d,"defaultBqdm","");
  Patient p=new Patient();p.setId("p1");p.setMrn("synthetic-1");p.setAdmissionTime(Date.from(Instant.now().minusSeconds(86400*5)));
  when(mongo.findOne(any(Query.class),eq(Patient.class),eq("patient"))).thenReturn(p);
  when(client.fetch("synthetic-1","WARD-A")).thenReturn("{\"code\":0,\"data\":[{\"BLH\":\"synthetic-1\",\"BQDM\":\"WARD-A\",\"WBNR\":\"无可识别开头日期\"}]}".getBytes(StandardCharsets.UTF_8));
  HandoverData.Snapshot result=d.load("A","p1");verify(client).fetch("synthetic-1","WARD-A");assertEquals(0,result.sources.size());assertTrue(result.warnings.stream().anyMatch(x->x.contains("1 份病程无法识别")));
 }

 @SuppressWarnings("unchecked") @Test void defaultRequestUsesMrnAndEmptyBqdm() {
  MongoTemplate mongo=mock(MongoTemplate.class);BlbcjlClient client=mock(BlbcjlClient.class);HandoverConfig cfg=new HandoverConfig();cfg.setEnabled(true);
  ObjectProvider<MongoTemplate> provider=mock(ObjectProvider.class);PatientRiskService risk=mock(PatientRiskService.class);HandoverData d=new HandoverData(mongo,client,cfg,provider,risk);
  ReflectionTestUtils.setField(d,"patients","patient");ReflectionTestUtils.setField(d,"defaultBqdm","");
  Patient p=new Patient();p.setId("synthetic-p1");p.setMrn("test-mrn");p.setAdmissionTime(Date.from(Instant.now().minusSeconds(86400*5)));
  when(mongo.findOne(any(Query.class),eq(Patient.class),eq("patient"))).thenReturn(p);
  when(client.fetch("test-mrn","")).thenReturn("{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8));
  assertNotNull(d.load("ANY_DEPT","synthetic-p1"));verify(client).fetch("test-mrn","");
 }

 @SuppressWarnings("unchecked") @Test void displaysHumanReadableScoreWithoutBackendCode(){
  MongoTemplate mongo=mock(MongoTemplate.class);BlbcjlClient client=mock(BlbcjlClient.class);HandoverConfig cfg=new HandoverConfig();cfg.setEnabled(true);
  ObjectProvider<MongoTemplate> provider=mock(ObjectProvider.class);PatientRiskService risk=mock(PatientRiskService.class);when(risk.cardScoreTypes()).thenReturn(List.of("bradenScore"));when(risk.getPressureTypes()).thenReturn(List.of("bradenScore"));
  HandoverData d=new HandoverData(mongo,client,cfg,provider,risk);ReflectionTestUtils.setField(d,"patients","patient");ReflectionTestUtils.setField(d,"bedside","bedside");ReflectionTestUtils.setField(d,"scoreCollection","score");ReflectionTestUtils.setField(d,"defaultBqdm","");
  Patient p=new Patient();p.setId("p1");p.setMrn("test-mrn");p.setAdmissionTime(Date.from(Instant.now().minusSeconds(86400*5)));p.setIcuAdmissionTime(Date.from(Instant.now().minusSeconds(86400*4)));
  when(mongo.findOne(any(Query.class),eq(Patient.class),eq("patient"))).thenReturn(p);
  when(client.fetch("test-mrn","")).thenReturn("{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8));
  when(mongo.findOne(any(Query.class),eq(org.bson.Document.class),eq("score"))).thenReturn(new org.bson.Document("time",Date.from(Instant.now().minusSeconds(3600))).append("total",9));
  HandoverData.Source score=d.load("A","p1").sources.stream().filter(x->x.kind.equals("风险评分")).findFirst().orElseThrow();
  assertEquals("压力性损伤风险",score.title);assertTrue(score.text.contains("Braden 评分"));assertFalse(score.text.contains("bradenScore"));
 }

}
