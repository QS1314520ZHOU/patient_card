package com.patientcard.patient_card_backend.handover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BlbcjlClientTest {
 @Test void sendsExpectedProtocolWithSyntheticData() throws Exception {
  AtomicBoolean checked = new AtomicBoolean(false); AtomicReference<String> expectedBqdm = new AtomicReference<>("W1");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/notes",x->{
   var root=new ObjectMapper().readTree(x.getRequestBody());
   checked.set("POST".equals(x.getRequestMethod()) && "BLBCJL".equals(root.path("method").asText()) && expectedBqdm.get().equals(root.path("data").path("BQDM").asText()) && "test-mrn".equals(root.path("data").path("BLH").asText()));
   byte[] response="{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
   x.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8"); x.sendResponseHeaders(200,response.length); x.getResponseBody().write(response);x.close();
  });
  server.start();
  try {
   BlbcjlClient c=new BlbcjlClient(new ObjectMapper());
   ReflectionTestUtils.setField(c,"endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/notes");
   ReflectionTestUtils.setField(c,"accessKey","synthetic-key");ReflectionTestUtils.setField(c,"mbkmc","");ReflectionTestUtils.setField(c,"wjjgmc","");
   ReflectionTestUtils.setField(c,"connectTimeoutMs",2000);ReflectionTestUtils.setField(c,"readTimeoutMs",2000);ReflectionTestUtils.setField(c,"maxResponseBytes",2048);
   assertEquals(0,NoteParser.parse(c.fetch("test-mrn","W1"),"test-mrn","W1",10,1000).size()); assertTrue(checked.get());
   expectedBqdm.set("");checked.set(false);
   assertEquals(0,NoteParser.parse(c.fetch("test-mrn",""),"test-mrn","",10,1000).size());assertTrue(checked.get());
  } finally {server.stop(0);}
 }
 @Test void missingConfigIsClosed() {BlbcjlClient c=new BlbcjlClient(new ObjectMapper());assertThrows(IllegalStateException.class,()->c.fetch("test-mrn","W1"));}
}
