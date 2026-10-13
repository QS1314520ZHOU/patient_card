package com.patientcard.patient_card_backend.model.patient;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class DiagnosisHistory {
    private Date time;

    private String editor;

    private String editorId;

    private String diagnosis;

    private List<String> diagnosisCodeList;
}
