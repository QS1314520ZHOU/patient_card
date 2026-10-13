package com.patientcard.patient_card_backend.model.overview;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统计指标（对齐需求文档 9.4：value 不可用时为 null，禁止用 0 掩盖来源缺失）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricValue {
    private String metricKey;
    private String label;
    private Integer value;
    private String unit;
    /** current | event-window */
    private String timeBasis;
    private Integer knownCount;
    private Integer unknownCount;
    private Integer notApplicableCount;
    private Double coverage;
    /** ok | partial | stale | unavailable */
    private String qualityStatus;
    private String asOf;
    private Boolean drilldownAvailable;
    private String note;
    /** 单位为“项”时另返涉及人数（需求 5.3：项 ≠ 人数，下钻给的是人） */
    private Integer affectedPatientCount;
}
