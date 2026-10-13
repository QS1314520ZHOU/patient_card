package com.patientcard.patient_card_backend.model.overview;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
public class OverviewSnapshot {
    private String snapshotId;
    private String asOf;
    private Date eventFrom;
    private Date eventTo;
    /** prevShift | last24h | custom | default */
    private String eventWindowPreset;
    /** 事件窗口径中文名，直接上顶栏 */
    private String eventWindowLabel;
    private OverviewScope scope;
    private List<MetricValue> metrics;
    private List<GroupSummary> groups;
    private List<OverviewPatient> patients;
    private List<DepartureEvent> departures;
    /** 本班变化清单（事件窗内，按时间倒序，已按视图 scope 标注） */
    private List<ChangeItem> changes;
    /** metricKey -> 命中患者 id（与卡片同源，保证下钻一致） */
    private Map<String, List<String>> metricPatientIds;
    /** metricKey -> 命中离科事件 id */
    private Map<String, List<String>> metricDepartureIds;
    private Map<String, Object> quality;
    private Integer refreshSeconds;
    /** ICU 长程住院阈值（天），前端据此打“长程”角标 */
    private Integer longStayDays;
    /** 出入量平衡窗（按 initSystemConfig.banCiInfoList 计算，非写死） */
    private String ioWindowStart;
    private String ioWindowEnd;
    /** 夜班事件窗（危急值/抢救回溯） */
    private String nightWindowStart;
    private String nightWindowEnd;
    /** 科室床位清单（空床暗色卡） */
    private java.util.List<BedSlot> beds;
}
