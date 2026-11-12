package com.patientcard.patient_card_backend.handover;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HandoverAiTest {
 @Test void scopedVectorRetrievalReturnsOriginalAndCitations() throws Exception {
  ObjectMapper mapper=new ObjectMapper();List<String> inputs=new ArrayList<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/embeddings",x->{var req=mapper.readTree(x.getRequestBody());req.path("input").forEach(n->inputs.add(n.asText()));var data=mapper.createArrayNode();for(int i=0;i<inputs.size();i++)data.addObject().put("index",i).putArray("embedding").add(1).add(i==0?0:1);byte[] b=mapper.writeValueAsBytes(Map.of("data",data));x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});
  server.createContext("/chat",x->{x.getRequestBody().readAllBytes();byte[] b="{\"choices\":[{\"message\":{\"content\":\"本班记录 [N1]，历史 [N2]。\"}}]}".getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});server.start();
  try{HandoverAi ai=new HandoverAi(mapper);for(var e:Map.of("base","http://127.0.0.1:"+server.getAddress().getPort(),"embeddingPath","/embeddings","chatPath","/chat","model","test-model","embeddingModel","test-embedding","key","").entrySet())ReflectionTestUtils.setField(ai,e.getKey(),e.getValue());
   HandoverData.Source recent=source("N1","当班合成记录",true),history=source("N2","历史合成记录"+"x".repeat(2000),false);
   HandoverData.Snapshot snapshot=new HandoverData.Snapshot();snapshot.from="2026-11-11T08:00";snapshot.to="2026-11-12T08:00";snapshot.asOf="2026-11-12T12:00";snapshot.sources.add(recent);snapshot.sources.add(history);
   HandoverAi.Result result=ai.generate(snapshot);assertEquals(2,inputs.size());assertTrue(inputs.get(0).contains("当班合成记录"));assertTrue(inputs.get(1).contains("历史合成记录"));assertEquals(history.text,result.sources.get(1).text);assertTrue(result.sources.get(1).excerpted);assertEquals(1500,result.sources.get(1).sentChars);assertFalse(result.draft.contains("[N1]"));assertTrue(result.draft.contains("本班记录"));
  }finally{server.stop(0);}
 }
 @Test void cosineHandlesZeroAndOrder(){assertEquals(0,HandoverAi.cosine(new double[]{0,0},new double[]{1,0}));assertTrue(HandoverAi.cosine(new double[]{1,0},new double[]{1,0})>HandoverAi.cosine(new double[]{1,0},new double[]{0,1}));}
 static HandoverData.Source source(String id,String text,boolean window){HandoverData.Source s=new HandoverData.Source();s.id=id;s.kind="病程";s.title="合成测试";s.text=text;s.time="2026-11-11T10:00";s.window=window;return s;}
 @Test void rejectsUnknownCitationAndIncompleteModelResponse() throws Exception {
  ObjectMapper mapper=new ObjectMapper();String[] response={"{\"choices\":[{\"message\":{\"content\":\"错误引用 [X99]\"}}]}"};HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/chat",x->{x.getRequestBody().readAllBytes();byte[] b=response[0].getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});server.start();
  try{HandoverAi ai=new HandoverAi(mapper);for(var e:Map.of("base","http://127.0.0.1:"+server.getAddress().getPort(),"embeddingPath","/embeddings","chatPath","/chat","model","m","embeddingModel","e","key","").entrySet())ReflectionTestUtils.setField(ai,e.getKey(),e.getValue());
   HandoverData.Snapshot snap=new HandoverData.Snapshot();snap.sources.add(source("N1","合成数据",true));
   assertThrows(IllegalStateException.class,()->ai.generate(snap));response[0]="{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"草稿 [N1]\"}}]}";assertThrows(IllegalStateException.class,()->ai.generate(snap));
  }finally{server.stop(0);}
 }

 @Test void sendsThinkingSettingAndDistinguishesIncompleteFromFiltered() throws Exception {
  ObjectMapper mapper=new ObjectMapper();List<com.fasterxml.jackson.databind.JsonNode> requests=new ArrayList<>();String[] finish={"stop"};
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/chat",x->{requests.add(mapper.readTree(x.getRequestBody()));byte[] b=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",finish[0],"message",Map.of("content","合成草稿 [N1]")))));x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});server.start();
  try{HandoverAi ai=new HandoverAi(mapper);for(var e:Map.of("base","http://127.0.0.1:"+server.getAddress().getPort(),"chatPath","/chat","model","m","embeddingModel","e","key","").entrySet())ReflectionTestUtils.setField(ai,e.getKey(),e.getValue());
   HandoverData.Snapshot snap=new HandoverData.Snapshot();snap.sources.add(source("N1","合成数据",true));
   ai.generate(snap);assertEquals(3000,requests.get(0).path("max_tokens").asInt());assertFalse(requests.get(0).path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
   ReflectionTestUtils.setField(ai,"thinking","default");ReflectionTestUtils.setField(ai,"maxTokens",4096);ai.generate(snap);assertFalse(requests.get(1).has("chat_template_kwargs"));assertEquals(4096,requests.get(1).path("max_tokens").asInt());
   ReflectionTestUtils.setField(ai,"thinking","on");finish[0]="length";assertTrue(assertThrows(IllegalStateException.class,()->ai.generate(snap)).getMessage().contains("长度上限"));assertTrue(requests.get(2).path("chat_template_kwargs").path("enable_thinking").asBoolean(false));
   finish[0]="content_filter";assertTrue(assertThrows(IllegalStateException.class,()->ai.generate(snap)).getMessage().contains("过滤"));
   ReflectionTestUtils.setField(ai,"maxTokens",8193);int before=requests.size();assertTrue(assertThrows(IllegalStateException.class,()->ai.generate(snap)).getMessage().contains("参数无效"));assertEquals(before,requests.size());
  }finally{server.stop(0);}
 }

 @Test void plainDraftRemovesMarkdownAndInternalReferencesWithoutDroppingContent(){
  String output=HandoverAi.plainDraft("### 1. 病情变化\n- **心率：** 60次/分 [N1][V2]\n\n**待核对**\n- 出入量 [F3]");
  assertEquals("1. 病情变化\n心率： 60次/分\n\n待核对\n出入量",output);
 }

}
