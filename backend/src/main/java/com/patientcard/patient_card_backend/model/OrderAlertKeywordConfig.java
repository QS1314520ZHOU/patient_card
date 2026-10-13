package com.patientcard.patient_card_backend.model;

import lombok.Data;

@Data
public class OrderAlertKeywordConfig {
    private String keyword;
    private String label;
    private Boolean enabled;
    private Integer sortOrder;
}
