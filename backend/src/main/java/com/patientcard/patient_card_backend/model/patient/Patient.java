package com.patientcard.patient_card_backend.model.patient;

import com.patientcard.patient_card_backend.dxmEnum.Gender;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;
import java.util.Set;

@Document("patient")
@Data
public class Patient {

    private String id;

    private String hisPid;

    private String patientId;

    private String mrn; // 住院号

    private Date createdTime;

    private Date admissionTime; // 住院时间

    private String admissionDiagnosis; // 住院诊断

    private String name; // 病人名字

    private String height; // 身高

    private Date birthday;

    private String nativePlace; // 籍贯

    private String weight; // 体重

    private String documentType;

    private String occupation;

    private String homePhone;

    private Gender gender; // 性别

    private String maritalStatus;

    private String address; // 地址

    private String bloodType; // 血型类型

    private String bloodRH; // 血型

    private String nation;

    private String pastHistory;  // 既往史

    private String allergic; // 过敏史

    private List<AllergicAndPastRecord> allergicAndPastRecordList; // 过敏史记录

    private String contactsName;  // 联系人

    private String contactRelationship;

    private String contactPhone; // 联系人电话

    private String contactAddress; // 联系人地址

    private String settlementMethod;

    private String hospitalTime;

    private String oneMonthWeight;

    private String twoMonthWeight;

    private String threeMonthWeight;

    private List<BMIChangeHistory> bmiChangeHistoryList; // BMI相关指数历史变更

    private String diet;

    private String isolation;

    private String position;

    private String hisBed;

    private Date bedTime;

    private String status;

    private Date icuAdmissionTime;

    private Date inAccessTime;

    private String admissionSource;

    private String admissionSourceCode;

    private String admissionPlan;

    private String clinicalDiagnosis; // 临床诊断

    private Date clinicalDiagnosisTime;

    private List<String> clinicalDiagnosisCodeList;

    private String insuranceType;

    private String insuranceNumber;

    private String hospitalNumber;

    private String dept;

    private String deptCode;

    private String medicalGroup; // 组别（大交班分组展示）

    private String idCard;

    private String tel;

    private String operation; // 手术

    private Date operationTime;

    private String vip;

    private String chiefComplaint;

    private String bedDoctorId;

    private String bedDoctor; // 管床医生

    private String bedDoctorPhone;

    private String bedPhysician;

    private String bedPhysicianPhone;

    private String treatedDoctorId;

    private String treatedDoctor;

    private String admissionType; // 入科类型

    private String illnessLevel; // 病情等级

    private String nursingLevel; // 护理等级

    private String responsibleNurse;

    private List<PatientOperation> patientOperations;

    private List<DiagnosisHistory> diagnosisHistoryList;

    private String diagnosisType;

    private String manual;

    private String dischargedType;

    private String dischargedDepartment;

    private String dischargedDepartmentCode;

    private Date icuDischargeTime;

    private String dischargedDiagnosis;

    private List<String> dischargedDiagnosisIcd;

    private Date deathTime; // 死亡时间

    private Date dischargeTime;

    private Boolean merge;

    private String specialDischargedCause;

    private String idType;

    private String contactsPhone;

    private String contactsRelation;

    private String operationsNumber;

    private String icuOrderNumber;

    private String editInICUAccountId;

    private Date editInICUTime;

    private Boolean allowReturn;

    private String expenseType;

    private String customaryLanguage;

    private Boolean inAndOutVolume24Hour;

    private Boolean preventFall;

    private Boolean preventFallingBed;

    private Boolean thrombosisRisk;

    private String verticalTag1;

    private String verticalTag2;

    private Integer useBedDays;

    private Set<String> scholarShipIdList;

    private Boolean isNewPat;

    private String icuDay;

    private String wardCode;

    private String showTag;

    private boolean reintubation48h;

    private boolean returnIcu48h;

    // 前端展示用：是否存在“病重”医嘱
    private Boolean hasSevereOrder;

    // 前端展示用：是否存在“病危”医嘱
    private Boolean hasCriticalOrder;

    // 前端展示用：命中的医嘱关键字（用于底部红圈动态展示）
    private List<String> orderAlertKeywords;

    // 08:00 至次日 08:00 业务日内新入科
    private Boolean newAdmission;

    // 入科类型标识
    private Boolean surgeryAdmission;
    private Boolean transferIn;

    // 最新风险评估
    private RiskAssessment vteRisk;
    private RiskAssessment pipeRisk;
    private RiskAssessment pressureDamageRisk;
    private RiskAssessment nutritionRisk;
    private RiskAssessment fallRisk;

    // 卡片强预警（dashboard 接口 enrichment 填充）
    private Boolean ventilator;
    private Boolean crrt;
    private Boolean difficultAirway;

    // 24h 出入量（bedside + configLiquidBalance，窗口按班次配置）
    private Integer ioInMl;
    private Integer ioOutMl;
    private Integer urineMl;
    private Integer ioBalanceMl;
    private String ioWindowStart;
    private String ioWindowEnd;
}
