package com.patientcard.patient_card_backend.model.overview;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 床号旁隔离角标（来自 DataCenter VI_ICU_ZYYZ 隔离医嘱的启停判定）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IsolationTag {
    private String label;
    /** contact | air | protect | droplet | bug | other */
    private String kind;
    private String title;
}
