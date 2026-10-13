package com.patientcard.patient_card_backend.controller;

import com.patientcard.patient_card_backend.model.overview.ChangeItem;
import com.patientcard.patient_card_backend.model.overview.CriticalItem;
import com.patientcard.patient_card_backend.model.overview.DepartureEvent;
import com.patientcard.patient_card_backend.model.overview.OverviewPatient;
import com.patientcard.patient_card_backend.model.overview.OverviewScope;
import com.patientcard.patient_card_backend.model.overview.OverviewSnapshot;
import com.patientcard.patient_card_backend.service.DeptOverviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 大交班大屏接口（对齐需求文档 9.3 建议 API 的 P0 子集）。
 * 鉴权为联调项：当前仅来源校验 + 只读，deptCode 本身不作为授权凭证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/icu-overview")
@CrossOrigin
public class OverviewController {

    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DeptOverviewService overviewService;

    public OverviewController(DeptOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    /**
     * 科室上下文解析（resolve 的只读等价物）：校验 deptCode 并返回规范范围。
     */
    @GetMapping("/context/{deptCode}")
    public OverviewScope resolveContext(@PathVariable String deptCode) {
        if (deptCode == null || deptCode.trim().isEmpty()) {
            throw new IllegalArgumentException("科室编码不能为空");
        }
        return overviewService.resolveScope(deptCode);
    }

    /**
     * 主快照：统计指标 + 分组 + 患者卡 + 离科事件 + 本班变化清单，同一 snapshotId/asOf 对齐。
     * 事件窗口径二选一：windowPreset=prevShift|last24h（后端计算），或显式 eventFrom/eventTo。
     */
    @GetMapping
    public ResponseEntity<?> getOverview(
            @RequestParam String deptCode,
            @RequestParam(required = false) String windowPreset,
            @RequestParam(required = false) String eventFrom,
            @RequestParam(required = false) String eventTo) {
        if (deptCode == null || deptCode.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "科室编码不能为空"));
        }
        try {
            Date from = DeptOverviewService.parseDate(eventFrom);
            Date to = DeptOverviewService.parseDate(eventTo);
            OverviewSnapshot snapshot = overviewService.buildSnapshot(deptCode, from, to, windowPreset);
            return ResponseEntity.ok(snapshot);
        } catch (Exception ex) {
            // 快照失败必须留痕，否则前端只见“生成失败：null”无从排查
            log.error("概览快照生成失败 deptCode={}", deptCode, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "概览快照生成失败：" + ex.getMessage(),
                            "qualityStatus", "unavailable"));
        }
    }

    /**
     * 统计下钻：与卡片同 snapshotId、同口径的命中名单。
     */
    @GetMapping("/snapshots/{snapshotId}/metrics/{metricKey}/items")
    public ResponseEntity<?> metricItems(@PathVariable String snapshotId,
                                         @PathVariable String metricKey) {
        OverviewSnapshot snapshot = overviewService.getSnapshot(snapshotId);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "快照已过期，请刷新后重试", "snapshotId", snapshotId));
        }
        Set<String> patientIds = new HashSet<>(
                snapshot.getMetricPatientIds().getOrDefault(metricKey, List.of()));
        Set<String> departureIds = new HashSet<>(
                snapshot.getMetricDepartureIds().getOrDefault(metricKey, List.of()));
        List<OverviewPatient> patients = snapshot.getPatients().stream()
                .filter(p -> patientIds.contains(p.getId()))
                .collect(Collectors.toList());
        List<DepartureEvent> departures = snapshot.getDepartures().stream()
                .filter(e -> departureIds.contains(e.getId()))
                .collect(Collectors.toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snapshot.getSnapshotId());
        body.put("asOf", snapshot.getAsOf());
        body.put("metricKey", metricKey);
        body.put("patients", patients);
        body.put("departures", departures);
        return ResponseEntity.ok(body);
    }

    /**
     * 单患者交班详情：仅由已确认结构化字段组成，不生成未确认临床结论。
     * 患者必须属于调用方持有的快照——快照即本次授权范围，禁止用患者 id 跨科室/跨范围取数。
     */
    @GetMapping("/patients/{patientId}/handover")
    public ResponseEntity<?> handover(@PathVariable String patientId,
                                      @RequestParam(required = false) String snapshotId) {
        if (snapshotId == null || snapshotId.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "缺少 snapshotId：交班详情只能在已授权的快照范围内读取"));
        }
        OverviewSnapshot snapshot = overviewService.getSnapshot(snapshotId.trim());
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "快照已过期，请刷新后重试", "snapshotId", snapshotId));
        }
        OverviewPatient patient = overviewService.getPatientFromSnapshot(snapshot, patientId);
        if (patient == null) {
            log.warn("交班详情拒绝：患者 {} 不在快照 {} 范围内", patientId, snapshotId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "患者不在该快照范围内", "patientId", patientId));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snapshot.getSnapshotId());
        body.put("asOf", snapshot.getAsOf());
        body.put("scope", snapshot.getScope());
        body.put("patient", patient);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(block("患者摘要", List.of(
                "入科时间：" + (patient.getAdmissionTime() == null ? "缺失" : fmtTime(patient.getAdmissionTime())),
                "入科天数：" + (patient.getIcuDays() == null ? "-" : patient.getIcuDays()),
                "主要诊断：" + blank(patient.getDiagnosis()),
                "入科类型：" + (Boolean.TRUE.equals(patient.getSurgeryAdmission()) ? "手术 "
                        + (Boolean.TRUE.equals(patient.getTransferIn()) ? "+ 转入" : "")
                        : (Boolean.TRUE.equals(patient.getTransferIn()) ? "转入" : "常规")),
                "责任组：" + blank(patient.getMedicalGroup()),
                "主管医生：" + blank(patient.getBedDoctor()),
                "责任护士：" + blank(patient.getResponsibleNurse())
        ), "confirmed"));
        blocks.add(block("当前支持", List.of(
                "呼吸支持：" + (Boolean.TRUE.equals(patient.getVentilator())
                        ? "呼吸机在用" + (patient.getVentMode() == null
                        ? "（模式未采集）"
                        : "（模式 " + patient.getVentMode() + "）")
                        : "无呼吸机在用记录"),
                "CRRT：" + (Boolean.TRUE.equals(patient.getCrrt()) ? "在用" : "无记录"),
                "高级脏器支持：" + (Boolean.TRUE.equals(patient.getOrganSupport())
                        ? blank(patient.getOrganSupportDetail()) : "无记录"),
                "血管活性微泵：" + (Boolean.TRUE.equals(patient.getVasoactive())
                        ? String.join("、", patient.getVasoactiveDrugs() == null
                        ? List.of() : patient.getVasoactiveDrugs()) : "无记录"),
                "详细启停时间：见“本班变化”"
        ), Boolean.TRUE.equals(patient.getVentilator()) ? "confirmed" : "partial"));
        blocks.add(block("风险评估", riskLines(patient), "confirmed"));
        blocks.add(block("危急值", criticalLines(patient),
                patient.getCriticals() == null ? "unavailable" : "confirmed"));
        blocks.add(block("本班变化", changeLines(snapshot, patientId),
                "confirmed"));
        blocks.add(block("下一班行动清单", actionLines(patient), "confirmed"));
        blocks.add(block("应变与交接确认", List.of(
                "正式签署与复述确认不在本屏实现范围"
        ), "unavailable"));
        body.put("blocks", blocks);
        return ResponseEntity.ok(body);
    }

    /** 未闭环危急值 = 下一班必须督办的事项（跨班承接，不因换班消失） */
    private List<String> actionLines(OverviewPatient p) {
        List<String> lines = new ArrayList<>();
        if (p.getCriticals() != null) {
            for (CriticalItem c : p.getCriticals()) {
                if (Boolean.TRUE.equals(c.getHandled())) continue;
                String mark = Boolean.TRUE.equals(c.getCarriedOver()) ? "跨班承接" : "本班新增";
                String age = c.getAgeHours() == null ? "" : "，已 " + c.getAgeHours() + "h";
                lines.add(String.format("[%s] %s %s @ %s%s（源系统标记未闭环）",
                        mark, c.getItem(), c.getValue(), fmtTime(c.getTime()), age));
            }
        }
        if (lines.isEmpty()) {
            lines.add("无未闭环危急值");
        }
        lines.add("其他交班待办数据源尚未接入，请按科室线下待办流程执行");
        return lines;
    }

    private List<String> criticalLines(OverviewPatient p) {
        if (p.getCriticals() == null || p.getCriticals().isEmpty()) {
            return List.of("无未闭环/事件窗内危急值记录");
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format("未闭环 %s 项（跨班承接 %s 项）；本窗已处置 %s 项",
                nvl(p.getCriticalPending()), nvl(p.getCriticalCarried()), nvl(p.getCriticalHandled())));
        for (CriticalItem c : p.getCriticals()) {
            String state = Boolean.TRUE.equals(c.getHandled())
                    ? "已闭环 " + fmtTime(c.getHandleTime())
                    : (Boolean.TRUE.equals(c.getCarriedOver()) ? "跨班承接" : "本班未闭环");
            lines.add(String.format("%s｜%s %s @ %s", state, c.getItem(), c.getValue(), fmtTime(c.getTime())));
        }
        return lines;
    }

    /** 本班变化：只取该患者在该快照事件窗内的记录，与大屏清单同源 */
    private List<String> changeLines(OverviewSnapshot snapshot, String patientId) {
        List<ChangeItem> all = snapshot.getChanges();
        if (all == null || all.isEmpty()) {
            return List.of("事件窗内无已接入的结构化变化记录");
        }
        return all.stream()
                .filter(c -> patientId.equals(c.getPatientId()))
                .map(c -> (fmtTime(c.getTime()) + " " + c.getLabel() + " "
                        + (c.getDetail() == null ? "" : c.getDetail())).trim())
                .collect(Collectors.toList());
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static String fmtTime(Date value) {
        if (value == null) {
            return "-";
        }
        return ZonedDateTime.ofInstant(value.toInstant(), HOSPITAL_ZONE).format(TIME_FMT);
    }

    private List<String> riskLines(OverviewPatient p) {
        List<String> lines = new ArrayList<>();
        lines.add("跌倒：" + riskText(p.getFallLevel(), p.getFallResult(), p.getFallScore(), p.getFallAssessTime()));
        lines.add("压力性损伤：" + riskText(p.getPressureLevel(), p.getPressureResult(), p.getPressureScore(), p.getPressureAssessTime()));
        lines.add("管道：" + levelText(p.getPipeLevel()));
        lines.add("营养：" + levelText(p.getNutritionLevel()));
        lines.add("VTE：" + (p.getVteResult() != null ? p.getVteResult() : levelText(p.getVteLevel())));
        return lines;
    }

    private String riskText(String level, String result, String score, Date time) {
        if ("none".equals(level)) {
            return "未评估（未评估≠低风险）";
        }
        StringBuilder sb = new StringBuilder(result == null ? levelText(level) : result);
        if (score != null && !score.isEmpty()) {
            sb.append("（评分 ").append(score).append("）");
        }
        if (time != null) {
            sb.append(" @ ").append(fmtTime(time));
        }
        return sb.toString();
    }

    private String levelText(String level) {
        if (level == null || "none".equals(level)) {
            return "未评估";
        }
        switch (level) {
            case "high":
                return "高危";
            case "medium":
                return "中危";
            case "low":
                return "低危";
            default:
                return "未知";
        }
    }

    private Map<String, Object> block(String title, List<String> lines, String quality) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("title", title);
        block.put("lines", lines);
        block.put("quality", quality);
        return block;
    }

    private String blank(String value) {
        return value == null || value.trim().isEmpty() ? "未记录" : value;
    }
}
