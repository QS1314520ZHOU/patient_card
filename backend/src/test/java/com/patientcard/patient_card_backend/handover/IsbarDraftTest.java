package com.patientcard.patient_card_backend.handover;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class IsbarDraftTest {
 @Test void configuredWindowsAreHalfOpenAndExcludeFuture(){
  Window day=new Window(Instant.parse("2026-11-21T12:00:00Z"),"08:00","20:00");
  assertEquals(LocalDateTime.parse("2026-11-21T08:00"),day.from);
  assertTrue(day.contains(day.from));assertFalse(day.contains(day.to));
  Window night=new Window(Instant.parse("2026-11-21T01:00:00Z"),"20:00","08:00");
  assertEquals(LocalDateTime.parse("2026-11-20T20:00"),night.from);
  Window early=new Window(Instant.parse("2026-11-20T22:00:00Z"));
  assertFalse(early.contains(LocalDateTime.parse("2026-11-21T07:00")));
 }
 @Test void sectionsPreserveReferencesAndNeverInferMissingSections(){
  var result=HandoverAi.sections("S现况\n合成变化 [N1]\nA呼吸：\n合成记录 [V2]\nR已有计划\n合成原计划 [N1]");
  assertEquals("合成变化 [N1]",result.get("S现况"));assertEquals("待核对",result.get("物品交接"));
  assertEquals("合成记录 [V2]",result.get("A呼吸"));
  assertEquals("旧格式 [N1]",HandoverAi.sections("旧格式 [N1]").get("未分区原稿"));
 }
 @Test void invalidShiftConfigurationFails(){assertThrows(java.time.format.DateTimeParseException.class,()->new HandoverConfig().setShiftStart("25:00"));}
}
