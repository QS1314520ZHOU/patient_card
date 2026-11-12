package com.patientcard.patient_card_backend.controller;

import com.patientcard.patient_card_backend.service.DepartmentGroupMappingService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/group-mappings")
public class DepartmentGroupMappingController {
    private final DepartmentGroupMappingService service;
    private final MongoTemplate mongo;
    @Value("${mongo.collections.patient:patient}") private String patientCollection;
    public DepartmentGroupMappingController(DepartmentGroupMappingService service, MongoTemplate mongo) {
        this.service = service; this.mongo = mongo;
    }
    @GetMapping("/{dept}")
    public Map<String, Object> get(@PathVariable String dept) {
        dept = DepartmentGroupMappingService.deptCode(dept);
        Document doc = service.read(dept);
        List<String> groups = mongo.findDistinct(Query.query(Criteria.where("deptCode").is(dept).and("status").is("admitted")),
                "medicalGroup", patientCollection, String.class);
        Set<String> names = new TreeSet<>();
        for (String group : groups) if (group != null && !group.trim().isEmpty()) names.add(group.trim());
        return Map.of("revision", ((Number)doc.get("revision")).longValue(), "mappings", doc.get("mappings"),
                "availableGroups", names, "editingEnabled", service.editingEnabled());
    }
    public static class SaveRequest {
        public long revision;
        public List<Map<String, String>> mappings;
    }
    @PutMapping("/{dept}")
    public ResponseEntity<?> save(@PathVariable String dept, @RequestBody SaveRequest req,
            @RequestHeader(value="X-Group-Edit-Key", required=false) String key) {
        if (!service.editingEnabled()) return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "尚未配置组别管理口令，请联系管理员设置 overview.group-mapping.edit-key"));
        if (!service.checkKey(key)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "组别管理口令不正确"));
        try {
            service.save(dept, req.revision, req.mappings);
            return ResponseEntity.ok(Map.of("saved", true));
        } catch (ConcurrentModificationException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
