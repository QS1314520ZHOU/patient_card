package com.patientcard.patient_card_backend.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class NewAdmissionMarkerTest {
    private Date date(String value) { return Date.from(Instant.parse(value)); }

    @Test
    void keepsHandoverWindowAndExpiresAfter24Hours() {
        Date now = date("2026-11-16T11:00:00+08:00");
        assertTrue(DeptOverviewService.isNewAdmission24h(date("2026-11-15T11:00:00+08:00"), now));
        assertFalse(DeptOverviewService.isNewAdmission24h(date("2026-11-15T10:59:59+08:00"), now));
        assertTrue(DeptOverviewService.isNewAdmission24h(date("2026-11-16T07:59:59+08:00"), now));
        assertFalse(DeptOverviewService.isNewAdmission24h(date("2026-11-16T08:00:00+08:00"), now));
        assertFalse(DeptOverviewService.isNewAdmission24h(null, now));
    }

    @Test
    void excludesFutureAdmissionsBeforeMorningHandover() {
        Date now = date("2026-11-16T07:00:00+08:00");
        assertTrue(DeptOverviewService.isNewAdmission24h(date("2026-11-15T08:00:00+08:00"), now));
        assertFalse(DeptOverviewService.isNewAdmission24h(date("2026-11-16T07:30:00+08:00"), now));
        assertFalse(DeptOverviewService.isNewAdmission24h(date("2026-11-15T07:59:59+08:00"), now));
    }
}
