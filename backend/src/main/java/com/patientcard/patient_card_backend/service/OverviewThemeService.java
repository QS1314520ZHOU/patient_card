package com.patientcard.patient_card_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 大屏颜色标识：统一保存在 JAR 同级 config/application.yml 的 overview.theme 段，
 * 所有大屏读取同一份配色。保存时只改 overview.theme 下的颜色行，其它配置与注释原样保留。
 */
@Slf4j
@Service
public class OverviewThemeService {

    /** 前端 key → yml key（顺序即写入顺序） */
    private static final LinkedHashMap<String, String> KEYS = new LinkedHashMap<>();
    /** 缺省配色（yml 未配置时使用） */
    private static final Map<String, String> DEFAULTS = new HashMap<>();

    static {
        KEYS.put("newHead", "new-head");
        KEYS.put("newPill", "new-pill");
        KEYS.put("normalHead", "normal-head");
        KEYS.put("longBg", "long-bg");
        KEYS.put("longTx", "long-tx");
        DEFAULTS.put("newHead", "#10B9A0");
        DEFAULTS.put("newPill", "#059669");
        DEFAULTS.put("normalHead", "#5392FF");
        DEFAULTS.put("longBg", "#FDE68A");
        DEFAULTS.put("longTx", "#7C2D12");
    }

    private static final Pattern HEX = Pattern.compile("^#?([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})$");

    private final Environment env;
    /** 可选：显式指定要回写的配置文件；留空则自动定位 JAR 同级 config/application.yml */
    private final String configFileProp;
    /** 可选：非空时保存配色需要提供此口令（请求头 X-Theme-Key） */
    private final String editKey;

    public OverviewThemeService(Environment env,
                                @Value("${overview.theme.config-file:}") String configFileProp,
                                @Value("${overview.theme.edit-key:}") String editKey) {
        this.env = env;
        this.configFileProp = configFileProp == null ? "" : configFileProp.trim();
        this.editKey = editKey == null ? "" : editKey.trim();
    }

    public boolean keyRequired() {
        return !editKey.isEmpty();
    }

    public boolean checkKey(String provided) {
        if (editKey.isEmpty()) return true;
        if (provided == null) return false;
        return MessageDigest.isEqual(editKey.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** 当前配色：优先实时读取配置文件（手工改 yml 刷新页面即生效），再回退启动时配置与缺省值 */
    public synchronized Map<String, String> current() {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> fileTheme = readThemeFromFile();
        for (Map.Entry<String, String> e : KEYS.entrySet()) {
            String v = null;
            if (fileTheme != null && fileTheme.get(e.getValue()) != null) {
                v = normalize(String.valueOf(fileTheme.get(e.getValue())));
            }
            if (v == null) v = normalize(env.getProperty("overview.theme." + e.getValue()));
            out.put(e.getKey(), v == null ? DEFAULTS.get(e.getKey()) : v);
        }
        return out;
    }

    /** 校验并写回 yml；返回保存后的完整配色 */
    public synchronized Map<String, String> save(Map<String, String> input) throws IOException {
        Map<String, String> merged = current();
        if (input != null) {
            for (String key : KEYS.keySet()) {
                if (!input.containsKey(key)) continue;
                String v = normalize(input.get(key));
                if (v == null) {
                    throw new IllegalArgumentException("色号格式不正确：" + key + "=" + input.get(key));
                }
                merged.put(key, v);
            }
        }
        Path file = resolveConfigFile();
        if (file == null) {
            throw new IOException("未找到外部配置文件 config/application.yml，"
                    + "请确认以 --spring.config.additional-location=file:<目录>/config/ 启动，"
                    + "或配置 overview.theme.config-file");
        }
        writeTheme(file, merged);
        log.info("大屏配色已保存到 {}：{}", file, merged);
        return merged;
    }

    // ================= 配置文件定位与读写 =================

    private Path resolveConfigFile() {
        List<Path> candidates = new ArrayList<>();
        if (!configFileProp.isEmpty()) {
            candidates.add(Paths.get(stripFilePrefix(configFileProp)));
        }
        String additional = env.getProperty("spring.config.additional-location", "");
        for (String loc : additional.split(",")) {
            String s = stripFilePrefix(loc.trim().replaceFirst("^optional:", ""));
            if (s.isEmpty() || s.startsWith("classpath:")) continue;
            Path p = Paths.get(s);
            candidates.add(s.endsWith(".yml") || s.endsWith(".yaml") ? p : p.resolve("application.yml"));
        }
        candidates.add(Paths.get(System.getProperty("user.dir"), "config", "application.yml"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
        }
        return null;
    }

    private static String stripFilePrefix(String s) {
        return s.startsWith("file:") ? s.substring(5) : s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readThemeFromFile() {
        Path file = resolveConfigFile();
        if (file == null) return null;
        try (InputStream in = Files.newInputStream(file)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return null;
            Object overview = ((Map<String, Object>) root).get("overview");
            if (!(overview instanceof Map)) return null;
            Object theme = ((Map<String, Object>) overview).get("theme");
            return theme instanceof Map ? (Map<String, Object>) theme : null;
        } catch (Exception ex) {
            log.warn("读取配色配置失败 {}：{}", file, ex.getMessage());
            return null;
        }
    }

    /**
     * 行级更新 overview.theme 段：已有的颜色行就地替换，缺失的追加到 theme 段末尾；
     * 无 theme 段则插入到 overview 段首；无 overview 段则追加到文件末尾。
     * 写入前备份为 application.yml.bak，并通过临时文件原子替换。
     */
    private void writeTheme(Path file, Map<String, String> theme) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        boolean bom = text.startsWith("\uFEFF");
        if (bom) text = text.substring(1);
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));

        int overviewIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^overview:\\s*(#.*)?$")) { overviewIdx = i; break; }
        }
        if (overviewIdx < 0) {
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
            lines.add("");
            lines.add("overview:");
            overviewIdx = lines.size() - 1;
            lines.add("");
        }
        int overviewEnd = blockEnd(lines, overviewIdx, 0);

        // overview 段的子级缩进（缺省 2 空格）
        String indent = "  ";
        for (int i = overviewIdx + 1; i < overviewEnd; i++) {
            String l = lines.get(i);
            if (isContent(l)) { indent = l.substring(0, l.length() - l.stripLeading().length()); break; }
        }
        String childIndent = indent + indent;

        int themeIdx = -1;
        for (int i = overviewIdx + 1; i < overviewEnd; i++) {
            if (lines.get(i).matches("^" + Pattern.quote(indent) + "theme:\\s*(#.*)?$")) { themeIdx = i; break; }
        }
        if (themeIdx < 0) {
            List<String> block = new ArrayList<>();
            block.add(indent + "theme:");
            block.add(childIndent + "# 大屏卡片颜色标识：页面「配色」面板保存时自动回写本段颜色行（其它配置不受影响）");
            for (Map.Entry<String, String> e : KEYS.entrySet()) {
                block.add(childIndent + e.getValue() + ": \"" + theme.get(e.getKey()) + "\"");
            }
            block.add(childIndent + "# 非空时保存配色需输入此口令；留空则任何能打开大屏的人都可保存");
            block.add(childIndent + "edit-key: \"\"");
            lines.addAll(overviewIdx + 1, block);
        } else {
            int themeEnd = blockEnd(lines, themeIdx, indent.length());
            String themeChildIndent = childIndent;
            for (int i = themeIdx + 1; i < themeEnd; i++) {
                String l = lines.get(i);
                if (isContent(l)) { themeChildIndent = l.substring(0, l.length() - l.stripLeading().length()); break; }
            }
            int insertAt = themeEnd;
            while (insertAt > themeIdx + 1 && lines.get(insertAt - 1).trim().isEmpty()) insertAt--;
            for (Map.Entry<String, String> e : KEYS.entrySet()) {
                String newLine = themeChildIndent + e.getValue() + ": \"" + theme.get(e.getKey()) + "\"";
                Pattern keyLine = Pattern.compile("^" + Pattern.quote(themeChildIndent)
                        + Pattern.quote(e.getValue()) + ":\\s*(\"[^\"]*\"|'[^']*'|[^#\\s]*)(.*)$");
                boolean replaced = false;
                for (int i = themeIdx + 1; i < insertAt; i++) {
                    java.util.regex.Matcher m = keyLine.matcher(lines.get(i));
                    if (m.matches()) {
                        String oldVal = normalize(m.group(1).replaceAll("^[\"']|[\"']$", ""));
                        if (!theme.get(e.getKey()).equals(oldVal)) {
                            // 只改值，原样保留行尾注释
                            String tail = m.group(2).trim().startsWith("#") ? m.group(2) : "";
                            lines.set(i, newLine + tail);
                        }
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    lines.add(insertAt, newLine);
                    insertAt++;
                }
            }
        }

        String out = (bom ? "\uFEFF" : "") + String.join(nl, lines);
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 从 start 行之后，找到缩进 ≤ parentIndent 的第一条内容行（即该块结束位置） */
    private static int blockEnd(List<String> lines, int start, int parentIndent) {
        for (int i = start + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!isContent(l)) continue;
            int ind = l.length() - l.stripLeading().length();
            if (ind <= parentIndent) return i;
        }
        return lines.size();
    }

    /** 非空、非纯注释行 */
    private static boolean isContent(String l) {
        String t = l.trim();
        return !t.isEmpty() && !t.startsWith("#");
    }

    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (!HEX.matcher(s).matches()) return null;
        s = s.startsWith("#") ? s.substring(1) : s;
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        return "#" + s.toUpperCase(Locale.ROOT);
    }
}
