package com.patientcard.patient_card_backend.model.overview;

import lombok.Data;

import java.util.Date;

/**
 * 大屏患者卡（交班摘要仅由已确认结构化字段组成，不推断临床结论）。
 */
@Data
public class OverviewPatient {
    private String id;
    private String hisPid;
    private String bed;
    private String name;
    private Integer age;
    private String gender;
    private String mrn;
    private String tel;
    private String diagnosis;
    private String medicalGroup;
    private String bedDoctor;
    private String responsibleNurse;
    private Date admissionTime;
    private Integer icuDays;
    private Boolean newAdmission;
    private Boolean surgeryAdmission;
    private Boolean transferIn;
    private Boolean ventilator;
    /** 呼吸机模式：bedside code=param_HuXiMoShi 最新非空 strVal；null=未采集到 */
    private String ventMode;
    /** high | medium | low | unknown | none(未评估) */
    private String fallLevel;
    private String fallResult;
    private String fallScore;
    private Date fallAssessTime;
    private String pressureLevel;
    private String pressureResult;
    private String pressureScore;
    private Date pressureAssessTime;
    private String pipeLevel;
    private String nutritionLevel;
    private String vteLevel;
    private String vteResult;

    // ===== 生命支持 / 微泵 =====
    private Boolean crrt;
    /** tubeExe type ∈ organ-types（ECMO/IABP）且 endTime 为空 = 高级脏器支持在用 */
    private Boolean organSupport;
    private String organSupportDetail;
    private Boolean vasoactive;
    private java.util.List<String> vasoactiveDrugs;

    // ===== 24h 出入量（按班次配置窗口）=====
    private Integer ioInMl;
    private Integer ioOutMl;
    private Integer ioBalanceMl;
    private Integer urineMl;

    // ===== 意识 / 镇静 / 客观评分 =====
    private Integer rass;
    private Date rassTime;
    private Integer gcs;
    private Date gcsTime;
    private Integer sofa;
    private Integer sofaDelta;
    private Date sofaTime;
    private Integer apacheII;
    private Integer apacheDelta;
    private Date apacheTime;

    // ===== 三管留置 =====
    private java.util.List<TubeChip> tubes;
    private Boolean airwayInUse;
    /** RASS +1~+2 且气管插管在用 → 自动 UEX 高危 */
    private Boolean uexHigh;

    // ===== 隔离 / 过敏 / 夜间事件 =====
    private java.util.List<IsolationTag> bedTags;
    private String allergy;
    private Boolean rescueNight;
    private String rescueDetail;
    private Integer criticalPending;
    private Integer criticalHandled;
    /** 其中发布于事件窗之前、由上一班跨班承接的未闭环项 */
    private Integer criticalCarried;
    private java.util.List<CriticalItem> criticals;
}
