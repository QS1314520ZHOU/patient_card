package com.patientcard.patient_card_backend.service;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.patientcard.patient_card_backend.model.patient.Patient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 患者卡片设备/气道 enrichment：
 * - 呼吸机/CRRT：deviceBind 未解绑
 * - 困难气道：tubeExe 当日 type∈气管插管/气切套管 且 cuspLocation=是
 */
@Service
public class CardSupportService {
    private static final Logger log = LoggerFactory.getLogger(CardSupportService.class);
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CENTER_COLLECTION_ORDERS = "VI_ICU_ZYYZ";

    private final MongoTemplate mongoTemplate;
    private final ObjectProvider<MongoTemplate> centerProvider;

    @Value("${mongo.collections.deviceBind:deviceBind}")
    private String deviceBindCollection;

    @Value("${overview.ventilator.unbind-field:unBindTime}")
    private String unbindField;

    @Value("${overview.ventilator.type-field:type}")
    private String typeField;

    @Value("${overview.ventilator.type-values:vent}")
    private String ventTypeValues;

    @Value("${overview.support.crrt-values:crrt}")
    private String crrtTypeValues;

    @Value("${overview.tube.airway-types:气管插管,气切套管}")
    private String airwayTypesRaw;

    public CardSupportService(MongoTemplate mongoTemplate,
                              @Qualifier("centerMongoTemplate") ObjectProvider<MongoTemplate> centerProvider) {
        this.mongoTemplate = mongoTemplate;
        this.centerProvider = centerProvider;
    }

    public void enrich(List<Patient> patients) {
        if (patients == null || patients.isEmpty()) {
            return;
        }
        Set<String> ids = patients.stream()
                .map(Patient::getId)
                .filter(v -> v != null && !v.trim().isEmpty())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        Map<String, Boolean> ventMap = boundDeviceMap(ids, split(ventTypeValues));
        Map<String, Boolean> crrtMap = boundDeviceMap(ids, split(crrtTypeValues));
        Set<String> airwayIds = difficultAirwayIds(ids);
        for (Patient p : patients) {
            String id = p.getId();
            p.setVentilator(Boolean.TRUE.equals(ventMap.get(id)));
            p.setCrrt(Boolean.TRUE.equals(crrtMap.get(id)));
            p.setDifficultAirway(airwayIds.contains(id));
            // 隔离不允许沿用 patient 集合基本信息字段，先清空再只认医嘱
            p.setIsolation(null);
        }
        enrichIsolation(patients);
    }

    /**
     * 隔离角标唯一来源：DataCenter VI_ICU_ZYYZ 隔离医嘱启停判定。
     * 关联关系仅此一条：ZYYZ.pid = patient.hisPid。
     * 不读取 SmartCare patient.isolation 等基本信息字段。
     */
    private void enrichIsolation(List<Patient> patients) {
        MongoTemplate center = centerProvider.getIfAvailable();
        if (center == null) {
            log.warn("隔离角标跳过：centerMongoTemplate 不可用（检查 mongo.center.database 配置）");
            return;
        }
        Set<String> hisPids = patients.stream()
                .map(Patient::getHisPid)
                .filter(v -> v != null && !v.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toSet());
        if (hisPids.isEmpty()) {
            return;
        }
        try {
            Query q = new Query(Criteria.where("pid").in(hisPids)
                    .and("orderName").regex(Pattern.compile("隔离")));
            q.with(Sort.by(Sort.Direction.DESC, "orderTime"));
            List<Document> orders = center.find(q, Document.class, CENTER_COLLECTION_ORDERS);
            log.info("VI_ICU_ZYYZ 隔离医嘱命中 {} 条（hisPid {} 个）", orders.size(), hisPids.size());
            Map<String, List<Document>> byPid = new HashMap<>();
            for (Document d : orders) {
                String pid = d.get("pid") == null ? null : String.valueOf(d.get("pid")).trim();
                if (pid == null) {
                    continue;
                }
                byPid.computeIfAbsent(pid, k -> new java.util.ArrayList<>()).add(d);
            }
            for (Patient p : patients) {
                String his = p.getHisPid() == null ? null : p.getHisPid().trim();
                if (his == null) {
                    continue;
                }
                List<Document> matched = byPid.get(his);
                if (matched == null || matched.isEmpty()) {
                    continue;
                }
                String isolation = joinActiveIsolationLabels(matched);
                if (isolation != null && !isolation.isEmpty()) {
                    p.setIsolation(isolation);
                }
            }
        } catch (Exception ex) {
            log.warn("VI_ICU_ZYYZ 隔离医嘱查询失败：{}", ex.getMessage());
        }
    }

    /** 按 topic 取最新事件，拼接仍在启用中的隔离标签，如「接触隔离,空气隔离」 */
    private String joinActiveIsolationLabels(List<Document> orders) {
        Map<String, Document> latestByTopic = new java.util.LinkedHashMap<>();
        for (Document d : orders) {
            String raw = str(d.get("orderName"));
            if (raw == null) {
                continue;
            }
            boolean isStop = raw.trim().startsWith("停");
            String core = isStop ? raw.trim().substring(1).replace("*", "") : raw;
            for (String topic : isolationTopics(core)) {
                latestByTopic.putIfAbsent(topic, d);
            }
        }
        List<String> labels = new java.util.ArrayList<>();
        for (Map.Entry<String, Document> en : latestByTopic.entrySet()) {
            String raw = str(en.getValue().get("orderName"));
            boolean isStop = raw != null && raw.trim().startsWith("停");
            boolean stopped = "已停止".equals(str(en.getValue().get("status")));
            if (isStop || stopped) {
                continue;
            }
            String topic = en.getKey();
            switch (topic) {
                case "air": labels.add("空气隔离"); break;
                case "protect": labels.add("保护隔离"); break;
                case "droplet": labels.add("飞沫隔离"); break;
                case "contact": labels.add("接触隔离"); break;
                case "other": labels.add("隔离"); break;
                default: labels.add(topic); break;
            }
        }
        return labels.isEmpty() ? null : String.join(",", labels);
    }

    private List<String> isolationTopics(String core) {
        List<String> topics = new java.util.ArrayList<>();
        if (core.contains("空气")) topics.add("air");
        if (core.contains("保护")) topics.add("protect");
        if (core.contains("飞沫")) topics.add("droplet");
        if (core.contains("接触") || core.contains("严密")) topics.add("contact");
        if (topics.isEmpty() && core.contains("隔离")) topics.add("other");
        return topics;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Boolean> boundDeviceMap(Set<String> patientIds, Set<String> types) {
        Map<String, Boolean> map = new HashMap<>();
        if (types.isEmpty()) {
            return map;
        }
        try {
            Query q = new Query(Criteria.where("pid").in(patientIds)
                    .and(typeField).in(types));
            List<Document> docs = mongoTemplate.find(q, Document.class, deviceBindCollection);
            Date now = new Date();
            for (Document d : docs) {
                String pid = idAsString(d.get("pid"));
                if (pid == null) {
                    continue;
                }
                Object raw = d.get(unbindField);
                boolean hasUnbind = raw != null
                        && (!(raw instanceof String) || !((String) raw).trim().isEmpty());
                boolean unbound = hasUnbind
                        && (!(raw instanceof Date) || ((Date) raw).before(now));
                if (!unbound) {
                    map.put(pid, true);
                }
            }
        } catch (Exception ignored) {
            // deviceBind 不可用时不冒充“在用”
        }
        return map;
    }

    private Set<String> difficultAirwayIds(Set<String> patientIds) {
        Set<String> result = new HashSet<>();
        Set<String> types = split(airwayTypesRaw);
        if (types.isEmpty()) {
            return result;
        }
        try {
            Date[] window = currentBusinessDay();
            Query q = new Query(Criteria.where("pid").in(patientIds)
                    .and("type").in(types)
                    .and("cuspLocation").is("是")
                    .and("startTime").gte(window[0]).lt(window[1]));
            List<Document> docs = mongoTemplate.find(q, Document.class, "tubeExe");
            for (Document d : docs) {
                String pid = idAsString(d.get("pid"));
                if (pid != null) {
                    result.add(pid);
                }
            }
        } catch (Exception ignored) {
            // tubeExe 不可用时不出困难气道标识
        }
        return result;
    }

    private Date[] currentBusinessDay() {
        ZonedDateTime now = ZonedDateTime.now(HOSPITAL_ZONE);
        LocalDate startDate = now.toLocalTime().isBefore(LocalTime.of(8, 0))
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        ZonedDateTime start = startDate.atTime(LocalTime.of(8, 0)).atZone(HOSPITAL_ZONE);
        return new Date[]{Date.from(start.toInstant()), Date.from(start.plusDays(1).toInstant())};
    }

    private Set<String> split(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null) {
            return set;
        }
        for (String s : raw.split(",")) {
            if (!s.trim().isEmpty()) {
                set.add(s.trim());
            }
        }
        return set;
    }

    private String idAsString(Object value) {
        if (value instanceof ObjectId) {
            return ((ObjectId) value).toHexString();
        }
        return value == null ? null : String.valueOf(value);
    }
}
