package com.patientcard.patient_card_backend.service;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.stream.Collectors;

/**
 * 患者卡片设备/气道 enrichment：
 * - 呼吸机/CRRT：deviceBind 未解绑
 * - 困难气道：tubeExe 当日 type∈气管插管/气切套管 且 cuspLocation=是
 */
@Service
public class CardSupportService {
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");

    private final MongoTemplate mongoTemplate;

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

    public CardSupportService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
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
        }
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
