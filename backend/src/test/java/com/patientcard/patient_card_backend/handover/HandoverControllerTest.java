package com.patientcard.patient_card_backend.handover;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
class HandoverControllerTest {
 @Test void rejectsCrossSiteAndCrossOrigin(){MockHttpServletRequest r=new MockHttpServletRequest("POST","/api/handover/generate");r.setScheme("http");r.setServerName("hospital.test");r.setServerPort(8080);r.addHeader("Origin","http://other.test:8080");assertFalse(HandoverController.sameOrigin(r));}
 @Test void acceptsMatchingOrigin(){MockHttpServletRequest r=new MockHttpServletRequest("POST","/api/handover/generate");r.setScheme("http");r.setServerName("hospital.test");r.setServerPort(8080);r.addHeader("Origin","http://hospital.test:8080");assertTrue(HandoverController.sameOrigin(r));r.addHeader("Sec-Fetch-Site","cross-site");assertFalse(HandoverController.sameOrigin(r));}
}
