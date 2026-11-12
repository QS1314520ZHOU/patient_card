package com.patientcard.patient_card_backend.service;

import org.springframework.dao.DuplicateKeyException;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Per-department display aliases. Patient records are never changed. */
@Service
public class DepartmentGroupMappingService {
    private static final String COLLECTION = "patientCardGroupMappings";
    private final MongoTemplate mongo;
    private final String editKey;
    public DepartmentGroupMappingService(MongoTemplate mongo,
            @Value("${overview.group-mapping.edit-key:}") String editKey) {
        this.mongo = mongo;
        this.editKey = editKey == null ? "" : editKey.trim();
    }
    public boolean editingEnabled() { return !editKey.isEmpty(); }
    public boolean checkKey(String provided) {
        return editingEnabled() && provided != null && MessageDigest.isEqual(
                editKey.getBytes(StandardCharsets.UTF_8), provided.trim().getBytes(StandardCharsets.UTF_8));
    }
    public Document read(String dept) {
        Document doc = mongo.findOne(Query.query(Criteria.where("_id").is("dept:" + deptCode(dept))), Document.class, COLLECTION);
        return doc == null ? new Document("revision", 0).append("mappings", new ArrayList<>()) : doc;
    }
    public Map<String, String> mapping(String dept) {
        Map<String, String> result = new LinkedHashMap<>();
        Object rows = read(dept).get("mappings");
        if (rows instanceof List<?>) for (Object obj : (List<?>) rows) {
            if (obj instanceof Document) {
                Document d = (Document) obj;
                String source = d.getString("source"), display = d.getString("display");
                if (source != null && display != null) result.put(source, display);
            }
        }
        return result;
    }
    public static String displayName(String name, Map<String, String> aliases) {
        if (name == null || name.trim().isEmpty()) return name;
        String source = name.trim();
        return aliases.getOrDefault(source, source);
    }
    public static String deptCode(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > 128 || result.indexOf('\0') >= 0) throw new IllegalArgumentException("科室编码无效");
        return result;
    }
    public static List<Document> validate(List<Map<String, String>> input) {
        if (input == null || input.size() > 200) throw new IllegalArgumentException("组别配置最多200条");
        List<Document> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Map<String, String> row : input) {
            if (row == null) throw new IllegalArgumentException("配置行无效");
            String source = row.get("source") == null ? "" : row.get("source").trim();
            String display = row.get("display") == null ? "" : row.get("display").trim();
            if (source.isEmpty() || display.isEmpty() || source.length() > 100 || display.length() > 100)
                throw new IllegalArgumentException("原组别和显示名称不能为空，且不能超过100字");
            if ("未分组".equals(source) || "未分组".equals(display))
                throw new IllegalArgumentException("“未分组”为系统保留名称");
            if (!seen.add(source)) throw new IllegalArgumentException("原组别重复：" + source);
            result.add(new Document("source", source).append("display", display));
        }
        return result;
    }
    public void save(String dept, long revision, List<Map<String, String>> input) {
        String id = "dept:" + deptCode(dept);
        List<Document> rows = validate(input);
        if (revision < 0) throw new IllegalArgumentException("版本号无效");
        if (revision == 0) {
            try { mongo.insert(new Document("_id", id).append("revision", 1L).append("mappings", rows), COLLECTION); }
            catch (DuplicateKeyException e) { throw new ConcurrentModificationException("配置已被其他人修改，请重新打开后再保存"); }
        } else {
            Query q = Query.query(Criteria.where("_id").is(id).and("revision").is(revision));
            if (mongo.updateFirst(q, new Update().set("mappings", rows).inc("revision", 1), COLLECTION).getMatchedCount() == 0)
                throw new ConcurrentModificationException("配置已被其他人修改，请重新打开后再保存");
        }
    }
}
