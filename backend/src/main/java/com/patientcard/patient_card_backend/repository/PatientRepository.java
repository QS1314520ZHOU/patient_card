package com.patientcard.patient_card_backend.repository;

import com.patientcard.patient_card_backend.model.patient.Patient;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PatientRepository extends MongoRepository<Patient, String> {
    List<Patient> findByDeptCode(String deptCode);
    List<Patient> findByStatus(String status);
    List<Patient> findByDeptCodeAndStatus(String deptCode, String status);
}