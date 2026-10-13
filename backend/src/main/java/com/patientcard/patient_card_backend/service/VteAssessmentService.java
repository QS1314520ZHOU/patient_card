package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.patient.RiskAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VteAssessmentService {
    private static final Logger log = LoggerFactory.getLogger(VteAssessmentService.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${vte.enabled:true}")
    private boolean enabled;

    public VteAssessmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return pid(hisPid) → 最新 VTE 评估；查询失败或功能未启用返回 null（表示“来源不可用”，不得当作 0）
     */
    public Map<String, RiskAssessment> findLatestByPatientGuids(Set<String> patientGuids) {
        if (!enabled) {
            return null;
        }
        if (patientGuids == null || patientGuids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> ids = patientGuids.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT patient_guid, assess_name, assess_value, assess_result, assess_date "
                + "FROM view_his_vte WHERE patient_guid IN (" + placeholders + ") "
                + "AND assess_date IS NOT NULL ORDER BY assess_date DESC";
        Map<String, RiskAssessment> latest = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(sql, ids.toArray(), rs -> {
                String patientGuid = rs.getString("patient_guid");
                if (!latest.containsKey(patientGuid)) {
                    Timestamp timestamp = rs.getTimestamp("assess_date");
                    Date assessDate = timestamp == null ? null : new Date(timestamp.getTime());
                    String result = rs.getString("assess_result");
                    latest.put(patientGuid, new RiskAssessment(
                            normalizeVteLevel(result),
                            result,
                            rs.getString("assess_value"),
                            assessDate,
                            rs.getString("assess_name")
                    ));
                }
            });
        } catch (Exception ex) {
            // VTE 数据源异常：返回 null 让调用方把指标降级为“不可用”，而不是伪装成 0
            log.warn("VTE assessment query failed: {}", ex.getMessage());
            return null;
        }
        return latest;
    }

    private String normalizeVteLevel(String result) {
        if (result == null) {
            return "unknown";
        }
        if (result.contains("高危")) {
            return "high";
        }
        if (result.contains("中危")) {
            return "medium";
        }
        if (result.contains("低危")) {
            return "low";
        }
        return "unknown";
    }
}
