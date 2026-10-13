package com.patientcard.patient_card_backend.model.overview;

import lombok.Data;

import java.util.List;

@Data
public class GroupSummary {
    private String groupName;
    private Integer count;
    private List<String> doctors;
    private Integer ventilatorCount;
    private Integer fallHighCount;
    private Integer pressureHighCount;
    private List<String> patientIds;
}
