package com.patientcard.patient_card_backend.model.overview;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 快照内床位清单（用于空床暗色卡展示）。
 */
@Data
@NoArgsConstructor@AllArgsConstructor
public class BedSlot {
    private String hisName;
    private String showName;
}
