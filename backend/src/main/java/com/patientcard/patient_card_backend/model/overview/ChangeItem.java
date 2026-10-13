package com.patientcard.patient_card_backend.model.overview;

import lombok.Data;

import java.util.Date;

/**
 * 交班事件窗内的单条变化（本班变化清单）。
 * 只由已确认结构化字段拼装，不推断临床结论；scope 供医生/护理/综合视图过滤。
 */
@Data
public class ChangeItem {
    /** 关联患者 id（快照内患者；离科事件为离科患者 id） */
    private String patientId;
    private String bed;
    private String name;
    /** admission | departure | critical | criticalClosed | score | tube | device | drug */
    private String kind;
    /** 短标签，如「危急值新增」「管路置入」 */
    private String label;
    /** 结构化说明，如「K+ 2.3mmol/L」 */
    private String detail;
    private Date time;
    /** 数据来源集合，便于口径追溯 */
    private String source;
    /** both | doctor | nurse */
    private String scope;

    public ChangeItem() {
    }

    public ChangeItem(String patientId, String bed, String name, String kind,
                      String label, String detail, Date time, String source, String scope) {
        this.patientId = patientId;
        this.bed = bed;
        this.name = name;
        this.kind = kind;
        this.label = label;
        this.detail = detail;
        this.time = time;
        this.source = source;
        this.scope = scope;
    }
}
