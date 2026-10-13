package com.patientcard.patient_card_backend.service;

import com.patientcard.patient_card_backend.model.patient.Patient;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 患者卡 24h 出入量。
 * 时间字段兼容 time / inday / outday，避免只读 time 导致当日有数却统计为 0。
 */
@Service
public class CardFluidService {
    private static final Pattern NUM = Pattern.compile("(-?\\d+(\\.\\d+)?)");

    private final MongoTemplate mongoTemplate;
    private final CardDetailService cardDetailService;

    @Value("${overview.io.in-codes:param_kouFu,param_biSi,param_YaoYeti_in_hour,param_YaoStomach_in_hour,param_YaoOther_in_hour,param_food_shiWu,param_饮食,param_YaoShuXue_in_hour,param_YaoSpecial_in_hour}")
    private String ioInCodes;

    @Value("${overview.io.out-codes:param_niaoLiang,param_daBianAmount,param_weiyeliang,param_outuwuliang,param_ylg,param_chaoLvLiang,param_恶露,param_咯血,param_渗液}")
    private String ioOutCodes;

    @Value("${overview.io.urine-code:param_niaoLiang}")
    private String ioUrineCode;

    /** 日总入量码：现场 param_in_day，窗内有记录则优先作总入，避免与分项重复累加 */
    @Value("${overview.io.day-total-in-code:param_in_day}")
    private String dayTotalInCode;

    /** 日总出量码：现场 param_out_day */
    @Value("${overview.io.day-total-out-code:param_out_day}")
    private String dayTotalOutCode;

    @Value("${mongo.collections.bedside:bedside}")
    private String bedsideCollection;

    public CardFluidService(MongoTemplate mongoTemplate, CardDetailService cardDetailService) {
        this.mongoTemplate = mongoTemplate;
        this.cardDetailService = cardDetailService;
    }

    public void enrich(List<Patient> patients, String deptCode) {
        if (patients == null || patients.isEmpty()) {
            return;
        }
        Date asOf = new Date();
        Date[] window = cardDetailService.resolveBalanceWindow(deptCode, asOf);
        String winStart = format(window[0]);
        String winEnd = format(window[1]);

        Set<String> inCodes = new HashSet<>(split(ioInCodes));
        Set<String> outCodes = new HashSet<>(split(ioOutCodes));
        String[] urineHolder = new String[]{ioUrineCode == null ? "" : ioUrineCode.trim()};
        loadCodes(deptCode, inCodes, outCodes, urineHolder);
        String urineCode = urineHolder[0];
        String dayInCode = dayTotalInCode == null ? "" : dayTotalInCode.trim();
        String dayOutCode = dayTotalOutCode == null ? "" : dayTotalOutCode.trim();

        Set<String> allCodes = new HashSet<>(inCodes);
        allCodes.addAll(outCodes);
        if (!urineCode.isEmpty()) {
            allCodes.add(urineCode);
        }
        if (!dayInCode.isEmpty()) {
            allCodes.add(dayInCode);
        }
        if (!dayOutCode.isEmpty()) {
            allCodes.add(dayOutCode);
        }

        Set<String> ids = patients.stream()
                .map(Patient::getId)
                .filter(v -> v != null && !v.trim().isEmpty())
                .collect(Collectors.toSet());

        // pid -> [dayIn, dayOut, itemIn, itemOut, urine]
        Map<String, int[]> sums = new HashMap<>();
        if (!ids.isEmpty() && !allCodes.isEmpty()) {
            try {
                String dayFrom = formatDay(window[0]);
                String dayTo = formatDay(window[1]);
                Criteria timeOrDay = new Criteria().orOperator(
                        Criteria.where("time").gte(window[0]).lt(window[1]),
                        Criteria.where("inday").gte(window[0]).lt(window[1]),
                        Criteria.where("outday").gte(window[0]).lt(window[1]),
                        Criteria.where("inday").gte(dayFrom).lt(dayTo),
                        Criteria.where("outday").gte(dayFrom).lt(dayTo),
                        // 字段名即现场参数名
                        Criteria.where("param_in_day").gte(window[0]).lt(window[1]),
                        Criteria.where("param_out_day").gte(window[0]).lt(window[1])
                );
                Query q = new Query(Criteria.where("pid").in(ids)
                        .and("code").in(allCodes)
                        .andOperator(timeOrDay));
                for (Document d : mongoTemplate.find(q, Document.class, bedsideCollection)) {
                    Boolean valid = asBool(d.get("valid"));
                    if (Boolean.FALSE.equals(valid)) {
                        continue;
                    }
                    Integer value = parseMl(str(d.get("strVal")));
                    // 字段名直接带量值时兜底
                    if (value == null) {
                        value = parseMl(str(d.get("param_in_day")));
                    }
                    if (value == null) {
                        value = parseMl(str(d.get("param_out_day")));
                    }
                    if (value == null) {
                        continue;
                    }
                    String pid = str(d.get("pid"));
                    String code = str(d.get("code"));
                    if (pid == null) {
                        continue;
                    }
                    int[] acc = sums.computeIfAbsent(pid, k -> new int[5]);
                    boolean isDayIn = dayInCode.equals(code);
                    boolean isDayOut = dayOutCode.equals(code);
                    boolean isIn = !isDayIn && inCodes.contains(code);
                    boolean isOut = !isDayOut && outCodes.contains(code);
                    // code 就是日汇总码，或 document 上直接有 param_in_day / param_out_day 量值
                    if (isDayIn || (code == null && d.get("param_in_day") != null)) {
                        acc[0] += value;
                    } else if (isIn) {
                        acc[2] += value;
                    }
                    if (isDayOut || (code == null && d.get("param_out_day") != null)) {
                        acc[1] += value;
                    } else if (isOut) {
                        acc[3] += value;
                    }
                    if (!urineCode.isEmpty() && urineCode.equals(code)) {
                        acc[4] += value;
                    }
                }
            } catch (Exception ignored) {
                // bedside 不可用时保持 null，前端显示 —
            }
        }

        for (Patient p : patients) {
            int[] v = sums.get(p.getId());
            p.setIoWindowStart(winStart);
            p.setIoWindowEnd(winEnd);
            if (v == null) {
                p.setIoInMl(null);
                p.setIoOutMl(null);
                p.setUrineMl(null);
                p.setIoBalanceMl(null);
            } else {
                // 日汇总优先，否则用分项累加
                int inMl = v[0] > 0 ? v[0] : v[2];
                int outMl = v[1] > 0 ? v[1] : v[3];
                p.setIoInMl(inMl > 0 || v[0] > 0 || v[2] > 0 ? inMl : null);
                p.setIoOutMl(outMl > 0 || v[1] > 0 || v[3] > 0 ? outMl : null);
                p.setUrineMl(v[4] > 0 ? v[4] : null);
                if (p.getIoInMl() == null && p.getIoOutMl() == null) {
                    p.setIoBalanceMl(null);
                } else {
                    p.setIoBalanceMl((p.getIoInMl() == null ? 0 : p.getIoInMl())
                            - (p.getIoOutMl() == null ? 0 : p.getIoOutMl()));
                }
            }
        }
    }

    private void loadCodes(String deptCode, Set<String> inCodes, Set<String> outCodes, String[] urineHolder) {
        try {
            Document cfg = mongoTemplate.findOne(
                    Query.query(Criteria.where("deptCode").is(deptCode)),
                    Document.class, "configLiquidBalance");
            if (cfg == null) {
                cfg = mongoTemplate.findOne(new Query(), Document.class, "configLiquidBalance");
            }
            if (cfg == null) {
                return;
            }
            List<Document> groups = (List<Document>) cfg.get("inOutGroupList");
            if (groups == null) {
                return;
            }
            for (Document g : groups) {
                String gname = str(g.get("name"));
                List<Document> details = (List<Document>) g.get("paramDetailList");
                if (details == null || gname == null) {
                    continue;
                }
                if ("入量".equals(gname) || "其它".equals(gname)) {
                    inCodes.clear();
                    for (Document d : details) {
                        String code = str(d.get("code"));
                        if (code != null) {
                            inCodes.add(code);
                        }
                    }
                } else if ("出量".equals(gname)) {
                    outCodes.clear();
                    for (Document d : details) {
                        String code = str(d.get("code"));
                        if (code != null) {
                            outCodes.add(code);
                        }
                        String n = str(d.get("name"));
                        if (n != null && n.contains("尿") && code != null) {
                            urineHolder[0] = code;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 读码表失败用 yml 默认码
        }
    }

    private static Boolean asBool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Integer parseMl(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = NUM.matcher(raw);
        if (!m.find()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(m.group(1)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Set<String> split(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static String format(Date d) {
        if (d == null) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(d);
    }

    private static String formatDay(Date d) {
        if (d == null) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }
}
