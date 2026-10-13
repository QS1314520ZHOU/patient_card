package com.patientcard.patient_card_backend.model.overview;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 三管/管路留置角标：days=留置天数，level=low|warn|crit（按院内阈值配置）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TubeChip {
    private String label;
    private Integer days;
    /** low | warn | crit */
    private String level;
    private String title;
}
