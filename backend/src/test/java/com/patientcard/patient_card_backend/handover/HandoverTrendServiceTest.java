package com.patientcard.patient_card_backend.handover;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HandoverTrendServiceTest {
 @SuppressWarnings("unchecked") @Test void readsRawValuesAndEventsWithEncounterQueries(){
  MongoTemplate mongo=mock(MongoTemplate.class),center=mock(MongoTemplate.class);ObjectProvider<MongoTemplate> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(center);
  HandoverTrendService service=new HandoverTrendService(mongo,provider,mock(HandoverData.class),mock(HandoverAi.class));Instant from=Instant.parse("2026-11-11T08:00:00Z"),to=Instant.parse("2026-11-12T08:00:00Z");Date time=Date.from(from.plusSeconds(3600));
  when(mongo.find(any(Query.class),eq(Document.class),eq("bedside"))).thenReturn(List.of(new Document("code","param_in_day").append("time",time).append("strVal","120"),new Document("code","param_in_day").append("time",time).append("strVal","150"),new Document("code","param_HR").append("time",time).append("strVal","<60")));
  when(center.find(any(Query.class),eq(Document.class),eq("VI_ICU_EXAM_ITEM"))).thenReturn(List.of(new Document("itemCode","LAC").append("result","2.3").append("unit","mmol/L").append("authTime",time).append("resultStatus","审核"),new Document("itemCode","LAC1").append("result","1.0").append("unit","mg/dL").append("authTime",time)));
  when(mongo.find(any(Query.class),eq(Document.class),eq("drugExe"))).thenReturn(List.of(new Document("startTime",time).append("drugList",List.of(new Document("name","模拟药"))).append("drugActionList",List.of(new Document("time",time).append("action","pause")))));
  HandoverTrendService.Chart chart=new HandoverTrendService.Chart();service.readBedside(chart,"patient-1",from,to);service.readLabs(chart,"encounter-1",from,to);service.readDrugs(chart,"patient-1",from,from,to);
  var in=chart.tracks.stream().filter(t->t.label.equals("当日累计入量")).findFirst().orElseThrow();assertEquals("mL",in.unit);assertEquals(List.of(120.0,150.0),in.points.stream().map(p->p.value).toList());assertFalse(chart.tracks.stream().anyMatch(t->t.label.equals("心率")));
  assertEquals(2,chart.tracks.stream().filter(t->t.label.equals("乳酸")).count());assertEquals(2,chart.medications.size());assertTrue(chart.medications.stream().anyMatch(e->e.action.equals("暂停")));
  ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);verify(center).find(query.capture(),eq(Document.class),eq("VI_ICU_EXAM_ITEM"));assertEquals("encounter-1",query.getValue().getQueryObject().getString("hisPid"));assertTrue(query.getValue().getQueryObject().containsKey("authTime"));
 }
 @Test void rejectsNonExactNumericResults(){assertNull(HandoverTrendService.number("<3"));assertNull(HandoverTrendService.number("NaN"));assertEquals(-1.5,HandoverTrendService.number("-1.5"));}
 @Test void aiUnavailableIsExplicitlyDefault(){HandoverAi ai=new HandoverAi(new com.fasterxml.jackson.databind.ObjectMapper());HandoverTrendService.Chart chart=new HandoverTrendService.Chart();chart.tracks.add(HandoverTrendService.track("test","心率","次/分","体征"));ai.chooseTrends(chart,new HandoverData.Snapshot(),"测试","");assertFalse(chart.aiSelected);assertEquals(List.of("test"),chart.selected);assertTrue(chart.warnings.stream().anyMatch(w->w.contains("默认")));}
 @Test void aiCanOnlySelectExistingRawTracks() throws Exception {
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  class TestAi extends HandoverAi {String payload;TestAi(){super(mapper);} @Override com.fasterxml.jackson.databind.JsonNode post(String path,Object request){try{return mapper.readTree(payload);}catch(Exception e){throw new RuntimeException(e);}}}
  TestAi ai=new TestAi();org.springframework.test.util.ReflectionTestUtils.setField(ai,"base","http://localhost");org.springframework.test.util.ReflectionTestUtils.setField(ai,"model","test");
  HandoverTrendService.Chart c=new HandoverTrendService.Chart();c.tracks.add(HandoverTrendService.track("hr","心率","次/分","体征"));c.tracks.add(HandoverTrendService.track("lab:1","乳酸","mmol/L","检验"));
  ai.payload="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"ids\\\":[\\\"lab:1\\\"]}\"}}]}";ai.chooseTrends(c,new HandoverData.Snapshot(),"测试诊断","");assertTrue(c.aiSelected);assertEquals(List.of("lab:1"),c.selected);
  HandoverTrendService.Chart bad=new HandoverTrendService.Chart();bad.tracks.addAll(c.tracks);ai.payload="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"ids\\\":[\\\"invented\\\"]}\"}}]}";ai.chooseTrends(bad,new HandoverData.Snapshot(),"","");assertFalse(bad.aiSelected);assertFalse(bad.selected.contains("invented"));
 }
}
