package com.patientcard.patient_card_backend.model.patient;

import lombok.Data;

import java.util.Date;

@Data
public class AllergicAndPastRecord {
    private String editId;

    private Date editAllergicTime;

    private String editAllergicDesc;

    private Date syncAllergicTime;

    private String syncAllergicDesc; // 过敏史

    private Date editPastTime;

    private String editPastDesc;

    private Date syncPastTime;

    private String syncPastDesc;
}
