package com.patientcard.patient_card_backend.model.patient;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {
    private String level;
    private String result;
    private String score;
    private Date assessTime;
    private String assessName;
}
