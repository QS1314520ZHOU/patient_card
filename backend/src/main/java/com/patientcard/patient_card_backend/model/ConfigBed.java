package com.patientcard.patient_card_backend.model;

import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("configBed")
@Data
public class ConfigBed {
    private String id;
    private String hisName;
    private String showName;
    private String centralName;
    private String printName;
    private String deptCode;
}
