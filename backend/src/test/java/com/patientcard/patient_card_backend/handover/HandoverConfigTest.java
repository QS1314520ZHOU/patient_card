package com.patientcard.patient_card_backend.handover;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;
class HandoverConfigTest {
 @Configuration @EnableConfigurationProperties(HandoverConfig.class) static class TestConfig {}
 @Test void departmentMapRetainsCaseForYamlStyleKeys(){
  new ApplicationContextRunner().withUserConfiguration(TestConfig.class).withPropertyValues("handover.enabled=true","handover.bqdm-by-dept.DeptA=W-A","handover.bqdm-by-dept.DeptB=W-B").run(ctx->{HandoverConfig c=ctx.getBean(HandoverConfig.class);assertTrue(c.isEnabled());assertEquals("W-A",c.getBqdmByDept().get("DeptA"));assertEquals("W-B",c.getBqdmByDept().get("DeptB"));});
 }
}
