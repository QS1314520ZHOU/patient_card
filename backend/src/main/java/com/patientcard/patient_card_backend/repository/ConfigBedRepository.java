package com.patientcard.patient_card_backend.repository;

import com.patientcard.patient_card_backend.model.ConfigBed;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ConfigBedRepository extends MongoRepository<ConfigBed, String> {
    List<ConfigBed> findByDeptCode(String deptCode);
}
