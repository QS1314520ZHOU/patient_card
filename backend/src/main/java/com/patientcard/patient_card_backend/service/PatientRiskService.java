package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.patient.Patient;
import com.patientcard.patient_card_backend.model.patient.RiskAssessment;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PatientRiskService {
    /** 导管风险 scoreType 可配置；兼容生产库 MS 命名 */
    @Value("${risk.score-types.pipe:pipeRiskMonitorScoreMS}")
    private String pipeScoreType;
    public static final String PRESSURE = "pressureDamageScore";
    public static final String NUTRITION = "nutritionalRisk";
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");

    private final MongoTemplate mongoTemplate;
    private final VteAssessmentService vteAssessmentService;

    @Value("${risk.pressure.high-max:12}")
    private int pressureHighMax;
    @Value("${risk.pressure.medium-max:14}")
    private int pressureMediumMax;
    @Value("${risk.nutrition.medium-min:3}")
    private int nutritionMediumMin;
    @Value("${risk.nutrition.high-min:5}")
    private int nutritionHighMin;
    @Value("${risk.fall.medium-min:25}")
    private int fallMediumMin;
    @Value("${risk.fall.high-min:45}")
    private int fallHighMin;
    // 跌倒评估在 score 集合中的 scoreType，按院内实际配置调整
    @Value("${risk.score-types.fall:fallRisk}")
    private String fallScoreType;
    // 压力性损伤评分类型（按优先级取第一个有记录的）：兼容生产库命名与本地 Braden
    @Value("${risk.score-types.pressure:pressureDamageScore,bradenScore,bradenNurseScore}")
    private String pressureScoreTypesRaw;
    @Value("${mongo.collections.score:score}")
    private String scoreCollection;

    public PatientRiskService(MongoTemplate mongoTemplate, VteAssessmentService vteAssessmentService) {
        this.mongoTemplate = mongoTemplate;
        this.vteAssessmentService = vteAssessmentService;
    }

    public void enrich(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) {
            return;
        }

        Date[] businessWindow = currentBusinessWindow();
        for (Patient patient : patients) {
            Date admittedAt = patient.getIcuAdmissionTime();
            patient.setNewAdmission(admittedAt != null
                    && !admittedAt.before(businessWindow[0])
                    && admittedAt.before(businessWindow[1]));
            String admissionType = patient.getAdmissionType() == null ? "" : patient.getAdmissionType().trim();
            patient.setSurgeryAdmission("手术".equals(admissionType));
            patient.setTransferIn(admissionType.contains("转入"));
        }

        Set<String> patientIds = patients.stream()
                .map(Patient::getId)
                .filter(value -> value != null && !value.trim().isEmpty())
                .collect(Collectors.toSet());
        Map<String, Map<String, RiskAssessment>> scoreMap = findLatestScores(patientIds);

        Set<String> hisPids = patients.stream()
                .map(Patient::getHisPid)
                .filter(value -> value != null && !value.trim().isEmpty())
                .collect(Collectors.toSet());
        Map<String, RiskAssessment> vteMap = vteAssessmentService.findLatestByPatientGuids(hisPids);
        if (vteMap == null) {
            // 来源不可用：卡片不出 VTE 标签，不冒充“无风险”
            vteMap = Collections.emptyMap();
        }

        for (Patient patient : patients) {
            Map<String, RiskAssessment> patientScores = scoreMap.getOrDefault(patient.getId(), Collections.emptyMap());
            patient.setPipeRisk(patientScores.get(pipeScoreType == null ? "" : pipeScoreType.trim()));
            patient.setPressureDamageRisk(pickPressure(patientScores));
            patient.setNutritionRisk(patientScores.get(NUTRITION));
            if (fallScoreType != null && !fallScoreType.trim().isEmpty()) {
                patient.setFallRisk(patientScores.get(fallScoreType.trim()));
            }
            patient.setVteRisk(vteMap.get(patient.getHisPid()));
        }
    }

    /** 压力性损伤评分：按配置优先级取第一个有记录的类型（兼容生产库与本地 Braden 命名）。 */
    public RiskAssessment pickPressure(Map<String, RiskAssessment> byType) {
        if (byType == null) {
            return null;
        }
        for (String type : getPressureTypes()) {
            RiskAssessment risk = byType.get(type);
            if (risk != null) {
                return risk;
            }
        }
        return null;
    }

    public List<String> getPressureTypes() {
        List<String> types = new ArrayList<>();
        if (pressureScoreTypesRaw != null) {
            for (String s : pressureScoreTypesRaw.split(",")) {
                if (!s.trim().isEmpty()) {
                    types.add(s.trim());
                }
            }
        }
        if (types.isEmpty()) {
            types.add(PRESSURE);
        }
        return types;
    }

    /** 患者卡片/大屏统一查询的 scoreType 集合。 */
    public List<String> cardScoreTypes() {
        List<String> types = new ArrayList<>();
        if (pipeScoreType != null && !pipeScoreType.trim().isEmpty()) {
            types.add(pipeScoreType.trim());
        }
        types.addAll(getPressureTypes());
        types.add(NUTRITION);
        if (fallScoreType != null && !fallScoreType.trim().isEmpty()) {
            types.add(fallScoreType.trim());
        }
        return types;
    }

    public Map<String, Map<String, RiskAssessment>> findLatestScores(Set<String> patientIds) {
        return findLatestScores(patientIds, cardScoreTypes());
    }

    public Map<String, Map<String, RiskAssessment>> findLatestScores(Set<String> patientIds, List<String> scoreTypes) {
        if (patientIds.isEmpty() || scoreTypes == null || scoreTypes.isEmpty()) {
            return Collections.emptyMap();
        }
        Query query = new Query(Criteria.where("pid").in(patientIds)
                .and("scoreType").in(scoreTypes)
                .and("valid").is(true));
        query.with(Sort.by(Sort.Direction.DESC, "time"));

        List<Document> documents = mongoTemplate.find(query, Document.class, scoreCollection);
        Map<String, Map<String, RiskAssessment>> latest = new HashMap<>();
        for (Document document : documents) {
            String pid = valueAsId(document.get("pid"));
            String scoreType = document.getString("scoreType");
            if (pid == null || scoreType == null) {
                continue;
            }
            Map<String, RiskAssessment> byType = latest.computeIfAbsent(pid, ignored -> new HashMap<>());
            if (byType.containsKey(scoreType)) {
                continue;
            }
            Integer total = valueAsInteger(document.get("total"));
            Date time = document.getDate("time");
            byType.put(scoreType, assessmentFor(scoreType, total, time, document.getString("conclusion")));
        }
        return latest;
    }

    public RiskAssessment assessmentFor(String scoreType, Integer total, Date time) {
        return assessmentFor(scoreType, total, time, null);
    }

    /**
     * @param conclusion 院内录入的结论（如跌倒评估“高风险”），优先于自算阈值
     */
    public RiskAssessment assessmentFor(String scoreType, Integer total, Date time, String conclusion) {
        String level = "unknown";
        String result = "未分级";
        // 跌倒：优先使用院内结论，避免自算阈值与院内分级不一致
        if (fallScoreType != null && fallScoreType.equals(scoreType)
                && conclusion != null && !conclusion.trim().isEmpty()) {
            level = levelFromConclusion(conclusion);
            result = conclusion.trim();
            return new RiskAssessment(level, result, total == null ? null : String.valueOf(total), time, scoreType);
        }
        if (total != null) {
            if (pipeScoreType != null && pipeScoreType.trim().equals(scoreType)) {
                // 现场：total>5 为高危
                if (total > 5) {
                    level = "high";
                    result = "高危";
                } else {
                    level = "low";
                    result = "低危";
                }
            } else if (getPressureTypes().contains(scoreType)) {
                // Braden：total < high-max（默认 <12）为高危
                if (total < pressureHighMax) {
                    level = "high";
                    result = "高危";
                } else if (total <= pressureMediumMax) {
                    level = "medium";
                    result = "中危";
                } else {
                    level = "low";
                    result = "低危";
                }
            } else if (NUTRITION.equals(scoreType)) {
                // 默认按 NRS-2002 三段展示；阈值可按院内规范调整。
                if (total >= nutritionHighMin) {
                    level = "high";
                    result = "高危";
                } else if (total >= nutritionMediumMin) {
                    level = "medium";
                    result = "中危";
                } else {
                    level = "low";
                    result = "低危";
                }
            } else if (fallScoreType != null && fallScoreType.equals(scoreType)) {
                // 无结论时回退：分值越高风险越高（如 Morse），阈值按院内量表配置。
                if (total >= fallHighMin) {
                    level = "high";
                    result = "高危";
                } else if (total >= fallMediumMin) {
                    level = "medium";
                    result = "中危";
                } else {
                    level = "low";
                    result = "低危";
                }
            }
        }
        return new RiskAssessment(level, result, total == null ? null : String.valueOf(total), time, scoreType);
    }

    private String levelFromConclusion(String conclusion) {
        if (conclusion.contains("高")) {
            return "high";
        }
        if (conclusion.contains("中")) {
            return "medium";
        }
        if (conclusion.contains("低") || conclusion.contains("无")) {
            return "low";
        }
        return "unknown";
    }

    public String getFallScoreType() {
        return fallScoreType;
    }

    public String getPipeScoreType() {
        return pipeScoreType;
    }

    private Date[] currentBusinessWindow() {
        ZonedDateTime now = ZonedDateTime.now(HOSPITAL_ZONE);
        LocalDate startDate = now.toLocalTime().isBefore(LocalTime.of(8, 0))
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        ZonedDateTime start = startDate.atTime(8, 0).atZone(HOSPITAL_ZONE);
        return new Date[]{Date.from(start.toInstant()), Date.from(start.plusDays(1).toInstant())};
    }

    private String valueAsId(Object value) {
        if (value instanceof ObjectId) {
            return ((ObjectId) value).toHexString();
        }
        return value == null ? null : String.valueOf(value);
    }

    private Integer valueAsInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
