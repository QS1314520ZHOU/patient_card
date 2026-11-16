package com.patientcard.patient_card_backend.handover;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Configuration only. Clinical content, keys and endpoints must not be logged. */
@Component
@ConfigurationProperties(prefix = "handover")
public class HandoverConfig {
    private boolean enabled = false;
    private Map<String,String> bqdmByDept = new LinkedHashMap<>();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled=value; }
    public Map<String,String> getBqdmByDept() { return bqdmByDept; }
    public void setBqdmByDept(Map<String,String> v) { bqdmByDept=v==null?new LinkedHashMap<>():v; }
}
