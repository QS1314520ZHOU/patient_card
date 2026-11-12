package com.patientcard.patient_card_backend.service;

/** 入科类型标识口径；入院/外院与按时间判断的新收是不同概念。 */
public final class AdmissionTypeFlags {
    private AdmissionTypeFlags() { }

    public static boolean surgeryAdmission(String admissionType) {
        return admissionType != null && admissionType.contains("手术");
    }

    public static boolean transferIn(String admissionType) {
        return admissionType != null && admissionType.contains("转入");
    }

    public static boolean hospitalAdmission(String admissionType) {
        return admissionType != null && (admissionType.contains("入院") || admissionType.contains("外院"));
    }
}
