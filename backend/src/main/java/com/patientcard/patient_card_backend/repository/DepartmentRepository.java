package com.patientcard.patient_card_backend.repository;

import com.patientcard.patient_card_backend.model.Department;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DepartmentRepository extends MongoRepository<Department, String> {
}
