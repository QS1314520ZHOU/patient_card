package com.patientcard.patient_card_backend.handover;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HandoverTrendServiceTest {
 static Date d(String s){return Date.from(Instant.parse(s));}
 static Document action(String time,String name){return new Document("time",d(time)).append("action",name);}
 @SuppressWarnings("unchecked") @Test void readsRawValuesAndGroupsMedicationsWithEncounterQueries(){
  MongoTemplate mongo=mock(MongoTemplate.class),center=mock(MongoTemplate.class);ObjectProvider<MongoTemplate> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(center);
  HandoverTrendService service=new HandoverTrendService(mongo,provider,mock(HandoverData.class),mock(HandoverAi.class));Instant from=Instant.parse("2026-11-11T08:00:00Z"),to=Instant.parse("2026-11-12T08:00:00Z");Date time=Date.from(from.plusSeconds(3600));
  when(mongo.find(any(Query.class),eq(Document.class),eq("bedside"))).thenReturn(List.of(new Document("code","param_in_day").append("time",time).append("strVal","120"),new Document("code","param_in_day").append("time",time).append("strVal","150"),new Document("code","param_HR").append("time",time).append("strVal","<60")));
  when(center.find(any(Query.class),eq(Document.class),eq("VI_ICU_EXAM_ITEM"))).thenReturn(List.of(new Document("itemCode","LAC").append("result","2.3").append("unit","mmol/L").append("authTime",time).append("resultStatus","审核"),new Document("itemCode","LAC1").append("result","1.0").append("unit","mg/dL").append("authTime",time)));
  when(mongo.find(any(Query.class),eq(Document.class),eq("drugExe"))).thenReturn(List.of(new Document("startTime",time).append("drugList",List.of(new Document("name","模拟药"))).append("drugActionList",List.of(new Document("time",time).append("action","pause")))));
  HandoverTrendService.Chart chart=new HandoverTrendService.Chart();service.readBedside(chart,"patient-1",from,to);service.readLabs(chart,"encounter-1",from,to);service.readDrugs(chart,"patient-1",from,from,to);
  var in=chart.tracks.stream().filter(t->t.label.equals("当日累计入量")).findFirst().orElseThrow();assertEquals("mL",in.unit);assertEquals(List.of(120.0,150.0),in.points.stream().map(p->p.value).toList());assertFalse(chart.tracks.stream().anyMatch(t->t.label.equals("心率")));
  assertEquals(2,chart.tracks.stream().filter(t->t.label.equals("乳酸")).count());assertEquals(1,chart.medications.size());assertEquals("暂停",chart.medications.get(0).records.get(0).actions.get(0).action);
  ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);verify(center).find(query.capture(),eq(Document.class),eq("VI_ICU_EXAM_ITEM"));assertEquals("encounter-1",query.getValue().getQueryObject().getString("hisPid"));assertTrue(query.getValue().getQueryObject().containsKey("authTime"));
 }
 @Test void completeActionsDrawCorrectIntervalsAndPreserveStatusConflicts(){
  Instant from=Instant.parse("2026-11-12T00:00:00Z"),to=Instant.parse("2026-11-13T00:00:00Z");Document row=new Document("startTime",d("2026-11-12T02:00:00Z")).append("endTime",d("2026-11-12T14:00:00Z")).append("status","finished").append("hisStartTime",new Document("statusFlag","未执行")).append("drugActionList",List.of(action("2026-11-12T14:00:00Z","stop"),action("2026-11-12T06:00:00Z","pause"),action("2026-11-12T02:00:00Z","start").append("speed",100).append("speedUnit","ml/h"),action("2026-11-12T12:00:00Z","recovery")));
  var r=HandoverTrendService.drugRecord(row,from,to);assertEquals(List.of("运行","暂停","运行"),r.segments.stream().map(x->x.state).toList());assertEquals(d("2026-11-12T06:00:00Z").getTime(),r.segments.get(0).to);assertEquals(d("2026-11-12T12:00:00Z").getTime(),r.segments.get(1).to);assertEquals(d("2026-11-12T14:00:00Z").getTime(),r.segments.get(2).to);assertEquals("100 ml/h",r.actions.get(0).speed);assertEquals("未执行",r.hisStatus);assertTrue(r.issues.stream().anyMatch(x->x.contains("未执行")));
 }
 @Test void unpairedAmbiguousOrMissingActionsNeverProduceConfirmedIntervals(){
  Instant from=Instant.parse("2026-11-12T00:00:00Z"),to=Instant.parse("2026-11-13T00:00:00Z");
  Document open=new Document("startTime",d("2026-11-12T02:00:00Z")).append("drugActionList",List.of(action("2026-11-12T02:00:00Z","start")));assertEquals("未见结束",HandoverTrendService.drugRecord(open,from,to).segments.get(0).state);
  Document unknown=new Document("startTime",d("2026-11-12T02:00:00Z")).append("drugActionList",List.of(action("2026-11-12T02:00:00Z","start"),action("2026-11-12T03:00:00Z","mystery")));assertEquals("过程未知",HandoverTrendService.drugRecord(unknown,from,to).segments.get(0).state);
  Document duplicate=new Document("startTime",d("2026-11-12T02:00:00Z")).append("drugActionList",List.of(action("2026-11-12T02:00:00Z","start"),action("2026-11-12T02:00:00Z","stop")));assertEquals("过程未知",HandoverTrendService.drugRecord(duplicate,from,to).segments.get(0).state);
  Document reversed=new Document("startTime",d("2026-11-12T02:00:00Z")).append("drugActionList",List.of(action("2026-11-12T02:00:00Z","stop"),action("2026-11-12T02:00:00Z","start")));assertEquals("过程未知",HandoverTrendService.drugRecord(reversed,from,to).segments.get(0).state);
  Document endWithoutStop=new Document("startTime",d("2026-11-12T02:00:00Z")).append("endTime",d("2026-11-12T04:00:00Z")).append("drugActionList",List.of(action("2026-11-12T02:00:00Z","start")));var bounded=HandoverTrendService.drugRecord(endWithoutStop,from,to);assertEquals("过程未知",bounded.segments.get(0).state);assertEquals(d("2026-11-12T04:00:00Z").getTime(),bounded.segments.get(0).to);assertFalse(bounded.issues.isEmpty());
  Document fieldsOnly=new Document("startTime",d("2026-11-12T02:00:00Z")).append("endTime",d("2026-11-12T04:00:00Z"));var rec=HandoverTrendService.drugRecord(fieldsOnly,from,to);assertEquals("过程未知",rec.segments.get(0).state);assertEquals(2,rec.actions.size());
 }
 @Test void identifiesAdjustmentsAndQuickPushWithoutInventingUnits(){
  Instant from=Instant.parse("2026-01-01T00:00:00Z"),to=Instant.parse("2026-01-02T00:00:00Z");
  Document row=new Document("startTime",d("2026-01-01T01:00:00Z")).append("drugActionList",List.of(action("2026-01-01T01:00:00Z","start"),action("2026-01-01T02:00:00Z","add").append("speed",4).append("speedUnit","ml/h"),action("2026-01-01T03:00:00Z","minus").append("speed",2).append("speedUnit","ml/h"),action("2026-01-01T04:00:00Z","quickAdd").append("quickAddAmount",7),action("2026-01-01T05:00:00Z","stop")));
  var rec=HandoverTrendService.drugRecord(row,from,to);assertEquals(List.of("开始","上调","下调","快推","停止"),rec.actions.stream().map(x->x.action).toList());assertEquals("4 ml/h",rec.actions.get(1).speed);assertEquals("7",rec.actions.get(3).quickAddAmount);assertEquals(List.of("运行","运行","运行","运行"),rec.segments.stream().map(x->x.state).toList());
 }
 @Test void simultaneousActionsRetainEachAndDoNotGuessLaterState(){
  Instant from=Instant.parse("2026-01-01T00:00:00Z"),to=Instant.parse("2026-01-02T00:00:00Z");String at="2026-01-01T02:00:00Z";
  Document row=new Document("startTime",d("2026-01-01T01:00:00Z")).append("endTime",d("2026-01-01T05:00:00Z")).append("drugActionList",List.of(action("2026-01-01T01:00:00Z","start"),action(at,"add"),action(at,"minus"),action(at,"pause"),action(at,"recovery"),action(at,"quickAdd").append("quickAddAmount",7),action("2026-01-01T05:00:00Z","stop")));
  var rec=HandoverTrendService.drugRecord(row,from,to);assertEquals(7,rec.actions.size());assertEquals(List.of("运行","过程未知"),rec.segments.stream().map(x->x.state).toList());assertEquals(d(at).getTime(),rec.segments.get(0).to);assertTrue(rec.issues.stream().anyMatch(x->x.contains("先后顺序未知")));
 }
 @Test void repeatedStartIsNotAConfirmedInterval(){
  Instant from=Instant.parse("2026-01-01T00:00:00Z"),to=Instant.parse("2026-01-02T00:00:00Z");Document row=new Document("startTime",d("2026-01-01T01:00:00Z")).append("drugActionList",List.of(action("2026-01-01T01:00:00Z","start"),action("2026-01-01T02:00:00Z","start"),action("2026-01-01T03:00:00Z","stop")));var rec=HandoverTrendService.drugRecord(row,from,to);assertEquals(List.of("过程未知","运行"),rec.segments.stream().map(x->x.state).toList());
 }
 @Test void rejectsNonExactNumericResults(){assertNull(HandoverTrendService.number("<3"));assertNull(HandoverTrendService.number("NaN"));assertEquals(-1.5,HandoverTrendService.number("-1.5"));}
 @Test void aiUnavailableIsExplicitlyDefault(){HandoverAi ai=new HandoverAi(new ObjectMapper());var chart=new HandoverTrendService.Chart();chart.tracks.add(HandoverTrendService.track("test","心率","次/分","体征"));ai.chooseTrends(chart,new HandoverData.Snapshot(),"测试","");assertFalse(chart.aiSelected);assertEquals(List.of("test"),chart.selected);assertTrue(chart.selectedDrugs.isEmpty());assertTrue(chart.warnings.stream().anyMatch(w->w.contains("默认")));}
 @Test void aiUsesRealChangesAndCanOnlySelectCandidateIds() throws Exception {
  var mapper=new ObjectMapper();class TestAi extends HandoverAi {String payload;Object request;TestAi(){super(mapper);}@Override JsonNode post(String path,Object req){request=req;try{return mapper.readTree(payload);}catch(Exception e){throw new RuntimeException(e);}}}
  TestAi ai=new TestAi();org.springframework.test.util.ReflectionTestUtils.setField(ai,"base","http://localhost");org.springframework.test.util.ReflectionTestUtils.setField(ai,"model","test");
  var c=new HandoverTrendService.Chart();var track=HandoverTrendService.track("hr","心率","次/分","体征");HandoverTrendService.point(track,1762900000000L,72,"");c.tracks.add(track);var drug=new HandoverTrendService.DrugTrack();drug.id="drug:1";drug.name="合成药";c.medications.add(drug);
  var snap=new HandoverData.Snapshot();snap.sources.add(HandoverAiTest.source("N1","合成病程变化",true));
  ai.payload=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",mapper.writeValueAsString(Map.of("metrics",List.of(Map.of("id","hr","reason","结合近期心率变化")),"drugs",List.of(Map.of("id","drug:1","reason","病程提及")))))))));
  ai.chooseTrends(c,snap,"合成诊断","");assertTrue(c.aiSelected);assertEquals(List.of("hr"),c.selected);assertEquals(List.of("drug:1"),c.selectedDrugs);String sent=mapper.writeValueAsString(ai.request);assertTrue(sent.contains("72.0"));assertTrue(sent.contains("合成病程变化"));assertTrue(sent.contains("合成药"));
  var bad=new HandoverTrendService.Chart();bad.tracks.add(track);bad.medications.add(drug);ai.payload=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","{\"metrics\":[],\"drugs\":[{\"id\":\"invented\",\"reason\":\"wrong\"}]}")))));ai.chooseTrends(bad,snap,"","");assertFalse(bad.aiSelected);assertTrue(bad.selectedDrugs.isEmpty());
 }
}
