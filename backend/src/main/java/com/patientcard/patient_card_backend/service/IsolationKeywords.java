package com.patientcard.patient_card_backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只提取医嘱中的隔离关键词，不把严密等类型推断为接触隔离。 */
final class IsolationKeywords {
    private static final Pattern KEYWORDS = Pattern.compile(
            "(?:严密|保护性接触|预防性接触|床旁接触|接触性|保护性|保护|呼吸道|消化道|空气|肠道|床旁|接触|飞沫)隔离");

    static List<String> extract(String text) {
        List<String> labels = new ArrayList<>();
        Matcher matcher = KEYWORDS.matcher(text);
        while (matcher.find()) {
            String label = matcher.group();
            if (!labels.contains(label)) labels.add(label);
        }
        if (labels.isEmpty() && text.contains("隔离")) labels.add("隔离");
        return labels;
    }

    static String kind(String label) {
        if (label.contains("空气")) return "air";
        if (label.contains("飞沫")) return "droplet";
        if (label.contains("保护")) return "protect";
        if (label.contains("接触")) return "contact";
        return "other";
    }
}
