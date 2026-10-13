package com.patientcard.patient_card_backend.model.overview;

import lombok.Data;

import java.util.Date;

/**
 * 事件窗内离科事件（转出/出院/死亡）。分类来自现有字段的最佳努力，quality 标注待核实。
 */
@Data
public class DepartureEvent {
    private String id;
    private String bed;
    private String name;
    /** transferred | discharged | died */
    private String type;
    private String typeLabel;
    private Date time;
    /** 入科时间（用于识别窗内新收后又离科） */
    private Date admissionTime;
    private String sourceType;
}
