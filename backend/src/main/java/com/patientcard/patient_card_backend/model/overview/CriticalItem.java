package com.patientcard.patient_card_backend.model.overview;

import lombok.Data;

import java.util.Date;

/**
 * 危急值条目：handled=true 已闭环，false 未闭环（交班重点督办）。
 * carriedOver=true 表示未闭环项发布于事件窗之前，由上一班跨班承接而来。
 */
@Data
public class CriticalItem {
    private String item;
    private String value;
    private Date time;
    private Boolean handled;
    /** 闭环时间（源系统 handleTime） */
    private Date handleTime;
    /** true=跨班承接（事件窗之前发布且仍未闭环） */
    private Boolean carriedOver;
    /** 发布至今小时数，用于识别陈旧未闭环 */
    private Integer ageHours;
}
