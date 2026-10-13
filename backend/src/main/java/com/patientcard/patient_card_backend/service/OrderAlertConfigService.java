package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.OrderAlertKeywordConfig;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class OrderAlertConfigService {

    private final MongoTemplate mongoTemplate;

    public OrderAlertConfigService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<OrderAlertKeywordConfig> getByDeptCode(String deptCode) {
        String collectionName = getCollectionName(deptCode);
        if (!mongoTemplate.collectionExists(collectionName)) {
            return new ArrayList<>();
        }

        return mongoTemplate.findAll(OrderAlertKeywordConfig.class, collectionName).stream()
                .filter(item -> item.getKeyword() != null && !item.getKeyword().trim().isEmpty())
                .sorted(Comparator.comparing(item -> item.getSortOrder() == null ? Integer.MAX_VALUE : item.getSortOrder()))
                .collect(Collectors.toList());
    }

    public List<OrderAlertKeywordConfig> saveByDeptCode(String deptCode, List<OrderAlertKeywordConfig> configs) {
        String collectionName = getCollectionName(deptCode);
        List<OrderAlertKeywordConfig> sanitized = sanitize(configs);

        if (mongoTemplate.collectionExists(collectionName)) {
            mongoTemplate.dropCollection(collectionName);
        }
        if (!sanitized.isEmpty()) {
            mongoTemplate.insert(sanitized, collectionName);
        }

        return sanitized;
    }

    private List<OrderAlertKeywordConfig> sanitize(List<OrderAlertKeywordConfig> configs) {
        List<OrderAlertKeywordConfig> sanitized = new ArrayList<>();
        if (configs == null) {
            return sanitized;
        }

        int sort = 0;
        for (OrderAlertKeywordConfig item : configs) {
            if (item == null || item.getKeyword() == null || item.getKeyword().trim().isEmpty()) {
                continue;
            }

            OrderAlertKeywordConfig clean = new OrderAlertKeywordConfig();
            clean.setKeyword(item.getKeyword().trim());
            clean.setLabel(
                    item.getLabel() == null || item.getLabel().trim().isEmpty()
                            ? item.getKeyword().trim()
                            : item.getLabel().trim()
            );
            clean.setEnabled(item.getEnabled() == null ? true : item.getEnabled());
            clean.setSortOrder(sort++);
            sanitized.add(clean);
        }
        return sanitized;
    }

    private String getCollectionName(String deptCode) {
        String normalized = deptCode == null ? "unknown" : deptCode.trim();
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        // Mongo collection name normalization by dept code
        normalized = Pattern.compile("[^a-zA-Z0-9_-]").matcher(normalized).replaceAll("_");
        return "order_alert_config_" + normalized;
    }
}
