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
   HandoverAi.Result result=ai.generate(snapshot);assertEquals(2,inputs.size());assertTrue(inputs.get(0).contains("当班合成记录"));assertTrue(inputs.get(1).contains("历史合成记录"));assertEquals(history.text,result.sources.get(1).text);assertTrue(result.sources.get(1).excerpted);assertEquals(1500,result.sources.get(1).sentChars);assertTrue(result.draft.contains("[N1]"));
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

}
