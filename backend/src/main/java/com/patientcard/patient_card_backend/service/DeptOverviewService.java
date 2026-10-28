package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.ConfigBed;
import com.patientcard.patient_card_backend.model.Department;
import com.patientcard.patient_card_backend.model.overview.DepartureEvent;
import com.patientcard.patient_card_backend.model.overview.ChangeItem;
import com.patientcard.patient_card_backend.model.overview.GroupSummary;
import com.patientcard.patient_card_backend.model.overview.MetricValue;
import com.patientcard.patient_card_backend.model.overview.OverviewPatient;
import com.patientcard.patient_card_backend.model.overview.OverviewScope;
import com.patientcard.patient_card_backend.model.overview.OverviewSnapshot;
import com.patientcard.patient_card_backend.model.patient.RiskAssessment;
import com.patientcard.patient_card_backend.repository.ConfigBedRepository;
import com.patientcard.patient_card_backend.repository.DepartmentRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 科室概览/大交班大屏快照服务。
 * 口径遵循《重症医学科科室概览与大交班大屏需求文档 V1.0》：来源缺失显示不可用，不用 0 掩盖；
 * 当前状态与事件窗两种时间口径分开；同一快照内指标/分组/下钻同源一致。
 */
@Service
public class DeptOverviewService {

    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String UNGROUPED = "未分组";
    /** 本班变化清单单次返回上限，超出记入质量说明而不是静默丢弃 */
    private static final int MAX_CHANGES = 300;

    private final MongoTemplate mongoTemplate;
    private final PatientRiskService riskService;
    private final VteAssessmentService vteService;
    private final DepartmentRepository departmentRepository;
    private final ConfigBedRepository configBedRepository;
    private final CardDetailService cardDetailService;

    @Value("${mongo.collections.patient:patient}")
    private String patientCollection;
    @Value("${mongo.collections.deviceBind:deviceBind}")
    private String deviceBindCollection;
    /** 床旁采集集合（code=param_HuXiMoShi，strVal=呼吸模式，按 pid 关联患者；固定呼吸机不在 deviceBind 中，靠采集识别） */
    @Value("${mongo.collections.bedside:bedside}")
    private String bedsideCollection;
    /** 解绑时间字段：为空/缺失=未解绑；晚于 asOf=查询时刻仍绑定 */
    @Value("${overview.ventilator.unbind-field:unbindTime}")
    private String ventilatorUnbindField;
    /** 呼吸机模式参数码（按顺序作为优先级，取最新非空 strVal） */
    @Value("${overview.ventilator.mode-codes:param_HuXiMoShi,param_vent_mode}")
    private String ventilatorModeCodesRaw;
    /** 床旁采集视为“呼吸机在用”的时间窗（小时）：固定呼吸机无绑定记录，按最近采集时间判定 */
    @Value("${overview.ventilator.capture-window-hours:24}")
    private int ventilatorCaptureWindowHours;
    /** 可选：设备类型字段与取值（留空=表内记录全部计为呼吸机） */
    @Value("${overview.ventilator.type-field:}")
    private String ventilatorTypeField;
    @Value("${overview.ventilator.type-values:}")
    private String ventilatorTypeValues;
    /** 离科事件时间字段（按优先级取第一个非空值） */
    private String[] departureTimeFields;
    @Value("${overview.departure.time-fields:icuDischargeTime,dischargeTime}")
    private String departureTimeFieldsRaw;
    @Value("${overview.refresh-seconds:60}")
    private int refreshSeconds;
    @Value("${overview.snapshot-ttl-seconds:600}")
    private long snapshotTtlSeconds;

    private final Map<String, CacheEntry> snapshotCache = new ConcurrentHashMap<>();

    public DeptOverviewService(MongoTemplate mongoTemplate,
                               PatientRiskService riskService,
                               VteAssessmentService vteService,
                               DepartmentRepository departmentRepository,
                               ConfigBedRepository configBedRepository,
                               CardDetailService cardDetailService) {
        this.mongoTemplate = mongoTemplate;
        this.riskService = riskService;
        this.vteService = vteService;
        this.departmentRepository = departmentRepository;
        this.configBedRepository = configBedRepository;
        this.cardDetailService = cardDetailService;
    }

    public OverviewScope resolveScope(String deptCode) {
        String normalized = normalize(deptCode);
        String deptName = null;
        try {
            deptName = departmentRepository.findAll().stream()
                    .filter(d -> normalized.equals(normalize(d.getCode())))
                    .map(Department::getName)
                    .findFirst().orElse(null);
        } catch (Exception ignored) {
            // 科室名称不可得时降级为编码展示
        }
        if (deptName == null) {
            deptName = normalized;
        }
        return new OverviewScope("icu-" + normalized, null, normalized, deptName,
                List.of(normalized), List.of("read"));
    }

    /**
     * @param windowPreset prevShift=上一已完成班次；last24h=最近 24 小时；
     *                     缺省时若显式给了 eventFrom/eventTo 则按自定义区间，否则回退上一班。
     *                     窗口由后端计算，客户端只选择口径，避免前端时钟偏差进入统计。
     */
    public OverviewSnapshot buildSnapshot(String deptCode, Date eventFrom, Date eventTo, String windowPreset) {
        String normalized = normalize(deptCode);
        Date asOf = new Date();

        String preset = normalizePreset(windowPreset);
        Date[] window;
        String windowLabel;
        if ("last24h".equals(preset)) {
            window = new Date[]{new Date(asOf.getTime() - 24L * 3600_000L), asOf};
            windowLabel = "最近 24 小时";
        } else if ("prevShift".equals(preset)) {
            window = cardDetailService.resolvePrevShiftWindow(normalized, asOf);
            windowLabel = "上一班";
        } else if (eventFrom != null && eventTo != null && eventTo.after(eventFrom)) {
            window = new Date[]{eventFrom, eventTo};
            windowLabel = "自定义区间";
            preset = "custom";
        } else {
            // 默认事件窗 = 上一已完成班次（读 banCiInfoList；无配置回退业务日 8~8）
            window = cardDetailService.resolvePrevShiftWindow(normalized, asOf);
            windowLabel = "上一班";
            preset = "prevShift";
        }
        eventFrom = window[0];
        eventTo = window[1];

        List<String> qualityNotes = new ArrayList<>();

        // 1. 当前在科患者（先定授权范围集合，再做聚合，符合文档 5.2）
        List<Document> docs = mongoTemplate.find(
                new Query(Criteria.where("deptCode").is(normalized).and("status").is("admitted")),
                Document.class, patientCollection);

        List<ConfigBed> beds = safeBeds(normalized);
        Map<String, String> bedDisplay = new HashMap<>();
        for (ConfigBed bed : beds) {
            if (bed.getHisName() != null && bed.getShowName() != null) {
                bedDisplay.put(bed.getHisName(), bed.getShowName());
            }
        }

        Set<String> ids = new HashSet<>();
        Set<String> hisPids = new HashSet<>();
        Map<String, Document> docById = new LinkedHashMap<>();
        for (Document doc : docs) {
            String id = valueAsId(doc.get("_id"));
            if (id == null) {
                continue;
            }
            ids.add(id);
            docById.put(id, doc);
            String hisPid = doc.getString("hisPid");
            if (hisPid != null && !hisPid.trim().isEmpty()) {
                hisPids.add(hisPid.trim());
            }
        }

        // 2. 风险评估（score 集合，valid=true 按 time 最新；类型集合由 PatientRiskService 统一给出）
        String fallType = riskService.getFallScoreType();
        Map<String, Map<String, RiskAssessment>> scoreMap = riskService.findLatestScores(
                ids, riskService.cardScoreTypes());

        // 3. VTE（MySQL 视图）：null=未启用或查询失败 → 指标按不可用展示，不冒充 0
        Map<String, RiskAssessment> vteMap = vteService.findLatestByPatientGuids(hisPids);
        boolean vteAvailable = vteMap != null;
        if (vteMap == null) {
            vteMap = Collections.emptyMap();
        }

        // 4. 呼吸机：deviceBind 未解绑 / 解绑时间晚于 asOf；null=查询失败 → 指标不可用。
        //    固定呼吸机不进 deviceBind，另按 bedside 近窗 param_HuXiMoShi 采集判定“在用”；
        //    模式取 bedside 最新非空 strVal（不限窗口）。bedside 失败只记质量说明，退回仅绑定口径。
        Map<String, String> boundDevices = findActiveVentBinds(ids, hisPids, asOf, qualityNotes);
        boolean ventilatorAvailable = boundDevices != null;
        Set<String> boundPids = boundDevices == null ? Collections.emptySet() : boundDevices.keySet();
        Set<String> ventCandidates = new HashSet<>(ids);
        ventCandidates.addAll(hisPids);
        boolean capturesAvailable;
        VentCaptureInfo ventCaptures = findVentCaptures(ventCandidates, asOf, qualityNotes);
        capturesAvailable = ventCaptures != null;
        if (ventCaptures == null) {
            ventCaptures = new VentCaptureInfo();
        }
        final VentCaptureInfo captures = ventCaptures;

        // 5. 组装患者卡
        List<OverviewPatient> patients = new ArrayList<>();
        int fallUnassessed = 0;
        int pressureUnassessed = 0;
        int fallHigh = 0;
        int pressureHigh = 0;
        int pipeHigh = 0;
        int nutritionHigh = 0;
        int vteHigh = 0;
        int ventilatorCount = 0;
        int newInDept = 0;
        int surgeryCount = 0;
        int transferInCount = 0;

        List<String> fallHighIds = new ArrayList<>();
        List<String> pressureHighIds = new ArrayList<>();
        List<String> ventilatorIds = new ArrayList<>();
        List<String> newInDeptIds = new ArrayList<>();
        List<String> attentionIds = new ArrayList<>();

        for (Document doc : docs) {
            String id = valueAsId(doc.get("_id"));
            if (id == null) {
                continue;
            }
            OverviewPatient p = new OverviewPatient();
            p.setId(id);
            p.setHisPid(doc.getString("hisPid"));
            p.setBed(bedDisplay.getOrDefault(doc.getString("hisBed"),
                    doc.getString("hisBed")));
            p.setName(doc.getString("name"));
            p.setMrn(doc.getString("mrn"));
            p.setTel(doc.getString("tel"));
            p.setAllergy(blankToNull(doc.getString("allergic")));
            p.setDiagnosis(doc.getString("clinicalDiagnosis"));
            p.setMedicalGroup(blankToNull(doc.getString("medicalGroup")));
            p.setBedDoctor(blankToNull(doc.getString("bedDoctor")));
            p.setResponsibleNurse(blankToNull(doc.getString("responsibleNurse")));
            p.setAge(calcAge(doc.getDate("birthday")));
            Object gender = doc.get("gender");
            p.setGender(gender == null ? null : String.valueOf(gender));
            p.setAdmissionTime(doc.getDate("icuAdmissionTime"));
            if (p.getAdmissionTime() != null) {
                p.setIcuDays(calendarDaysInclusive(asOf, p.getAdmissionTime()));
            }
            String admissionType = doc.getString("admissionType");
            if (admissionType == null) {
                admissionType = "";
            }
            admissionType = admissionType.trim();
            boolean isNew = p.getAdmissionTime() != null
                    && !p.getAdmissionTime().before(eventFrom) && p.getAdmissionTime().before(eventTo);
            p.setNewAdmission(isNew);
            p.setSurgeryAdmission("手术".equals(admissionType));
            p.setTransferIn(admissionType.contains("转入"));

            String hisPid = p.getHisPid();
            boolean bound = boundPids.contains(id) || (hisPid != null && boundPids.contains(hisPid));
            // 固定呼吸机无 deviceBind 记录：采集窗内有呼吸模式采集同样计为在用
            boolean captured = captures.recentPids.contains(id)
                    || (hisPid != null && captures.recentPids.contains(hisPid));
            boolean ventilator = bound || captured;
            p.setVentilator(ventilator);
            String ventMode = captures.modes.get(id);
            if (ventMode == null && hisPid != null) {
                ventMode = captures.modes.get(hisPid);
            }
            p.setVentMode(ventMode);

            Map<String, RiskAssessment> scores = scoreMap.getOrDefault(id, Collections.emptyMap());
            RiskAssessment fall = scores.get(fallType);
            RiskAssessment pressure = riskService.pickPressure(scores);
            RiskAssessment pipe = scores.get(riskService.getPipeScoreType());
            RiskAssessment nutrition = scores.get(PatientRiskService.NUTRITION);
            RiskAssessment vte = hisPid == null ? null : vteMap.get(hisPid);

            applyLevel(p, "fall", fall);
            applyLevel(p, "pressure", pressure);
            applyLevel(p, "pipe", pipe);
            applyLevel(p, "nutrition", nutrition);
            if (vte != null) {
                p.setVteLevel(vte.getLevel());
                p.setVteResult(vte.getResult());
            } else {
                p.setVteLevel("none");
            }

            if (fall == null) {
                fallUnassessed++;
                p.setFallLevel("none");
            } else if ("high".equals(p.getFallLevel())) {
                fallHigh++;
                fallHighIds.add(id);
            }
            if (pressure == null) {
                pressureUnassessed++;
                p.setPressureLevel("none");
            } else if ("high".equals(p.getPressureLevel())) {
                pressureHigh++;
                pressureHighIds.add(id);
            }
            if ("high".equals(p.getPipeLevel())) {
                pipeHigh++;
            }
            if ("high".equals(p.getNutritionLevel())) {
                nutritionHigh++;
            }
            if ("high".equals(p.getVteLevel())) {
                vteHigh++;
            }
            if (ventilator) {
                ventilatorCount++;
                ventilatorIds.add(id);
            }
            if (isNew) {
                newInDept++;
                newInDeptIds.add(id);
            }
            if (p.getSurgeryAdmission()) {
                surgeryCount++;
            }
            if (p.getTransferIn()) {
                transferInCount++;
            }
            if (ventilator || isNew || "high".equals(p.getFallLevel()) || "high".equals(p.getPressureLevel())) {
                attentionIds.add(id);
            }
            patients.add(p);
        }
        patients.sort(Comparator.comparing(p -> bedSortValue(p.getBed())));

        // 5.1 高频重症指标（三管/CRRT/ECMO/微泵/出入量/评分/隔离/危急值/抢救/UEX）+ 本班变化清单
        Date[] ioWindow = cardDetailService.resolveBalanceWindow(normalized, asOf);
        Date[] nightWindow = cardDetailService.resolveNightWindow(normalized, asOf);
        List<ChangeItem> changes = new ArrayList<>();
        cardDetailService.enrich(patients, normalized, asOf, ioWindow, nightWindow,
                new Date[]{eventFrom, eventTo}, changes, qualityNotes);

        // 6. 离科事件（事件窗内，按现有字段最佳努力分类）
        List<DepartureEvent> departures = findDepartures(normalized, eventFrom, eventTo, qualityNotes);
        int diedCount = 0, transferredCount = 0, dischargedCount = 0, newDeparted = 0;
        List<String> diedIds = new ArrayList<>();
        List<String> transferredIds = new ArrayList<>();
        List<String> dischargedIds = new ArrayList<>();
        List<String> newDepartedIds = new ArrayList<>();
        for (DepartureEvent e : departures) {
            switch (e.getType()) {
                case "died":
                    diedCount++;
                    diedIds.add(e.getId());
                    break;
                case "transferred":
                    transferredCount++;
                    transferredIds.add(e.getId());
                    break;
                default:
                    dischargedCount++;
                    dischargedIds.add(e.getId());
            }
            if (e.getAdmissionTime() != null
                    && !e.getAdmissionTime().before(eventFrom) && e.getAdmissionTime().before(eventTo)) {
                newDeparted++;
                newDepartedIds.add(e.getId());
            }
        }

        // 7. 分组（medicalGroup；未分组独立成组，合计=在科数）
        List<GroupSummary> groups = buildGroups(patients);

        // 7.1 本班变化清单：入科 + 离科（危急值/评分/管路/设备/微泵已在 enrich 中采集）
        for (OverviewPatient p : patients) {
            if (Boolean.TRUE.equals(p.getNewAdmission())) {
                String detail = (p.getSurgeryAdmission() ? "手术入科" : "")
                        + (p.getTransferIn() ? "转入入科" : "");
                if (detail.isEmpty()) detail = "常规入科";
                changes.add(new ChangeItem(p.getId(), p.getBed(), p.getName(),
                        "admission", "新收", detail, p.getAdmissionTime(), patientCollection, "both"));
            }
        }
        for (DepartureEvent e : departures) {
            changes.add(new ChangeItem(e.getId(), e.getBed(), e.getName(),
                    "departure", e.getTypeLabel(), e.getSourceType(), e.getTime(),
                    patientCollection, "both"));
        }
        changes.sort(Comparator.comparing(ChangeItem::getTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (changes.size() > MAX_CHANGES) {
            qualityNotes.add("本班变化共 " + changes.size() + " 条，仅返回最近 " + MAX_CHANGES + " 条");
            changes = new ArrayList<>(changes.subList(0, MAX_CHANGES));
        }

        // 8. 指标（来源缺失 value=null + unavailable，禁止置 0）
        Date asOfFinal = asOf;
        String asOfText = formatTime(asOf);

        // 危急值口径：未闭环跨班承接（项），本窗已处置（项）
        boolean criticalAvailable = qualityNotes.stream()
                .noneMatch(n -> n.startsWith(CardDetailService.NOTE_CRITICAL_FAILED));
        int criticalPendingTotal = sumInt(patients, OverviewPatient::getCriticalPending);
        int criticalHandledTotal = sumInt(patients, OverviewPatient::getCriticalHandled);
        int criticalCarriedTotal = sumInt(patients, OverviewPatient::getCriticalCarried);
        List<String> criticalPendingIds = patients.stream()
                .filter(p -> p.getCriticalPending() != null && p.getCriticalPending() > 0)
                .map(OverviewPatient::getId).collect(Collectors.toList());
        List<String> criticalCarriedIds = patients.stream()
                .filter(p -> p.getCriticalCarried() != null && p.getCriticalCarried() > 0)
                .map(OverviewPatient::getId).collect(Collectors.toList());
        List<String> criticalHandledIds = patients.stream()
                .filter(p -> p.getCriticalHandled() != null && p.getCriticalHandled() > 0)
                .map(OverviewPatient::getId).collect(Collectors.toList());

        List<MetricValue> metrics = new ArrayList<>();
        metrics.add(metric("inDept", "当前在科人数", patients.size(), "人", "current", "ok", true,
                asOfText, patients.size(), 0, "按科室在科状态去重，同一患者不重复计数"));
        metrics.add(metric("openBeds", "床位总数", beds.size(), "张", "current",
                beds.isEmpty() ? "unavailable" : "partial", false, asOfText,
                beds.size(), 0, "来源 configBed 映射，无开放/停用状态；空列表时视为来源缺失"));
        Integer occupancy = beds.isEmpty() ? null : (int) Math.round(patients.size() * 100.0 / beds.size());
        metrics.add(metric("occupancyPercent", "当前占用比例", occupancy, "%", "current",
                beds.isEmpty() ? "unavailable" : "partial", false, asOfText,
                beds.isEmpty() ? null : patients.size(), 0, "瞬时占用比例，非国家 ICU-01 床位使用率"));
        metrics.add(ventilatorAvailable
                ? metric("ventilator", "呼吸机在用", ventilatorCount, "人", "current",
                capturesAvailable ? "ok" : "partial", true, asOfText,
                ventilatorCount, 0, "deviceBind：" + deviceBindCollection + " 按 " + ventilatorUnbindField
                + " 未解绑/晚于 asOf；固定呼吸机无绑定记录，另按 bedside " + bedsideCollection
                + " 近 " + ventilatorCaptureWindowHours + "h param_HuXiMoShi 采集计入"
                + (capturesAvailable ? "" : "（bedside 查询失败，本次仅按 deviceBind 统计）"))
                : metric("ventilator", "呼吸机在用", null, "人", "current", "unavailable", false, asOfText,
                null, null, "deviceBind 查询失败，指标不可用"));
        metrics.add(metric("fallHigh", "跌倒高风险", fallHigh, "人", "current",
                fallUnassessed > 0 ? "partial" : "ok", true, asOfText, fallHigh, fallUnassessed,
                "未评估 " + fallUnassessed + " 人（未评估≠低风险）"));
        metrics.add(metric("pressureHigh", "压力性损伤高风险（压疮）", pressureHigh, "人", "current",
                pressureUnassessed > 0 ? "partial" : "ok", true, asOfText, pressureHigh, pressureUnassessed,
                "未评估 " + pressureUnassessed + " 人；为高风险人数，非已有损伤人数"));
        int newAdmissionTotal = newInDept + newDeparted;
        metrics.add(metric("newAdmissions", "本窗新收（含已离科）", newAdmissionTotal, "人", "event-window",
                "partial", true, asOfText, newAdmissionTotal, 0,
                "事件窗按 icuAdmissionTime 计算，在科新收 " + newInDept + " 人 + 窗内已离科 " + newDeparted + " 人"));
        metrics.add(metric("pipeHigh", "管道高风险", pipeHigh, "人", "current", "ok", true, asOfText, pipeHigh, 0, ""));
        metrics.add(metric("nutritionHigh", "营养高风险", nutritionHigh, "人", "current", "ok", true, asOfText, nutritionHigh, 0, ""));
        metrics.add(vteAvailable
                ? metric("vteHigh", "VTE高风险", vteHigh, "人", "current", "ok", true, asOfText, vteHigh, 0,
                "来源 VTE 评估视图")
                : metric("vteHigh", "VTE高风险", null, "人", "current", "unavailable", false, asOfText,
                null, null, "VTE 数据源未启用或查询失败，指标不可用"));
        metrics.add(metric("attention", "重点关注（规则生成）", attentionIds.size(), "人", "current",
                "partial", true, asOfText, attentionIds.size(), 0,
                "规则=新收/呼吸机/跌倒高/压疮高 并集；非人工重点标记"));
        metrics.add(metric("transferred", "转出", transferredCount, "人", "event-window", "partial", true,
                asOfText, transferredCount, 0, "离科分类基于死亡时间与离院方式判定，口径待院内核实"));
        metrics.add(metric("discharged", "出院", dischargedCount, "人", "event-window", "partial", true,
                asOfText, dischargedCount, 0, "离科分类基于死亡时间与离院方式判定，口径待院内核实"));
        metrics.add(metric("died", "死亡", diedCount, "人", "event-window", "partial", true, asOfText, diedCount, 0,
                "离科分类基于死亡时间与离院方式判定，口径待院内核实"));
        metrics.add(metric("surgery", "手术入科", surgeryCount, "人", "current", "ok", true, asOfText, surgeryCount, 0, "入科类型=手术"));
        metrics.add(metric("transferIn", "转入入科", transferInCount, "人", "current", "ok", true, asOfText, transferInCount, 0, "入科类型含转入"));
        metrics.add(metric("fallUnassessed", "跌倒未评估", fallUnassessed, "人", "current", "ok", true, asOfText, fallUnassessed, 0, "未评估≠低风险"));
        metrics.add(metric("pressureUnassessed", "压疮未评估", pressureUnassessed, "人", "current", "ok", true, asOfText, pressureUnassessed, 0, "未评估≠低风险"));
        if (criticalAvailable) {
            metrics.add(withAffected(metric("criticalPending", "未闭环危急值", criticalPendingTotal, "项", "current",
                    "ok", true, asOfText, criticalPendingTotal, 0,
                    "判定=criticalValue.status 未闭环；未闭环不受事件窗限制，其中跨班承接 "
                            + criticalCarriedTotal + " 项"), criticalPendingIds.size()));
            metrics.add(withAffected(metric("criticalCarried", "跨班承接未闭环", criticalCarriedTotal, "项", "current",
                    "ok", true, asOfText, criticalCarriedTotal, 0,
                    "发布于事件窗之前且仍未闭环，由上一班承接至本班"), criticalCarriedIds.size()));
            metrics.add(withAffected(metric("criticalHandled", "本窗已处置危急值", criticalHandledTotal, "项", "event-window",
                    "ok", true, asOfText, criticalHandledTotal, 0,
                    "事件窗 " + formatTime(eventFrom) + " ~ " + formatTime(eventTo)
                            + " 内已闭环的危急值"), criticalHandledIds.size()));
        } else {
            metrics.add(metric("criticalPending", "未闭环危急值", null, "项", "current", "unavailable",
                    false, asOfText, null, null, "criticalValue 查询失败，指标不可用"));
            metrics.add(metric("criticalCarried", "跨班承接未闭环", null, "项", "current", "unavailable",
                    false, asOfText, null, null, "criticalValue 查询失败，指标不可用"));
            metrics.add(metric("criticalHandled", "本窗已处置危急值", null, "项", "event-window", "unavailable",
                    false, asOfText, null, null, "criticalValue 查询失败，指标不可用"));
        }
        metrics.add(metric("pendingTasks", "未完成待办", null, "项", "current", "unavailable", false, asOfText,
                null, null, "待办数据源未接入（P1）"));
        metrics.add(metric("keyFlagged", "重点交班（人工标记）", null, "人", "current", "unavailable", false, asOfText,
                null, null, "人工重点标记数据源未接入（P1）；请使用“重点关注（规则生成）”"));

        // 9. 快照与下钻
        OverviewSnapshot snapshot = new OverviewSnapshot();
        snapshot.setSnapshotId(UUID.randomUUID().toString().replace("-", ""));
        snapshot.setAsOf(asOfText);
        snapshot.setEventFrom(eventFrom);
        snapshot.setEventTo(eventTo);
        snapshot.setScope(resolveScope(normalized));
        snapshot.setMetrics(metrics);
        snapshot.setGroups(groups);
        snapshot.setPatients(patients);
        snapshot.setDepartures(departures);
        snapshot.setChanges(changes);
        snapshot.setEventWindowPreset(preset);
        snapshot.setEventWindowLabel(windowLabel);

        Map<String, List<String>> metricPatientIds = new HashMap<>();
        metricPatientIds.put("inDept", patients.stream().map(OverviewPatient::getId).collect(Collectors.toList()));
        metricPatientIds.put("ventilator", ventilatorIds);
        metricPatientIds.put("fallHigh", fallHighIds);
        metricPatientIds.put("pressureHigh", pressureHighIds);
        metricPatientIds.put("newAdmissions", newInDeptIds);
        metricPatientIds.put("attention", attentionIds);
        metricPatientIds.put("surgery", patients.stream().filter(OverviewPatient::getSurgeryAdmission)
                .map(OverviewPatient::getId).collect(Collectors.toList()));
        metricPatientIds.put("transferIn", patients.stream().filter(OverviewPatient::getTransferIn)
                .map(OverviewPatient::getId).collect(Collectors.toList()));
        metricPatientIds.put("pipeHigh", highIds(patients, OverviewPatient::getPipeLevel));
        metricPatientIds.put("nutritionHigh", highIds(patients, OverviewPatient::getNutritionLevel));
        metricPatientIds.put("vteHigh", highIds(patients, OverviewPatient::getVteLevel));
        metricPatientIds.put("fallUnassessed", levelIds(patients, "none", OverviewPatient::getFallLevel));
        metricPatientIds.put("pressureUnassessed", levelIds(patients, "none", OverviewPatient::getPressureLevel));
        metricPatientIds.put("criticalPending", criticalPendingIds);
        metricPatientIds.put("criticalCarried", criticalCarriedIds);
        metricPatientIds.put("criticalHandled", criticalHandledIds);
        snapshot.setMetricPatientIds(metricPatientIds);

        Map<String, List<String>> metricDepartureIds = new HashMap<>();
        metricDepartureIds.put("transferred", transferredIds);
        metricDepartureIds.put("discharged", dischargedIds);
        metricDepartureIds.put("died", diedIds);
        metricDepartureIds.put("newAdmissions", newDepartedIds);
        snapshot.setMetricDepartureIds(metricDepartureIds);

        qualityNotes.add("SSO 后端鉴权未接入：当前按来源校验+只读展示，鉴权为联调项");
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("overall", qualityNotes.isEmpty() ? "ok" : "degraded");
        quality.put("notes", qualityNotes);
        quality.put("eventWindow", formatTime(eventFrom) + " ~ " + formatTime(eventTo) + "（含起点不含终点）");
        quality.put("eventWindowLabel", windowLabel);
        snapshot.setQuality(quality);
        snapshot.setRefreshSeconds(refreshSeconds);
        snapshot.setLongStayDays(cardDetailService.getLongStayDays());
        snapshot.setIoWindowStart(formatTime(ioWindow[0]));
        snapshot.setIoWindowEnd(formatTime(ioWindow[1]));
        snapshot.setNightWindowStart(formatTime(nightWindow[0]));
        snapshot.setNightWindowEnd(formatTime(nightWindow[1]));
        snapshot.setBeds(beds.stream()
                .map(b -> new com.patientcard.patient_card_backend.model.overview.BedSlot(
                        b.getHisName(), b.getShowName()))
                .collect(Collectors.toList()));

        cacheSnapshot(snapshot);
        return snapshot;
    }

    public OverviewSnapshot getSnapshot(String snapshotId) {
        if (snapshotId == null) {
            return null;
        }
        CacheEntry entry = snapshotCache.get(snapshotId);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt < System.currentTimeMillis()) {
            snapshotCache.remove(snapshotId);
            return null;
        }
        return entry.snapshot;
    }

    public OverviewPatient getPatientFromSnapshot(OverviewSnapshot snapshot, String patientId) {
        if (snapshot == null || snapshot.getPatients() == null) {
            return null;
        }
        return snapshot.getPatients().stream()
                .filter(p -> patientId.equals(p.getId()))
                .findFirst().orElse(null);
    }

    /**
     * @return pid → deviceID（活跃呼吸机绑定；deviceID 缺失时 value 为 null，仍计为绑定）
     *         查询失败返回 null（调用方按“指标不可用”处理，不冒充 0）
     */
    private Map<String, String> findActiveVentBinds(Set<String> ids, Set<String> hisPids, Date asOf, List<String> qualityNotes) {
        Map<String, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        Set<String> candidates = new HashSet<>(ids);
        candidates.addAll(hisPids);
        int unparseable = 0;
        try {
            Query query = new Query(Criteria.where("pid").in(candidates));
            List<Document> binds = mongoTemplate.find(query, Document.class, deviceBindCollection);
            for (Document bind : binds) {
                if (hasTypeFilter() && !typeMatches(bind)) {
                    continue;
                }
                Object raw = bind.get(ventilatorUnbindField);
                Object deviceID = bind.get("deviceID");
                String pid = String.valueOf(bind.get("pid"));
                String device = isBlankValue(deviceID) ? null : String.valueOf(deviceID);
                if (isBlankValue(raw)) {
                    // 无解绑时间 = 未解绑
                    result.put(pid, device);
                    continue;
                }
                Date unbindAt = toDate(raw);
                if (unbindAt == null) {
                    unparseable++;
                    continue;
                }
                if (unbindAt.after(asOf)) {
                    result.put(pid, device);
                }
            }
        } catch (Exception ex) {
            qualityNotes.add("deviceBind 查询失败：" + ex.getMessage());
            return null;
        }
        if (unparseable > 0) {
            qualityNotes.add("deviceBind 解绑时间无法解析 " + unparseable + " 条，未计入呼吸机");
        }
        return result;
    }

    /**
     * 床旁呼吸模式采集（bedside，code ∈ mode-codes）：
     * - modes：pid → 最新非空 strVal（不限时间窗，仅表示“采集到过的最新模式”）；
     * - recentPids：采集窗（capture-window-hours，按 asOf 回溯）内有任意采集记录的 pid。
     *   固定呼吸机不进 deviceBind，靠 recentPids 并入“呼吸机在用”。
     * @return 查询失败返回 null（调用方退回仅 deviceBind 口径并记质量说明）
     */
    private VentCaptureInfo findVentCaptures(Set<String> candidates, Date asOf, List<String> qualityNotes) {
        VentCaptureInfo info = new VentCaptureInfo();
        Set<String> modeCodes = Arrays.stream(ventilatorModeCodesRaw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (candidates.isEmpty() || modeCodes.isEmpty()) {
            return info;
        }
        try {
            // 1. 最新非空模式：valid=true，time 倒序，每 pid 取首条非空 strVal
            Criteria modeBase = Criteria.where("pid").in(candidates)
                    .and("code").in(modeCodes).and("valid").is(true)
                    .and("strVal").nin("", null);
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.match(modeBase),
                    Aggregation.sort(Sort.Direction.DESC, "time"),
                    Aggregation.group("pid").first("strVal").as("mode"));
            for (Document d : mongoTemplate.aggregate(agg, bedsideCollection, Document.class)) {
                Object pid = d.get("_id");
                Object mode = d.get("mode");
                if (pid != null && !isBlankValue(mode)) {
                    info.modes.put(String.valueOf(pid), String.valueOf(mode).trim());
                }
            }
            // 2. 采集窗内在用：窗口内有任意有效采集 → 视为呼吸机在用（覆盖固定呼吸机）
            Date windowStart = new Date(asOf.getTime() - ventilatorCaptureWindowHours * 3600_000L);
            Criteria activeBase = Criteria.where("pid").in(candidates)
                    .and("code").in(modeCodes).and("valid").is(true)
                    .and("time").gte(windowStart);
            Query activeQuery = new Query(activeBase);
            activeQuery.fields().include("pid");
            for (Document d : mongoTemplate.find(activeQuery, Document.class, bedsideCollection)) {
                Object pid = d.get("pid");
                if (pid != null) {
                    info.recentPids.add(String.valueOf(pid));
                }
            }
        } catch (Exception ex) {
            qualityNotes.add("bedside 呼吸模式采集查询失败：" + ex.getMessage());
            return null;
        }
        return info;
    }

    /** bedside 呼吸模式采集结果：pid → 最新非空模式；采集窗内在用的 pid 集合 */
    private static class VentCaptureInfo {
        final Map<String, String> modes = new HashMap<>();
        final Set<String> recentPids = new HashSet<>();
    }

    private List<DepartureEvent> findDepartures(String deptCode, Date from, Date to, List<String> qualityNotes) {
        List<DepartureEvent> events = new ArrayList<>();
        if (departureTimeFields == null) {
            departureTimeFields = Arrays.stream(departureTimeFieldsRaw.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
        if (departureTimeFields.length == 0) {
            qualityNotes.add("离科事件时间字段未配置，转出/出院/死亡显示不可用");
            return events;
        }
        try {
            Criteria[] windowEach = Arrays.stream(departureTimeFields)
                    .map(f -> Criteria.where(f).gte(from).lt(to))
                    .toArray(Criteria[]::new);
            Criteria criteria = Criteria.where("deptCode").is(deptCode)
                    .andOperator(new Criteria().orOperator(windowEach));
            List<Document> docs = mongoTemplate.find(new Query(criteria), Document.class, patientCollection);
            for (Document doc : docs) {
                DepartureEvent event = new DepartureEvent();
                event.setId(valueAsId(doc.get("_id")));
                event.setBed(doc.getString("hisBed"));
                event.setName(doc.getString("name"));
                Date time = firstDate(doc, departureTimeFields);
                event.setTime(time);
                event.setAdmissionTime(doc.getDate("icuAdmissionTime"));
                Date deathTime = doc.getDate("deathTime");
                String dischargedType = doc.getString("dischargedType");
                if (deathTime != null) {
                    event.setType("died");
                    event.setTypeLabel("死亡");
                } else if (dischargedType != null && dischargedType.contains("转")) {
                    event.setType("transferred");
                    event.setTypeLabel("转出");
                } else {
                    event.setType("discharged");
                    event.setTypeLabel("出院");
                }
                event.setSourceType(dischargedType);
                events.add(event);
            }
            if (!docs.isEmpty()) {
                qualityNotes.add("离科分类基于 deathTime/dischargedType 最佳努力，转出/出院口径待院内核实");
            }
        } catch (Exception ex) {
            qualityNotes.add("离科事件查询失败：" + ex.getMessage());
        }
        events.sort(Comparator.comparing(DepartureEvent::getTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return events;
    }

    private List<GroupSummary> buildGroups(List<OverviewPatient> patients) {
        Map<String, List<OverviewPatient>> byGroup = new LinkedHashMap<>();
        for (OverviewPatient p : patients) {
            String key = p.getMedicalGroup() == null ? UNGROUPED : p.getMedicalGroup();
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        List<GroupSummary> groups = new ArrayList<>();
        for (Map.Entry<String, List<OverviewPatient>> entry : byGroup.entrySet()) {
            GroupSummary g = new GroupSummary();
            g.setGroupName(entry.getKey());
            List<OverviewPatient> list = entry.getValue();
            g.setCount(list.size());
            g.setDoctors(list.stream().map(OverviewPatient::getBedDoctor)
                    .filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList()));
            g.setVentilatorCount((int) list.stream().filter(p -> Boolean.TRUE.equals(p.getVentilator())).count());
            g.setFallHighCount((int) list.stream().filter(p -> "high".equals(p.getFallLevel())).count());
            g.setPressureHighCount((int) list.stream().filter(p -> "high".equals(p.getPressureLevel())).count());
            g.setPatientIds(list.stream().map(OverviewPatient::getId).collect(Collectors.toList()));
            groups.add(g);
        }
        groups.sort(Comparator.comparing((GroupSummary g) -> UNGROUPED.equals(g.getGroupName()) ? 1 : 0)
                .thenComparing(GroupSummary::getGroupName));
        return groups;
    }

    private void applyLevel(OverviewPatient p, String which, RiskAssessment risk) {
        String level = risk == null ? "none" : (risk.getLevel() == null ? "unknown" : risk.getLevel());
        switch (which) {
            case "fall":
                p.setFallLevel(level);
                if (risk != null) {
                    p.setFallResult(risk.getResult());
                    p.setFallScore(risk.getScore());
                    p.setFallAssessTime(risk.getAssessTime());
                }
                break;
            case "pressure":
                p.setPressureLevel(level);
                if (risk != null) {
                    p.setPressureResult(risk.getResult());
                    p.setPressureScore(risk.getScore());
                    p.setPressureAssessTime(risk.getAssessTime());
                }
                break;
            case "pipe":
                p.setPipeLevel(level);
                break;
            case "nutrition":
                p.setNutritionLevel(level);
                break;
            default:
                break;
        }
    }

    private List<String> highIds(List<OverviewPatient> patients, java.util.function.Function<OverviewPatient, String> getter) {
        return patients.stream().filter(p -> "high".equals(getter.apply(p)))
                .map(OverviewPatient::getId).collect(Collectors.toList());
    }

    private List<String> levelIds(List<OverviewPatient> patients, String level,
                                  java.util.function.Function<OverviewPatient, String> getter) {
        return patients.stream().filter(p -> level.equals(getter.apply(p)))
                .map(OverviewPatient::getId).collect(Collectors.toList());
    }

    private MetricValue metric(String key, String label, Integer value, String unit, String timeBasis,
                               String quality, boolean drill, String asOf,
                               Integer known, Integer unknown, String note) {
        MetricValue m = new MetricValue();
        m.setMetricKey(key);
        m.setLabel(label);
        m.setValue(value);
        m.setUnit(unit);
        m.setTimeBasis(timeBasis);
        m.setQualityStatus(quality);
        m.setDrilldownAvailable(drill);
        m.setAsOf(asOf);
        m.setKnownCount(known);
        m.setUnknownCount(unknown);
        m.setNotApplicableCount(0);
        if (known != null) {
            int total = known + (unknown == null ? 0 : unknown);
            m.setCoverage(total == 0 ? 1.0 : Math.round(known * 1000.0 / total) / 1000.0);
        }
        m.setNote(note);
        return m;
    }

    /** 单位为“项”的指标另注涉及人数，避免“6 项”被读成“6 人” */
    private static MetricValue withAffected(MetricValue m, int affected) {
        m.setAffectedPatientCount(affected);
        if (m.getNote() != null && !m.getNote().isEmpty()) {
            m.setNote(m.getNote() + "；涉及 " + affected + " 人");
        }
        return m;
    }

    private void cacheSnapshot(OverviewSnapshot snapshot) {
        long now = System.currentTimeMillis();
        snapshotCache.entrySet().removeIf(e -> e.getValue().expireAt < now);
        snapshotCache.put(snapshot.getSnapshotId(),
                new CacheEntry(snapshot, now + snapshotTtlSeconds * 1000L));
    }

    private boolean hasTypeFilter() {
        return ventilatorTypeField != null && !ventilatorTypeField.trim().isEmpty();
    }

    private boolean typeMatches(Document bind) {
        Object value = bind.get(ventilatorTypeField);
        if (value == null) {
            return false;
        }
        String actual = String.valueOf(value).trim();
        return Arrays.stream(ventilatorTypeValues.split(","))
                .map(String::trim)
                .anyMatch(v -> v.equalsIgnoreCase(actual));
    }

    private List<ConfigBed> safeBeds(String deptCode) {
        try {
            return configBedRepository.findByDeptCode(deptCode);
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    public static Date[] defaultEventWindow(Date now) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(now.toInstant(), HOSPITAL_ZONE);
        LocalDate start = zdt.toLocalDate();
        if (zdt.toLocalTime().isBefore(LocalTime.of(8, 0))) {
            start = start.minusDays(1);
        }
        ZonedDateTime from = start.atTime(8, 0).atZone(HOSPITAL_ZONE);
        return new Date[]{Date.from(from.toInstant()), now};
    }

    private static int sumInt(List<OverviewPatient> patients,
                              java.util.function.Function<OverviewPatient, Integer> getter) {
        int total = 0;
        for (OverviewPatient p : patients) {
            Integer value = getter.apply(p);
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    /** 归一化客户端事件窗口径；未知取值返回 null，交由默认分支处理 */
    private static String normalizePreset(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if ("prevShift".equalsIgnoreCase(v) || "previousShift".equalsIgnoreCase(v)
                || "上一班".equals(v)) {
            return "prevShift";
        }
        if ("last24h".equalsIgnoreCase(v) || "last24H".equals(v) || "24h".equalsIgnoreCase(v)
                || "最近24小时".equals(v) || "最近 24 小时".equals(v)) {
            return "last24h";
        }
        return null;
    }

    public static Date parseDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String value = raw.trim();
        try {
            return Date.from(java.time.Instant.parse(value));
        } catch (Exception ignored) {
            // fall through
        }
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"};
        for (String pattern : patterns) {
            try {
                return Date.from(java.time.LocalDateTime.parse(value,
                                DateTimeFormatter.ofPattern(pattern))
                        .atZone(HOSPITAL_ZONE).toInstant());
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private static Date firstDate(Document doc, String[] fields) {
        for (String field : fields) {
            Date value = toDate(doc.get(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Date toDate(Object raw) {
        if (raw instanceof Date) {
            return (Date) raw;
        }
        if (raw instanceof Number) {
            return new Date(((Number) raw).longValue());
        }
        if (raw instanceof String && !((String) raw).trim().isEmpty()) {
            String text = ((String) raw).trim();
            try {
                return Date.from(java.time.Instant.parse(text));
            } catch (Exception ignored) {
                // fall through
            }
            for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"}) {
                try {
                    return Date.from(java.time.LocalDateTime.parse(text,
                                    DateTimeFormatter.ofPattern(pattern))
                            .atZone(HOSPITAL_ZONE).toInstant());
                } catch (Exception ignored) {
                    // try next
                }
            }
        }
        return null;
    }

    private static boolean isBlankValue(Object raw) {
        if (raw == null) {
            return true;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text);
    }

    private static int bedSortValue(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private static Integer calcAge(Date birthday) {
        if (birthday == null) {
            return null;
        }
        LocalDate birth = birthday.toInstant().atZone(HOSPITAL_ZONE).toLocalDate();
        LocalDate today = LocalDate.now(HOSPITAL_ZONE);
        int age = today.getYear() - birth.getYear();
        if (today.getMonthValue() < birth.getMonthValue()
                || (today.getMonthValue() == birth.getMonthValue() && today.getDayOfMonth() < birth.getDayOfMonth())) {
            age--;
        }
        return Math.max(0, age);
    }

    /**
     * 入科天数：按自然日、含当天计。当天入科 = 1 天。
     */
    private static int calendarDaysInclusive(Date asOf, Date admission) {
        if (asOf == null || admission == null) {
            return 1;
        }
        LocalDate asOfDay = ZonedDateTime.ofInstant(asOf.toInstant(), HOSPITAL_ZONE).toLocalDate();
        LocalDate admitDay = ZonedDateTime.ofInstant(admission.toInstant(), HOSPITAL_ZONE).toLocalDate();
        long days = java.time.temporal.ChronoUnit.DAYS.between(admitDay, asOfDay) + 1;
        return (int) Math.max(1, days);
    }

    private static String valueAsId(Object value) {
        if (value instanceof ObjectId) {
            return ((ObjectId) value).toHexString();
        }
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static String normalize(String deptCode) {
        return deptCode == null ? "" : deptCode.trim();
    }

    private static String formatTime(Date date) {
        if (date == null) {
            return null;
        }
        ZonedDateTime zdt = ZonedDateTime.ofInstant(date.toInstant(), HOSPITAL_ZONE);
        return zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static final class CacheEntry {
        final OverviewSnapshot snapshot;
        final long expireAt;

        CacheEntry(OverviewSnapshot snapshot, long expireAt) {
            this.snapshot = snapshot;
            this.expireAt = expireAt;
        }
    }
}
