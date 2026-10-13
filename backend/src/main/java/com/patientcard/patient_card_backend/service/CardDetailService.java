package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.overview.ChangeItem;
import com.patientcard.patient_card_backend.model.overview.CriticalItem;
import com.patientcard.patient_card_backend.model.overview.IsolationTag;
import com.patientcard.patient_card_backend.model.overview.OverviewPatient;
import com.patientcard.patient_card_backend.model.overview.TubeChip;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 患者卡片高频重症指标聚合：
 * 三管留置天数（tubeExe）、高级脏器支持（tubeExe ECMO/IABP endTime 为空）、
 * CRRT（deviceBind crrt）、血管活性微泵（drugExe working）、
 * 24h 出入量（configLiquidBalance + bedside，窗口来自 initSystemConfig.banCiInfoList，不写死）、
 * RASS/GCS/SOFA/APACHE（score，含趋势差值）、
 * 隔离角标（DataCenter VI_ICU_ZYYZ 医嘱启停）、夜间危急值（criticalValue 已处置/待闭环）、
 * 夜间抢救回溯（ZYYZ 医嘱）、UEX 高危联动（RASS+1~2 且气管插管在用）。
 * 任何来源失败只追加质量说明，不影响主列表。
 */
@Service
public class CardDetailService {

    public static final String NOTE_CRITICAL_FAILED = "criticalValue 危急值查询失败";

    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CENTER_COLLECTION_ORDERS = "VI_ICU_ZYYZ";
    private static final Map<String, String> VASO_ALIAS = new HashMap<>();

    static {
        VASO_ALIAS.put("去甲肾上腺素", "去甲NE");
        VASO_ALIAS.put("垂体后叶素", "垂体");
        VASO_ALIAS.put("多巴酚丁胺", "多巴酚丁");
        VASO_ALIAS.put("血管加压素", "加压素");
        VASO_ALIAS.put("去氧肾上腺素", "苯肾");
        VASO_ALIAS.put("异丙肾上腺素", "异丙肾");
    }

    private final MongoTemplate mongoTemplate;
    private final ObjectProvider<MongoTemplate> centerProvider;

    @Value("${mongo.collections.score:score}")
    private String scoreCollection;
    @Value("${mongo.collections.deviceBind:deviceBind}")
    private String deviceBindCollection;
    @Value("${overview.ventilator.unbind-field:unbindTime}")
    private String ventilatorUnbindField;

    @Value("${risk.score-types.rass:rass}")
    private String rassType;
    @Value("${risk.score-types.gcs:gcsScore}")
    private String gcsType;
    @Value("${risk.score-types.sofa:sofa}")
    private String sofaType;
    @Value("${risk.score-types.apache:apacheII}")
    private String apacheType;

    @Value("${overview.ventilator.type-field:type}")
    private String deviceTypeField;
    @Value("${overview.support.crrt-values:crrt}")
    private String crrtValues;

    @Value("${overview.organ-support-types:ECMO,IABP}")
    private String organSupportTypes;
    @Value("${overview.tube.airway-types:气管插管,气切套管}")
    private String airwayTypes;
    @Value("${overview.tube.cvc-types:中心静脉导管,PICC管}")
    private String cvcTypes;
    @Value("${overview.tube.urine-types:尿管}")
    private String urineTypes;
    @Value("${overview.tube.airway-warn-days:7}")
    private int airwayWarnDays;
    @Value("${overview.tube.airway-crit-days:14}")
    private int airwayCritDays;
    @Value("${overview.tube.cvc-crit-days:14}")
    private int cvcCritDays;
    @Value("${overview.tube.urine-warn-days:7}")
    private int urineWarnDays;

    @Value("${overview.vasoactive.keywords:去甲肾上腺素,去甲,垂体后叶素,多巴胺,多巴酚丁胺,肾上腺素,血管加压素,去氧肾上腺素,异丙肾上腺素,米力农,硝普钠,硝酸甘油,乌拉地尔,尼卡地平,酚妥拉明,艾司洛尔,拉贝洛尔}")
    private String vasoKeywords;

    @Value("${overview.io.in-codes:param_kouFu,param_biSi,param_YaoYeti_in_hour,param_YaoStomach_in_hour,param_YaoOther_in_hour,param_food_shiWu,param_饮食,param_YaoShuXue_in_hour,param_YaoSpecial_in_hour}")
    private String ioInCodes;
    @Value("${overview.io.out-codes:param_niaoLiang,param_daBianAmount,param_weiyeliang,param_outuwuliang,param_ylg,param_chaoLvLiang,param_恶露,param_咯血,param_渗液}")
    private String ioOutCodes;
    @Value("${overview.io.urine-code:param_niaoLiang}")
    private String ioUrineCode;

    @Value("${overview.night.start-hour:20}")
    private int nightStartHour;
    @Value("${overview.night.end-hour:8}")
    private int nightEndHour;

    @Value("${overview.rescue-keywords:除颤,心肺复苏,抢救}")
    private String rescueKeywords;

    @Value("${overview.los.long-stay-days:14}")
    private int longStayDays;

    public CardDetailService(MongoTemplate mongoTemplate,
                             @Qualifier("centerMongoTemplate") ObjectProvider<MongoTemplate> centerProvider) {
        this.mongoTemplate = mongoTemplate;
        this.centerProvider = centerProvider;
    }

    public int getLongStayDays() {
        return longStayDays;
    }

    // ================= 时间窗（读班次配置，不写死） =================

    /**
     * 上一已完成班次窗口：遍历 banCiInfoList 各班，取“最近一个完整发生期”里结束最晚者。
     * 例：14:00 时 AP班(8-20)未完，N班(20-8)已于 08:00 结束 → 上一班=昨20:00~今08:00。
     * 无班次配置时回退业务日 8~8。
     */
    public Date[] resolvePrevShiftWindow(String deptCode, Date asOf) {
        List<Document> shifts = readBanCiList(deptCode);
        Date[] best = null;
        Date bestEnd = null;
        if (shifts != null) {
            for (Document shift : shifts) {
                Integer start = asInt(shift.get("banCiStartHour"));
                Integer end = asInt(shift.get("banCiEndHour"));
                if (start == null || end == null) continue;
                Date[] w = latestCompleteWindow(asOf, start, end);
                if (bestEnd == null || w[1].after(bestEnd)) {
                    best = w;
                    bestEnd = w[1];
                }
            }
        }
        if (best != null) {
            return best;
        }
        return latestCompleteWindow(asOf, 8, 8);
    }

    private List<Document> readBanCiList(String deptCode) {
        try {
            Document doc = mongoTemplate.findOne(
                    Query.query(Criteria.where("deptCode").is(deptCode)),
                    Document.class, "initSystemConfig");
            if (doc == null) {
                doc = mongoTemplate.findOne(new Query(), Document.class, "initSystemConfig");
            }
            return doc == null ? null : (List<Document>) doc.get("banCiInfoList");
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 出入量平衡窗：取 initSystemConfig.banCiInfoList 中“全天”班（start==end 或名含全天）的
     * balanceStartHour/balanceEndHour，取最近一个**完整**的平衡日；缺配置回退
     * oneDayBalanceCalStartHour/EndHour，再回退 8~8。
     */
    public Date[] resolveBalanceWindow(String deptCode, Date asOf) {
        int startHour = 8;
        int endHour = 8;
        try {
            Document doc = mongoTemplate.findOne(
                    Query.query(Criteria.where("deptCode").is(deptCode)),
                    Document.class, "initSystemConfig");
            if (doc == null) {
                doc = mongoTemplate.findOne(new Query(), Document.class, "initSystemConfig");
            }
            if (doc != null) {
                Integer s = null, e = null;
                List<Document> shifts = (List<Document>) doc.get("banCiInfoList");
                if (shifts != null) {
                    for (Document shift : shifts) {
                        String name = String.valueOf(shift.get("name"));
                        Integer bs = asInt(shift.get("banCiStartHour"));
                        Integer be = asInt(shift.get("banCiEndHour"));
                        boolean fullDay = (bs != null && be != null && bs.equals(be))
                                || name.contains("全天");
                        if (fullDay) {
                            s = asInt(shift.get("balanceStartHour"));
                            e = asInt(shift.get("balanceEndHour"));
                            break;
                        }
                    }
                }
                if (s == null) s = asInt(doc.get("oneDayBalanceCalStartHour"));
                if (e == null) e = asInt(doc.get("oneDayBalanceCalEndHour"));
                if (s != null) startHour = s;
                if (e != null) endHour = e;
            }
        } catch (Exception ex) {
            // 配置不可得时按 8~8 兜底，交由质量说明提示
        }
        return latestCompleteWindow(asOf, startHour, endHour);
    }

    /**
     * 夜班事件窗：banCiInfoList 中跨午夜班次（start>end，如 N班 20→8）最近一次发生；
     * 无配置回退 overview.night.* (20→8)。窗口为 [occStart, min(asOf, occEnd))。
     */
    public Date[] resolveNightWindow(String deptCode, Date asOf) {
        int startHour = nightStartHour;
        int endHour = nightEndHour;
        try {
            Document doc = mongoTemplate.findOne(
                    Query.query(Criteria.where("deptCode").is(deptCode)),
                    Document.class, "initSystemConfig");
            if (doc != null) {
                List<Document> shifts = (List<Document>) doc.get("banCiInfoList");
                if (shifts != null) {
                    for (Document shift : shifts) {
                        Integer bs = asInt(shift.get("banCiStartHour"));
                        Integer be = asInt(shift.get("banCiEndHour"));
                        if (bs != null && be != null && bs > be) {
                            startHour = bs;
                            endHour = be;
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 回退默认夜班窗口
        }
        int duration = (endHour - startHour + 24) % 24;
        if (duration <= 0) duration = 12;
        ZonedDateTime now = ZonedDateTime.ofInstant(asOf.toInstant(), HOSPITAL_ZONE);
        ZonedDateTime occStart = now.toLocalDate().atTime(startHour, 0).atZone(HOSPITAL_ZONE);
        if (occStart.isAfter(now)) occStart = occStart.minusDays(1);
        ZonedDateTime occEnd = occStart.plusHours(duration);
        Date to = occEnd.isAfter(now) ? asOf : Date.from(occEnd.toInstant());
        return new Date[]{Date.from(occStart.toInstant()), to};
    }

    private Date[] latestCompleteWindow(Date asOf, int startHour, int endHour) {
        int duration = (endHour - startHour + 24) % 24;
        if (duration == 0) duration = 24;
        ZonedDateTime now = ZonedDateTime.ofInstant(asOf.toInstant(), HOSPITAL_ZONE);
        ZonedDateTime cand = now.toLocalDate().atTime(startHour, 0).atZone(HOSPITAL_ZONE);
        if (cand.isAfter(now)) cand = cand.minusDays(1);
        while (cand.plusHours(duration).isAfter(now)) {
            cand = cand.minusDays(1);
        }
        return new Date[]{Date.from(cand.toInstant()), Date.from(cand.plusHours(duration).toInstant())};
    }

    // ================= 主入口 =================

    /**
     * @param eventWindow 交班事件窗 [from, to)，危急值承接判定与本班变化清单共用同一窗口
     * @param changes     本班变化清单收集器（可为 null，表示本次只做卡片聚合）
     */
    public void enrich(List<OverviewPatient> patients, String deptCode, Date asOf,
                       Date[] balanceWindow, Date[] nightWindow, Date[] eventWindow,
                       List<ChangeItem> changes, List<String> qualityNotes) {
        if (patients == null || patients.isEmpty()) {
            return;
        }
        Set<String> ids = patients.stream().map(OverviewPatient::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> hisPids = patients.stream().map(OverviewPatient::getHisPid)
                .filter(v -> v != null && !v.trim().isEmpty())
                .map(String::trim).collect(Collectors.toSet());
        Map<String, OverviewPatient> byId = new HashMap<>();
        Map<String, OverviewPatient> byHis = new HashMap<>();
        for (OverviewPatient p : patients) {
            byId.put(p.getId(), p);
            if (p.getHisPid() != null) byHis.put(p.getHisPid(), p);
        }

        enrichTubes(patients, ids, asOf, qualityNotes);
        enrichCrrt(ids, hisPids, asOf, byId, byHis, qualityNotes);
        enrichVasoactive(ids, byId, qualityNotes);
        enrichScores(ids, byId, eventWindow, changes, qualityNotes);
        enrichFluids(ids, deptCode, balanceWindow, byId, qualityNotes);
        enrichCriticals(hisPids, eventWindow, asOf, byId, byHis, changes, qualityNotes);
        enrichTubeChanges(ids, byId, eventWindow, changes, qualityNotes);
        enrichDeviceChanges(ids, hisPids, byId, byHis, eventWindow, changes, qualityNotes);
        enrichVasoactiveChanges(ids, byId, eventWindow, changes, qualityNotes);
        enrichIsolationAndRescue(deptCode, hisPids, nightWindow, byId, byHis, qualityNotes);

        // UEX 联动：RASS +1~+2 且气管插管在用 → 自动高危
        for (OverviewPatient p : patients) {
            boolean airway = Boolean.TRUE.equals(p.getAirwayInUse());
            Integer rass = p.getRass();
            p.setUexHigh(airway && rass != null && rass >= 1 && rass <= 2);
        }
    }

    private static void addChange(List<ChangeItem> changes, OverviewPatient p, String kind,
                                  String label, String detail, Date time, String source, String scope) {
        if (changes == null || p == null || time == null) return;
        changes.add(new ChangeItem(p.getId(), p.getBed(), p.getName(),
                kind, label, detail, time, source, scope));
    }

    private static boolean inWindow(Date time, Date[] window) {
        return time != null && window != null && !time.before(window[0]) && time.before(window[1]);
    }

    // ================= 三管 / 高级脏器支持 =================

    private void enrichTubes(List<OverviewPatient> patients, Set<String> ids, Date asOf,
                             List<String> qualityNotes) {
        Map<String, List<Document>> byPid = new HashMap<>();
        try {
            Query q = new Query(Criteria.where("pid").in(ids)
                    .and("endTime").is(null)
                    .and("startTime").lte(asOf));
            for (Document d : mongoTemplate.find(q, Document.class, "tubeExe")) {
                byPid.computeIfAbsent(str(d.get("pid")), k -> new ArrayList<>()).add(d);
            }
        } catch (Exception ex) {
            qualityNotes.add("tubeExe 管路查询失败：" + ex.getMessage());
            return;
        }

        Set<String> airwaySet = split(airwayTypes);
        Set<String> cvcSet = split(cvcTypes);
        Set<String> urineSet = split(urineTypes);
        Set<String> organSet = split(organSupportTypes);

        for (OverviewPatient p : patients) {
            List<Document> tubes = byPid.getOrDefault(p.getId(), Collections.emptyList());
            List<TubeChip> chips = new ArrayList<>();
            boolean airway = false;
            List<String> organDetail = new ArrayList<>();
            Map<String, Document> earliest = new HashMap<>(); // groupKey -> earliest tube

            for (Document t : tubes) {
                String type = str(t.get("type"));
                Date start = toDate(t.get("startTime"));
                if (type == null || start == null) continue;
                int days = daysBetween(start, asOf);

                String group = null;
                String label = null;
                if (airwaySet.contains(type)) {
                    group = "airway";
                    label = "气道";
                    airway = true;
                } else if (cvcSet.contains(type)) {
                    group = "cvc";
                    label = "PICC管".equals(type) ? "PICC" : "CVC";
                } else if (urineSet.contains(type)) {
                    group = "urine";
                    label = "尿管";
                } else if (organSet.contains(type)) {
                    organDetail.add(type);
                }
                if (group == null) continue;
                Document prev = earliest.get(group);
                if (prev == null || days > daysBetween(toDate(prev.get("startTime")), asOf)) {
                    earliest.put(group, t);
                }
            }

            // 依据组内最早置管生成角标
            for (Map.Entry<String, Document> en : earliest.entrySet()) {
                Document t = en.getValue();
                String type = str(t.get("type"));
                Date start = toDate(t.get("startTime"));
                int days = daysBetween(start, asOf);
                String label;
                String level = "low";
                String title;
                switch (en.getKey()) {
                    case "airway":
                        label = "气切".equals(type) ? "气切" : "气道";
                        if (days >= airwayCritDays) level = "crit";
                        else if (days >= airwayWarnDays) level = "warn";
                        title = label + "（" + type + "）留置 " + days + " 天，自 " + fmt(start)
                                + (level.equals("crit") ? "，已达高危阈值，评估气切/脱机"
                                : level.equals("warn") ? "，≥" + airwayWarnDays + " 天，关注VAP/脱机评估" : "");
                        break;
                    case "cvc":
                        label = "PICC管".equals(type) ? "PICC" : "CVC";
                        if (days > cvcCritDays) level = "crit";
                        title = label + " 留置 " + days + " 天，自 " + fmt(start)
                                + (level.equals("crit") ? "，>" + cvcCritDays + " 天，警惕CRBSI，交接穿刺点" : "");
                        break;
                    default:
                        label = "尿管";
                        if (days >= urineWarnDays) level = "warn";
                        title = "尿管留置 " + days + " 天，自 " + fmt(start)
                                + (level.equals("warn") ? "，评估CAUTI与拔管指征" : "");
                        break;
                }
                chips.add(new TubeChip(label, days, level, title));
            }
            chips.sort(Comparator.comparing(TubeChip::getLabel));
            p.setTubes(chips.isEmpty() ? null : chips);
            p.setAirwayInUse(airway);
            if (!organDetail.isEmpty()) {
                p.setOrganSupport(true);
                p.setOrganSupportDetail(String.join("/", organDetail));
            } else {
                p.setOrganSupport(false);
            }
        }
    }

    // ================= CRRT（deviceBind type=crrt 且未解绑） =================

    private void enrichCrrt(Set<String> ids, Set<String> hisPids, Date asOf,
                            Map<String, OverviewPatient> byId, Map<String, OverviewPatient> byHis,
                            List<String> qualityNotes) {
        Set<String> crrtSet = split(crrtValues);
        if (crrtSet.isEmpty()) return;
        try {
            Set<String> candidates = new HashSet<>(ids);
            candidates.addAll(hisPids);
            Query q = new Query(Criteria.where("pid").in(candidates));
            for (Document d : mongoTemplate.find(q, Document.class, deviceBindCollection)) {
                String type = str(d.get(deviceTypeField));
                if (type == null || !crrtSet.contains(type.trim())) continue;
                if (!boundAt(d, asOf, qualityNotes, deviceBindCollection)) continue;
                String pid = str(d.get("pid"));
                OverviewPatient p = byId.get(pid);
                if (p == null) p = byHis.get(pid);
                if (p != null) p.setCrrt(true);
            }
        } catch (Exception ex) {
            qualityNotes.add("deviceBind CRRT 查询失败：" + ex.getMessage());
        }
    }

    // ================= 血管活性微泵（drugExe status=working） =================

    private void enrichVasoactive(Set<String> ids, Map<String, OverviewPatient> byId,
                                  List<String> qualityNotes) {
        List<String> keywords = splitOrdered(vasoKeywords);
        if (keywords.isEmpty()) return;
        try {
            Query q = new Query(Criteria.where("pid").in(ids).and("status").is("working"));
            for (Document d : mongoTemplate.find(q, Document.class, "drugExe")) {
                OverviewPatient p = byId.get(str(d.get("pid")));
                if (p == null) continue;
                List<Document> drugs = (List<Document>) d.get("drugList");
                if (drugs == null) continue;
                List<String> names = drugs.stream()
                        .map(dr -> str(dr.get("name")))
                        .filter(Objects::nonNull).collect(Collectors.toList());
                List<String> hits = new ArrayList<>();
                for (String kw : keywords) {
                    if (kw.trim().isEmpty()) continue;
                    if (names.stream().anyMatch(n -> n.contains(kw))) {
                        String alias = VASO_ALIAS.getOrDefault(kw, kw);
                        if (!hits.contains(alias)) hits.add(alias);
                    }
                }
                if (!hits.isEmpty()) {
                    p.setVasoactive(true);
                    if (p.getVasoactiveDrugs() == null) p.setVasoactiveDrugs(new ArrayList<>());
                    for (String h : hits) {
                        if (!p.getVasoactiveDrugs().contains(h)) p.getVasoactiveDrugs().add(h);
                    }
                }
            }
        } catch (Exception ex) {
            qualityNotes.add("drugExe 微泵查询失败：" + ex.getMessage());
        }
    }

    // ================= RASS / GCS / SOFA / APACHE（含趋势） =================

    private void enrichScores(Set<String> ids, Map<String, OverviewPatient> byId,
                              Date[] eventWindow, List<ChangeItem> changes, List<String> qualityNotes) {
        try {
            applyScore(ids, byId, rassType, "RASS", "both", eventWindow, changes, (p, cur, prev, time) -> {
                p.setRass(cur);
                p.setRassTime(time);
            });
            applyScore(ids, byId, gcsType, "GCS", "both", eventWindow, changes, (p, cur, prev, time) -> {
                p.setGcs(cur);
                p.setGcsTime(time);
            });
            applyScore(ids, byId, sofaType, "SOFA", "doctor", eventWindow, changes, (p, cur, prev, time) -> {
                p.setSofa(cur);
                p.setSofaTime(time);
                p.setSofaDelta(prev == null ? null : cur - prev);
            });
            applyScore(ids, byId, apacheType, "APACHE II", "doctor", eventWindow, changes, (p, cur, prev, time) -> {
                p.setApacheII(cur);
                p.setApacheTime(time);
                p.setApacheDelta(prev == null ? null : cur - prev);
            });
        } catch (Exception ex) {
            qualityNotes.add("score 评分查询失败：" + ex.getMessage());
        }
    }

    private interface ScoreApplier {
        void apply(OverviewPatient p, Integer cur, Integer prev, Date time);
    }

    private void applyScore(Set<String> ids, Map<String, OverviewPatient> byId,
                            String scoreType, String label, String scope,
                            Date[] eventWindow, List<ChangeItem> changes, ScoreApplier applier) {
        if (ids.isEmpty() || scoreType == null || scoreType.trim().isEmpty()) return;
        Query q = new Query(Criteria.where("pid").in(ids)
                .and("scoreType").is(scoreType).and("valid").is(true));
        q.with(Sort.by(Sort.Direction.DESC, "time"));
        Map<String, List<Document>> topTwo = new HashMap<>();
        for (Document d : mongoTemplate.find(q, Document.class, scoreCollection)) {
            String pid = str(d.get("pid"));
            List<Document> list = topTwo.computeIfAbsent(pid, k -> new ArrayList<>());
            if (list.size() < 2) list.add(d);
        }
        for (Map.Entry<String, List<Document>> en : topTwo.entrySet()) {
            OverviewPatient p = byId.get(en.getKey());
            if (p == null) continue;
            Document curDoc = en.getValue().get(0);
            Integer cur = asInt(curDoc.get("total"));
            Integer prev = en.getValue().size() > 1 ? asInt(en.getValue().get(1).get("total")) : null;
            if (cur == null) continue;
            Date time = toDate(curDoc.get("time"));
            applier.apply(p, cur, prev, time);
            // 本班变化：只收有变化的评分（与上一次相同视为无变化，不上清单）
            if (inWindow(time, eventWindow)) {
                String detail;
                if (prev == null) {
                    detail = label + " " + cur + "（首次记录，无上一次可比）";
                } else if (cur.equals(prev)) {
                    detail = null;
                } else {
                    detail = label + " " + prev + " → " + cur
                            + (cur > prev ? "（↑" + (cur - prev) + "）"
                            : "（↓" + (prev - cur) + "）");
                }
                if (detail != null) {
                    addChange(changes, p, "score", "评分变化", detail, time, scoreCollection, scope);
                }
            }
        }
    }

    // ================= 本班变化清单：管路 / 设备 / 微泵 =================

    /** 窗内置入的管路（含已拔除，故单独查询，不复用“当前在用”结果） */
    private void enrichTubeChanges(Set<String> ids, Map<String, OverviewPatient> byId,
                                   Date[] window, List<ChangeItem> changes, List<String> qualityNotes) {
        if (ids.isEmpty() || window == null || changes == null) return;
        try {
            Query q = new Query(Criteria.where("pid").in(ids)
                    .and("startTime").gte(window[0]).lt(window[1]));
            for (Document d : mongoTemplate.find(q, Document.class, "tubeExe")) {
                OverviewPatient p = byId.get(str(d.get("pid")));
                if (p == null) continue;
                String type = str(d.get("type"));
                String size = str(d.get("size"));
                addChange(changes, p, "tube", "管路置入",
                        type + (size == null ? "" : " " + size),
                        toDate(d.get("startTime")), "tubeExe", "nurse");
            }
        } catch (Exception ex) {
            qualityNotes.add("tubeExe 变化查询失败：" + ex.getMessage());
        }
    }

    /** 窗内设备接入/解绑（呼吸机、CRRT），用于交班说明支持变化 */
    private void enrichDeviceChanges(Set<String> ids, Set<String> hisPids,
                                     Map<String, OverviewPatient> byId, Map<String, OverviewPatient> byHis,
                                     Date[] window, List<ChangeItem> changes, List<String> qualityNotes) {
        if (window == null || changes == null) return;
        Set<String> candidates = new HashSet<>(ids);
        candidates.addAll(hisPids);
        if (candidates.isEmpty()) return;
        Set<String> crrtSet = split(crrtValues);
        try {
            Query q = new Query(Criteria.where("pid").in(candidates));
            for (Document d : mongoTemplate.find(q, Document.class, deviceBindCollection)) {
                OverviewPatient p = byId.get(str(d.get("pid")));
                if (p == null) p = byHis.get(str(d.get("pid")));
                if (p == null) continue;
                String type = str(d.get(deviceTypeField));
                if (type == null || (!crrtSet.contains(type.trim())
                        && !"vent".equalsIgnoreCase(type.trim()))) continue;
                String deviceLabel = crrtSet.contains(type.trim()) ? "CRRT" : "呼吸机";
                Date bindTime = toDate(d.get("bindTime"));
                if (inWindow(bindTime, window)) {
                    addChange(changes, p, "device", "支持接入", deviceLabel + "接入",
                            bindTime, deviceBindCollection, "both");
                }
                Date unbind = toDate(d.get(ventilatorUnbindField));
                if (inWindow(unbind, window)) {
                    addChange(changes, p, "device", "支持解除", deviceLabel + "解绑",
                            unbind, deviceBindCollection, "both");
                }
            }
        } catch (Exception ex) {
            qualityNotes.add("deviceBind 变化查询失败：" + ex.getMessage());
        }
    }

    /** 窗内启用的血管活性微泵（药物关键词与卡片角标同一套配置） */
    private void enrichVasoactiveChanges(Set<String> ids, Map<String, OverviewPatient> byId,
                                         Date[] window, List<ChangeItem> changes, List<String> qualityNotes) {
        if (ids.isEmpty() || window == null || changes == null) return;
        List<String> keywords = splitOrdered(vasoKeywords);
        if (keywords.isEmpty()) return;
        try {
            Query q = new Query(Criteria.where("pid").in(ids)
                    .and("startTime").gte(window[0]).lt(window[1]));
            for (Document d : mongoTemplate.find(q, Document.class, "drugExe")) {
                OverviewPatient p = byId.get(str(d.get("pid")));
                if (p == null) continue;
                List<Document> drugs = (List<Document>) d.get("drugList");
                if (drugs == null) continue;
                List<String> names = drugs.stream()
                        .map(dr -> str(dr.get("name")))
                        .filter(Objects::nonNull).collect(Collectors.toList());
                List<String> hits = new ArrayList<>();
                for (String kw : keywords) {
                    if (kw.trim().isEmpty()) continue;
                    if (names.stream().anyMatch(n -> n.contains(kw))) {
                        String alias = VASO_ALIAS.getOrDefault(kw, kw);
                        if (!hits.contains(alias)) hits.add(alias);
                    }
                }
                if (!hits.isEmpty()) {
                    addChange(changes, p, "drug", "微泵启用",
                            String.join("/", hits), toDate(d.get("startTime")),
                            "drugExe", "both");
                }
            }
        } catch (Exception ex) {
            qualityNotes.add("drugExe 变化查询失败：" + ex.getMessage());
        }
    }

    // ================= 24h 出入量（班次窗 + configLiquidBalance 码表） =================

    private void enrichFluids(Set<String> ids, String deptCode, Date[] window,
                              Map<String, OverviewPatient> byId, List<String> qualityNotes) {
        if (ids.isEmpty() || window == null) return;
        Set<String> inCodes = new HashSet<>(split(ioInCodes));
        Set<String> outCodes = new HashSet<>(split(ioOutCodes));
        String urineCode = ioUrineCode == null ? "" : ioUrineCode.trim();
        try {
            Document cfg = mongoTemplate.findOne(
                    Query.query(Criteria.where("deptCode").is(deptCode)),
                    Document.class, "configLiquidBalance");
            if (cfg == null) {
                cfg = mongoTemplate.findOne(new Query(), Document.class, "configLiquidBalance");
            }
            if (cfg != null) {
                List<Document> groups = (List<Document>) cfg.get("inOutGroupList");
                if (groups != null) {
                    for (Document g : groups) {
                        String gname = str(g.get("name"));
                        List<Document> details = (List<Document>) g.get("paramDetailList");
                        if (details == null || gname == null) continue;
                        if ("入量".equals(gname) || "其它".equals(gname)) {
                            inCodes = details.stream().map(d -> str(d.get("code")))
                                    .filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
                        } else if ("出量".equals(gname)) {
                            outCodes = details.stream().map(d -> str(d.get("code")))
                                    .filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
                            for (Document d : details) {
                                String n = str(d.get("name"));
                                if (n != null && n.contains("尿")) {
                                    urineCode = str(d.get("code"));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            qualityNotes.add("configLiquidBalance 读取失败，使用默认出入量码：" + ex.getMessage());
        }

        Set<String> allCodes = new HashSet<>(inCodes);
        allCodes.addAll(outCodes);
        if (!urineCode.isEmpty()) allCodes.add(urineCode);
        if (allCodes.isEmpty()) return;

        Map<String, int[]> sums = new HashMap<>(); // pid -> [in, out, urine]
        try {
            Query q = new Query(Criteria.where("pid").in(ids)
                    .and("code").in(allCodes)
                    .and("valid").is(true)
                    .and("time").gte(window[0]).lt(window[1]));
            for (Document d : mongoTemplate.find(q, Document.class, "bedside")) {
                Integer value = parseMl(str(d.get("strVal")));
                if (value == null) continue;
                String pid = str(d.get("pid"));
                String code = str(d.get("code"));
                int[] acc = sums.computeIfAbsent(pid, k -> new int[3]);
                boolean isIn = inCodes.contains(code);
                boolean isOut = outCodes.contains(code);
                if (isIn) acc[0] += value;
                if (isOut) acc[1] += value;
                if (!urineCode.isEmpty() && urineCode.equals(code)) acc[2] += value;
            }
        } catch (Exception ex) {
            qualityNotes.add("bedside 出入量查询失败：" + ex.getMessage());
            return;
        }

        for (Map.Entry<String, int[]> en : sums.entrySet()) {
            OverviewPatient p = byId.get(en.getKey());
            if (p == null) continue;
            int[] v = en.getValue();
            p.setIoInMl(v[0]);
            p.setIoOutMl(v[1]);
            p.setIoBalanceMl(v[0] - v[1]);
            if (!urineCode.isEmpty()) p.setUrineMl(v[2]);
        }
    }

    // ================= 危急值（未闭环跨班承接 / 窗内已闭环） =================

    /**
     * 未闭环（criticalValue.status 非已闭环取值）不受事件窗限制，跨班承接始终展示；
     * 已闭环只统计事件窗内的，避免用历史已闭环项充数。
     * 判定来源只有源系统的 status 字段，handleTime 一并给出供人工核对，不做推断。
     */
    private void enrichCriticals(Set<String> hisPids, Date[] eventWindow, Date asOf,
                                 Map<String, OverviewPatient> byId, Map<String, OverviewPatient> byHis,
                                 List<ChangeItem> changes, List<String> qualityNotes) {
        if (hisPids.isEmpty() || eventWindow == null) return;
        Date from = eventWindow[0];
        Date to = eventWindow[1];
        try {
            Criteria pendingOrInWindow = new Criteria().orOperator(
                    // 未闭环：status 不属于已闭环取值（含缺失），跨班承接
                    Criteria.where("status").nin(true, "True", "true", "1", "已处理"),
                    Criteria.where("publishTime").gte(from).lt(to));
            Query q = new Query(Criteria.where("pid").in(hisPids).andOperator(pendingOrInWindow));
            q.with(Sort.by(Sort.Direction.DESC, "publishTime"));
            Map<String, List<Document>> byPid = new HashMap<>();
            for (Document d : mongoTemplate.find(q, Document.class, "criticalValue")) {
                byPid.computeIfAbsent(str(d.get("pid")), k -> new ArrayList<>()).add(d);
            }
            for (Map.Entry<String, List<Document>> en : byPid.entrySet()) {
                OverviewPatient p = byId.get(en.getKey());
                if (p == null) p = byHis.get(en.getKey());
                if (p == null) continue;
                int pending = 0;
                int handled = 0;
                int carried = 0;
                List<CriticalItem> items = new ArrayList<>();
                for (Document d : en.getValue()) {
                    boolean isHandled = isCriticalHandled(d);
                    Date publish = toDate(d.get("publishTime"));
                    Date handleTime = toDate(d.get("handleTime"));
                    boolean inWindow = inWindow(publish, eventWindow);
                    if (isHandled) {
                        if (inWindow) handled++;
                    } else {
                        pending++;
                        if (!inWindow) carried++;
                    }
                    CriticalItem item = new CriticalItem();
                    item.setItem(str(d.get("lisItem")));
                    item.setValue(str(d.get("value")));
                    item.setTime(publish);
                    item.setHandled(isHandled);
                    item.setHandleTime(handleTime);
                    item.setCarriedOver(!isHandled && !inWindow);
                    item.setAgeHours(publish == null ? null
                            : (int) Math.max(0, (asOf.getTime() - publish.getTime()) / 3600000L));
                    items.add(item);

                    // 变化清单只收事件窗内的事件；跨班承接项不冒充“本班新发生”
                    if (inWindow) {
                        addChange(changes, p, "critical", "危急值新增",
                                itemLabel(item), publish, "criticalValue", "both");
                    }
                    if (isHandled && inWindow(handleTime, eventWindow)) {
                        addChange(changes, p, "criticalClosed", "危急值闭环",
                                itemLabel(item), handleTime, "criticalValue", "both");
                    }
                }
                // 待闭环优先，其余按发布时间倒序，最多 10 条
                items.sort(Comparator.comparing((CriticalItem i) -> Boolean.TRUE.equals(i.getHandled()))
                        .thenComparing(CriticalItem::getTime, Comparator.nullsLast(Comparator.reverseOrder())));
                p.setCriticalPending(pending);
                p.setCriticalHandled(handled);
                p.setCriticalCarried(carried);
                p.setCriticals(items.size() > 10 ? new ArrayList<>(items.subList(0, 10)) : items);
            }
        } catch (Exception ex) {
            qualityNotes.add(NOTE_CRITICAL_FAILED + "：" + ex.getMessage());
        }
    }

    private static String itemLabel(CriticalItem item) {
        String v = item.getValue() == null ? "" : item.getValue();
        return (item.getItem() == null ? "" : item.getItem()) + " " + v;
    }

    /**
     * 已闭环判定只看源系统 status（true/True/1/已处理 为已闭环）；
     * handleTime 仅作为展示与闭环变化的时间依据，不用于改判。
     */
    private boolean isCriticalHandled(Document d) {
        Object status = d.get("status");
        if (status instanceof Boolean) return (Boolean) status;
        String s = str(status);
        return s != null && ("true".equalsIgnoreCase(s) || "1".equals(s) || "已处理".equals(s));
    }

    // ================= 隔离角标 + 夜间抢救（DataCenter） =================

    private void enrichIsolationAndRescue(String deptCode, Set<String> hisPids, Date[] nightWindow,
                                          Map<String, OverviewPatient> byId, Map<String, OverviewPatient> byHis,
                                          List<String> qualityNotes) {
        if (hisPids.isEmpty()) return;
        MongoTemplate center = centerProvider.getIfAvailable();
        if (center == null) {
            qualityNotes.add("DataCenter 未配置（mongo.center.database 为空）：隔离角标与夜间抢救显示不可用");
            return;
        }
        Pattern isoPattern = Pattern.compile("隔离");
        try {
            Query q = new Query(Criteria.where("pid").in(hisPids)
                    .and("orderName").regex(isoPattern));
            q.with(Sort.by(Sort.Direction.DESC, "orderTime"));
            Map<String, List<Document>> byPid = new HashMap<>();
            for (Document d : center.find(q, Document.class, CENTER_COLLECTION_ORDERS)) {
                byPid.computeIfAbsent(str(d.get("pid")), k -> new ArrayList<>()).add(d);
            }
            for (Map.Entry<String, List<Document>> en : byPid.entrySet()) {
                OverviewPatient p = byId.get(en.getKey());
                if (p == null) p = byHis.get(en.getKey());
                if (p == null) continue;
                p.setBedTags(resolveIsolationTags(en.getValue()));
            }
        } catch (Exception ex) {
            qualityNotes.add("VI_ICU_ZYYZ 隔离医嘱查询失败：" + ex.getMessage());
        }

        // 夜间抢救回溯
        try {
            String joined = splitOrdered(rescueKeywords).stream()
                    .filter(s -> !s.isEmpty())
                    .map(Pattern::quote)
                    .collect(Collectors.joining("|"));
            if (joined.isEmpty()) return;
            Query q = new Query(Criteria.where("pid").in(hisPids)
                    .and("orderName").regex(Pattern.compile(joined))
                    .and("orderTime").gte(nightWindow[0]).lt(nightWindow[1]));
            q.with(Sort.by(Sort.Direction.DESC, "orderTime"));
            Map<String, List<Document>> hits = new HashMap<>();
            for (Document d : center.find(q, Document.class, CENTER_COLLECTION_ORDERS)) {
                hits.computeIfAbsent(str(d.get("pid")), k -> new ArrayList<>()).add(d);
            }
            for (Map.Entry<String, List<Document>> en : hits.entrySet()) {
                OverviewPatient p = byId.get(en.getKey());
                if (p == null) p = byHis.get(en.getKey());
                if (p == null || en.getValue().isEmpty()) continue;
                p.setRescueNight(true);
                p.setRescueDetail(en.getValue().stream().limit(3)
                        .map(d -> str(d.get("orderName")) + " @ " + fmt(toDate(d.get("orderTime"))))
                        .collect(Collectors.joining("；")));
            }
        } catch (Exception ex) {
            qualityNotes.add("VI_ICU_ZYYZ 抢救医嘱查询失败：" + ex.getMessage());
        }
    }

    /**
     * 隔离启停判定：按 topic 取 orderTime 最新事件；
     * 事件名以“停”开头或 status=已停止 → 该 topic 关闭，否则生效。
     * topic：接触(含严密/床旁接触/预防性接触)、空气、保护性、飞沫 + 医嘱名内嵌的 MDRO 菌名。
     */
    private List<IsolationTag> resolveIsolationTags(List<Document> orders) {
        Map<String, Document> latestByTopic = new LinkedHashMap<>();
        for (Document d : orders) {
            String raw = str(d.get("orderName"));
            if (raw == null) continue;
            boolean isStop = raw.trim().startsWith("停");
            String core = isStop ? raw.trim().substring(1).replace("*", "") : raw;
            for (String topic : topicsOf(core)) {
                latestByTopic.putIfAbsent(topic, d);
                // orders 已按 orderTime 倒序，每个 topic 第一次出现即最新
            }
        }
        List<IsolationTag> tags = new ArrayList<>();
        for (Map.Entry<String, Document> en : latestByTopic.entrySet()) {
            String topic = en.getKey();
            Document latest = en.getValue();
            String raw = str(latest.get("orderName"));
            boolean isStop = raw != null && raw.trim().startsWith("停");
            boolean stopped = "已停止".equals(str(latest.get("status")));
            if (isStop || stopped) continue;

            String label;
            String kind;
            switch (topic) {
                case "air": label = "空气隔离"; kind = "air"; break;
                case "protect": label = "保护性隔离"; kind = "protect"; break;
                case "droplet": label = "飞沫隔离"; kind = "droplet"; break;
                case "contact": label = "接触隔离"; kind = "contact"; break;
                case "other": label = "隔离"; kind = "other"; break;
                default: label = topic; kind = "bug"; break; // MDRO 菌名
            }
            String title = raw + "（" + str(latest.get("status")) + " · "
                    + fmt(toDate(latest.get("orderTime"))) + "）";
            tags.add(new IsolationTag(label, kind, title));
        }
        return tags.isEmpty() ? null : tags;
    }

    private List<String> topicsOf(String core) {
        List<String> topics = new ArrayList<>();
        if (core.contains("空气")) topics.add("air");
        if (core.contains("保护")) topics.add("protect");
        if (core.contains("飞沫")) topics.add("droplet");
        if (core.contains("接触") || core.contains("严密")) topics.add("contact");
        if (topics.isEmpty() && core.contains("隔离")) topics.add("other");
        for (String bug : new String[]{"CRKP", "CRAB", "MRSA", "VRE", "鲍曼", "克雷伯",
                "念珠菌", "结核", "多重耐药", "泛耐药"}) {
            if (core.contains(bug)) topics.add(bug);
        }
        return topics;
    }

    // ================= 通用工具 =================

    private boolean boundAt(Document bind, Date asOf, List<String> qualityNotes, String collection) {
        Object raw = bind.get(ventilatorUnbindField);
        if (raw == null || String.valueOf(raw).trim().isEmpty()
                || "null".equalsIgnoreCase(String.valueOf(raw).trim())) {
            return true;
        }
        Date unbindAt = toDate(raw);
        if (unbindAt == null) return false;
        return unbindAt.after(asOf);
    }

    private static int daysBetween(Date from, Date to) {
        long diff = to.getTime() - from.getTime();
        return (int) Math.max(0, diff / 86400000L);
    }

    private static Integer parseMl(String raw) {
        if (raw == null) return null;
        String m = Pattern.compile("(-?\\d+(\\.\\d+)?)").matcher(raw).find()
                ? extractFirstNumber(raw) : null;
        if (m == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(m));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String extractFirstNumber(String raw) {
        java.util.regex.Matcher m = Pattern.compile("(-?\\d+(\\.\\d+)?)").matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    private static Set<String> split(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static List<String> splitOrdered(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Date toDate(Object raw) {
        if (raw instanceof Date) return (Date) raw;
        if (raw instanceof Number) return new Date(((Number) raw).longValue());
        if (raw instanceof String && !((String) raw).trim().isEmpty()) {
            String text = ((String) raw).trim();
            for (String pattern : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd"}) {
                try {
                    return Date.from(java.time.LocalDateTime.parse(text,
                                    DateTimeFormatter.ofPattern(pattern))
                            .atZone(HOSPITAL_ZONE).toInstant());
                } catch (Exception ignored) {
                    // try next
                }
            }
            try {
                return Date.from(java.time.Instant.parse(text));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String fmt(Date date) {
        if (date == null) return "-";
        return ZonedDateTime.ofInstant(date.toInstant(), HOSPITAL_ZONE)
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }
}
