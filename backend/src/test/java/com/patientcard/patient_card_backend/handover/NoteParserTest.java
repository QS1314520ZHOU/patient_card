package com.patientcard.patient_card_backend.handover;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class NoteParserTest {
 private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
 @Test void handlesHeaderAndLegacyBodyNewlines() {
  String json="{\"code\":0,\"data\":[{\"BLH\":\"test-mrn\",\"BQDM\":\"W1\",\"MBKMC\":\"日常记录\",\"WBNR\":\"2026-11-11 19: 54\n合成测试内容\"}]}";
  var notes=NoteParser.parse(bytes(json),"test-mrn","W1",10,1000);
  assertEquals(LocalDateTime.of(2026,11,11,19,54), notes.get(0).time);
  assertTrue(notes.get(0).body.contains("\n"));
 }
 @Test void noGuessingBodyDate() { assertNull(NoteParser.headerTime("病史中记录2026-11-11 19:54")); assertNull(NoteParser.headerTime("2026-02-30 19:54 note")); }
 @Test void wrongIdentityRejectsEntireBatch() {
  String json="{\"code\":0,\"data\":[{\"BLH\":\"other\",\"BQDM\":\"W1\",\"WBNR\":\"2026-11-11 19:54 测试\"}]}";
  assertThrows(IllegalArgumentException.class,()->NoteParser.parse(bytes(json),"test-mrn","W1",10,1000));
  assertThrows(IllegalArgumentException.class,()->NoteParser.parse(bytes(json),"other","W2",10,1000));
 }
 @Test void doesNotGuessUnescapedQuotesOrDuplicateFields() {
  assertThrows(IllegalArgumentException.class,()->NoteParser.parse(bytes("{\"code\":0,\"code\":0,\"data\":[]}"),"x","y",10,1000));
  assertThrows(IllegalArgumentException.class,()->NoteParser.parse(bytes("{\"code\":0,\"data\":[{\"BLH\":\"x\",\"BQDM\":\"y\",\"WBNR\":\"2026-11-11 12:00 \"quoted\"\"}]}"),"x","y",10,1000));
 }
 @Test void windowIsHalfOpenAndNeverFuture() {
  Window w=new Window(Instant.parse("2026-11-12T04:00:00Z"));
  assertTrue(w.contains(LocalDateTime.of(2026,11,11,8,0)));
  assertTrue(w.contains(LocalDateTime.of(2026,11,12,7,59)));
  assertFalse(w.contains(LocalDateTime.of(2026,11,12,8,0)));
  assertFalse(w.contains(LocalDateTime.of(2026,11,11,7,59)));
  Window early=new Window(Instant.parse("2026-11-11T22:00:00Z"));
  assertFalse(early.contains(LocalDateTime.of(2026,11,12,7,0)));
 }
}
