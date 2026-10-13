package com.patientcard.patient_card_backend.model.overview;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverviewScope {
    private String scopeId;
    private String hospitalCode;
    private String normalizedDeptCode;
    private String deptName;
    private List<String> wardCodes;
    private List<String> allowedActions;
}
