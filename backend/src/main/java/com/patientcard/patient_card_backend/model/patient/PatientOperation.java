package com.patientcard.patient_card_backend.model.patient;

import lombok.Data;

import java.util.Date;

@Data
public class PatientOperation {
    private String orderId;

    private Date startTime;

    private Date endTime;

    private String name;

    private String code;
}
