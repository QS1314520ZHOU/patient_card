package com.patientcard.patient_card_backend.controller;

import com.patientcard.patient_card_backend.model.ConfigBed;
import com.patientcard.patient_card_backend.model.Department;
import com.patientcard.patient_card_backend.model.OrderAlertKeywordConfig;
import com.patientcard.patient_card_backend.model.patient.Patient;
import com.patientcard.patient_card_backend.repository.ConfigBedRepository;
import com.patientcard.patient_card_backend.repository.DepartmentRepository;
import com.patientcard.patient_card_backend.repository.PatientRepository;
import com.patientcard.patient_card_backend.service.OrderAlertConfigService;
import com.patientcard.patient_card_backend.service.PatientRiskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/patients")
@CrossOrigin
public class PatientController {

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private ConfigBedRepository configBedRepository;

    @Autowired
    private OrderAlertConfigService orderAlertConfigService;

    @Autowired
    private PatientRiskService patientRiskService;

    @Autowired
    private com.patientcard.patient_card_backend.service.CardSupportService cardSupportService;

    @Autowired
    private com.patientcard.patient_card_backend.service.CardFluidService cardFluidService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Value("${mongo.collections.patient:patient}")
    private String patientCollection;

    @GetMapping
    public List<Patient> getAllPatients() {
        return patientRepository.findAll();
    }

    @GetMapping("/{id}")
    public Patient getPatientById(@PathVariable String id) {
        return patientRepository.findById(id).orElse(null);
    }

    @GetMapping("/departments/list")
    public List<Department> getDepartments() {
        return departmentRepository.findAll();
    }

    @GetMapping("/config-beds/{deptCode}")
    public List<ConfigBed> getConfigBedsByDeptCode(@PathVariable String deptCode) {
        return configBedRepository.findByDeptCode(deptCode);
    }

    @GetMapping("/by-dept/{deptCode}")
    public List<Patient> getPatientsByDeptCode(@PathVariable String deptCode) {
        return patientRepository.findByDeptCode(deptCode);
    }

    /**
     * 患者卡片专用接口：科室只匹配 deptCode，患者状态固定 admitted。
     */
    @GetMapping("/dashboard/{deptCode}")
    public List<Patient> getDashboardPatients(@PathVariable String deptCode) {
        List<Patient> patients = mongoTemplate.find(
                new Query(Criteria.where("deptCode").is(deptCode.trim()).and("status").is("admitted")),
                Patient.class,
                patientCollection
        );
        patientRiskService.enrich(patients);
        cardSupportService.enrich(patients);
        cardFluidService.enrich(patients, deptCode.trim());
        return patients;
    }

    @GetMapping("/by-status/{status}")
    public List<Patient> getPatientsByStatus(@PathVariable String status) {
        return patientRepository.findByStatus(status);
    }

    @GetMapping("/by-dept-status/{deptCode}/{status}")
    public List<Patient> getPatientsByDeptCodeAndStatus(
            @PathVariable String deptCode,
            @PathVariable String status
    ) {
        // 兼容旧地址，但患者状态仍固定为 admitted。
        return patientRepository.findByDeptCodeAndStatus(deptCode.trim(), "admitted");
    }

    @GetMapping("/order-alert-config/{deptCode}")
    public List<OrderAlertKeywordConfig> getOrderAlertConfig(@PathVariable String deptCode) {
        return orderAlertConfigService.getByDeptCode(deptCode);
    }

    @PutMapping("/order-alert-config/{deptCode}")
    public List<OrderAlertKeywordConfig> saveOrderAlertConfig(
            @PathVariable String deptCode,
            @RequestBody List<OrderAlertKeywordConfig> configs
    ) {
        return orderAlertConfigService.saveByDeptCode(deptCode, configs);
    }
}