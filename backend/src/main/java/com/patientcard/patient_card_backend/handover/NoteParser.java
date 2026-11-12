package com.patientcard.patient_card_backend.handover;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/** BLBCJL response parser; never guesses a date buried in narrative or repairs quotes. */
public final class NoteParser {
    private static final Pattern HEADER = Pattern.compile("^\\s*(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ \\t]+(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})(?:\\s*[:：]\\s*(\\d{1,2}))?(?=\\s|$)");
    public static final class Note {
        public final String type, body;
        public final LocalDateTime time;
        Note(String type,String body,LocalDateTime time) {this.type=type;this.body=body;this.time=time;}
    }
    private NoteParser() {}
    public static LocalDateTime headerTime(String body) {
        if (body==null) return null;
        Matcher m=HEADER.matcher(body);
        if (!m.find()) return null;
        try {return LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6)==null?0:Integer.parseInt(m.group(6)));}
        catch (DateTimeException|NumberFormatException e) {return null;}
    }
    public static List<Note> parse(byte[] bytes,String mrn,String bqdm,int maxDocs,int maxBody) {
        ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try {
            JsonNode root;
            try { root=mapper.readTree(bytes); }
            catch(Exception e) {root=mapper.readTree(normalize(bytes));}
            if(root==null||!root.isObject()||!(root.path("code").isIntegralNumber()||root.path("code").isTextual())||!"0".equals(root.path("code").asText())||!root.path("data").isArray())throw new IllegalArgumentException();
            if(root.path("data").size()>maxDocs)throw new IllegalArgumentException();
            List<Note> notes=new ArrayList<>();
            for(JsonNode row:root.path("data")) {
                if(!row.isObject() || !row.path("BLH").isTextual() || !mrn.equals(row.path("BLH").asText()) || !row.path("BQDM").isTextual() || !bqdm.equals(row.path("BQDM").asText()) || !row.path("WBNR").isTextual()) throw new IllegalArgumentException();
                String body=row.path("WBNR").asText(); if(body.isBlank()||body.length()>maxBody)throw new IllegalArgumentException();
                String type=row.path("MBKMC").isTextual()?row.path("MBKMC").asText():"文书类型待核对";
                if(type.length()>200)type=type.substring(0,200);
                notes.add(new Note(type,body,headerTime(body)));
            }
            return notes;
        } catch(Exception e) {throw new IllegalArgumentException("病程响应格式、范围或患者归属校验失败");}
    }
    /** Port of the sepsis adapter: only normalize raw CR/LF/TAB and invalid backslash escapes inside WBNR. */
    static byte[] normalize(byte[] bytes) throws Exception {
        String input=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        StringBuilder out=new StringBuilder(input.length()+64);boolean quoted=false,escaped=false,body=false;int changed=0;
        for(int i=0;i<input.length();i++) {char c=input.charAt(i);
            if(!quoted) {if(c=='"') {quoted=true;int before=i-1;while(before>=0&&Character.isWhitespace(input.charAt(before)))before--;body=false;if(before>=0&&input.charAt(before)==':'){int end=before-1;while(end>=0&&Character.isWhitespace(input.charAt(end)))end--;int begin=end-5;body=begin>=0&&input.substring(begin,end+1).equals("\"WBNR\"");}}out.append(c);continue;}
            if(escaped){out.append(c);escaped=false;continue;}
            if(c=='\\'){if(body&&i+1<input.length()&&"\"\\/bfnrtu".indexOf(input.charAt(i+1))<0){out.append("\\\\");changed++;continue;}out.append(c);escaped=true;continue;}
            if(c=='"'){quoted=false;out.append(c);continue;}
            if(c<32){if(!body||(c!='\n'&&c!='\r'&&c!='\t'))throw new IllegalArgumentException();out.append(c=='\n'?"\\n":c=='\r'?"\\r":"\\t");changed++;}else out.append(c);
        }
        if(quoted||changed==0)throw new IllegalArgumentException();
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
