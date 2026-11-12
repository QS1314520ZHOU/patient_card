package com.patientcard.patient_card_backend.handover;

import com.patientcard.patient_card_backend.model.patient.Patient;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read-only, bounded context for a shared-time-axis chart. Not a monitoring or decision system. */
@Service
public class HandoverTrendService {
 private final MongoTemplate mongo; private final ObjectProvider<MongoTemplate> center;
 private final HandoverData handover; private final HandoverAi ai;
 @Value("${mongo.collections.bedside:bedside}") private String bedside="bedside";
 private static final Map<String,String[]> OBS=Map.ofEntries(
  Map.entry("param_HR",new String[]{"心率","次/分","体征"}),Map.entry("param_resp",new String[]{"呼吸","次/分","体征"}),
  Map.entry("param_spo2",new String[]{"血氧饱和度","%","体征"}),Map.entry("param_T",new String[]{"体温","℃","体征"}),
  Map.entry("param_nibp_s",new String[]{"无创收缩压","mmHg","体征"}),Map.entry("param_nibp_m",new String[]{"无创平均压","mmHg","体征"}),Map.entry("param_nibp_d",new String[]{"无创舒张压","mmHg","体征"}),
  Map.entry("param_ibp_s",new String[]{"有创收缩压","mmHg","体征"}),Map.entry("param_ibp_m",new String[]{"有创平均压","mmHg","体征"}),Map.entry("param_ibp_d",new String[]{"有创舒张压","mmHg","体征"}),
  Map.entry("param_in_day",new String[]{"当日累计入量","mL","出入量"}),Map.entry("param_out_day",new String[]{"当日累计出量","mL","出入量"}));
 private static final Map<String,String> LAB=Map.ofEntries(Map.entry("PLT","血小板"),Map.entry("platelet","血小板"),Map.entry("TBIL","总胆红素"),Map.entry("sCr","肌酐"),Map.entry("CREA","肌酐"),Map.entry("Cr","肌酐"),Map.entry("LAC","乳酸"),Map.entry("LAC1","乳酸"));
 public static class Point { public long time;public double value; public String rawStatus; }
 public static class Track { public String id,label,unit,kind; public List<Point> points=new ArrayList<>(); }
 public static class Event { public long time; public String name,action; }
 public static class Chart {public long from,to;public List<Track> tracks=new ArrayList<>();public List<Event> medications=new ArrayList<>();public List<String> selected=new ArrayList<>(),warnings=new ArrayList<>();public boolean aiSelected;}
 public HandoverTrendService(MongoTemplate mongo,@Qualifier("centerMongoTemplate") ObjectProvider<MongoTemplate> center,HandoverData handover,HandoverAi ai){this.mongo=mongo;this.center=center;this.handover=handover;this.ai=ai;}
 public Chart load(String dept,String id){
  HandoverData.Snapshot snap=handover.load(dept,id);Patient p=handover.patient(dept,id);handover.verify(dept,snap);
  if(!Objects.equals(snap.mrn,p.getMrn())||!Objects.equals(snap.hisPid,p.getHisPid())||!Objects.equals(snap.admission,p.getAdmissionTime())||!Objects.equals(snap.icuAdmission,p.getIcuAdmissionTime()))throw new IllegalStateException("患者住院或入科信息已改变，请重新打开");
  if(p.getIcuAdmissionTime()==null)throw new IllegalStateException("缺少入科时间，无法安全读取趋势资料");
  Instant to=Instant.now(),from=to.minus(Duration.ofHours(24)),admitted=p.getIcuAdmissionTime().toInstant();if(admitted.isAfter(from))from=admitted;
  if(!from.isBefore(to))throw new IllegalStateException("入科时间无效，无法读取趋势资料");
  Chart c=new Chart();c.from=from.toEpochMilli();c.to=to.toEpochMilli();
  c.warnings.add("回顾近24小时（不早于本次入科），与交班草稿固定时段不同；仅显示有记录的时点，不推断因果或连续用药。体征单位按项目既有映射，须院内核对。");
  readBedside(c,id,from,to);readLabs(c,p.getHisPid(),from,to);readDrugs(c,id,admitted,from,to);
  handover.verify(dept,snap);ai.chooseTrends(c,snap,p.getClinicalDiagnosis(),p.getAdmissionDiagnosis());handover.verify(dept,snap);return c;
 }
 void readBedside(Chart c,String pid,Instant from,Instant to){
  try{
   Query q=Query.query(Criteria.where("pid").is(pid).and("code").in(OBS.keySet()).and("valid").is(true).and("time").gte(Date.from(from)).lte(Date.from(to)));
   q.with(Sort.by(Sort.Direction.DESC,"time")).limit(7001);q.fields().include("time").include("code").include("strVal");
   List<Document> rows=mongo.find(q,Document.class,bedside);if(rows.size()>7000)c.warnings.add("体征与出入量记录超过7000条，仅显示最近7000条，图表不完整。");
   Map<String,Track> grouped=new LinkedHashMap<>();int skipped=0;
   for(Document d:rows.subList(0,Math.min(rows.size(),7000))){String code=d.getString("code");String[] meta=OBS.get(code);Double val=number(d.get("strVal"));Object t=d.get("time");if(meta==null||!(t instanceof Date)||val==null){skipped++;continue;}String key="bed:"+code;Track track=grouped.computeIfAbsent(key,k->track(k,meta[0],meta[1],meta[2]));point(track,((Date)t).getTime(),val,"");}
   c.tracks.addAll(grouped.values());if(skipped>0)c.warnings.add("有 "+skipped+" 条体征或出入量记录不是精确数值或缺少时间，未绘图。");
   c.warnings.add("当日入量与出量为当日累计原始值，跨日可能重置；不计算交班时段总量，也不与其他分项相加。");
  }catch(Exception e){c.tracks.removeIf(t->!"检验".equals(t.kind));c.warnings.add("体征或出入量读取失败，不能视为该时段无记录。");}
 }
 void readLabs(Chart c,String hisPid,Instant from,Instant to){
  MongoTemplate db=center.getIfAvailable();if(db==null||hisPid==null||hisPid.isBlank()){c.warnings.add("检验来源或住院关联缺失，不能视为无检验。");return;}
  try{
   Query q=Query.query(Criteria.where("hisPid").is(hisPid).and("itemCode").in(LAB.keySet()).and("authTime").gte(Date.from(from)).lte(Date.from(to)));
   q.with(Sort.by(Sort.Direction.DESC,"authTime")).limit(2001);q.fields().include("itemCode").include("result").include("unit").include("authTime").include("resultStatus");
   List<Document> rows=db.find(q,Document.class,"VI_ICU_EXAM_ITEM");if(rows.size()>2000)c.warnings.add("检验记录超过2000条，仅显示最近2000条，图表不完整。");
   Map<String,Track> grouped=new LinkedHashMap<>();int skipped=0;
   for(Document d:rows.subList(0,Math.min(2000,rows.size()))){String name=LAB.get(d.getString("itemCode"));Double val=number(d.get("result"));Object time=d.get("authTime");if(name==null||val==null||!(time instanceof Date)){skipped++;continue;}
    String unit=Objects.toString(d.get("unit"),"").trim();if(unit.isEmpty())unit="单位未记录";if(unit.length()>50)unit="单位待核对";
    String group=name+"|"+unit;Track t=grouped.get(group);if(t==null){t=track("lab:"+(grouped.size()+1),name,unit,"检验");grouped.put(group,t);}String status=Objects.toString(d.get("resultStatus"),"未提供");if(status.length()>80)status=status.substring(0,80);point(t,((Date)time).getTime(),val,status);
   }
   c.tracks.addAll(grouped.values());if(skipped>0)c.warnings.add("有 "+skipped+" 条检验不是精确数值或缺少时间，未绘图；需查看原报告。");
   c.warnings.add("检验仅覆盖已映射的血小板、总胆红素、肌酐和乳酸；时间为报告审核时间，非采样时间，结果状态须核对原报告。");
  }catch(Exception e){c.tracks.removeIf(t->"检验".equals(t.kind));c.warnings.add("检验读取失败，不能视为该时段无记录。");}
 }
 void readDrugs(Chart c,String pid,Instant admitted,Instant from,Instant to){
  try{
   List<Object> ids=new ArrayList<>();ids.add(pid);if(ObjectId.isValid(pid))ids.add(new ObjectId(pid));Instant earliest=from.minus(Duration.ofDays(30));if(admitted.isAfter(earliest))earliest=admitted;
   Query q=Query.query(Criteria.where("pid").in(ids).and("startTime").gte(Date.from(earliest)).lte(Date.from(to)));q.with(Sort.by(Sort.Direction.DESC,"startTime")).limit(2001);q.fields().include("startTime").include("drugList").include("drugActionList");
   List<Document> rows=mongo.find(q,Document.class,"drugExe");if(rows.size()>2000)c.warnings.add("用药执行记录超过2000条，仅查看最近记录，事件可能不完整。");
   List<Event> events=new ArrayList<>();
   for(Document d:rows.subList(0,Math.min(2000,rows.size()))){String name=drugName(d.get("drugList"));addEvent(events,d.get("startTime"),name,"起始记录（状态待核对）",from,to);
    if(d.get("drugActionList") instanceof List)for(Object v:(List<?>)d.get("drugActionList"))if(v instanceof Document){Document a=(Document)v;Object action=a.get("action")!=null?a.get("action"):a.get("type");addEvent(events,a.get("time"),name,actionName(action),from,to);}
   }
   events.sort(Comparator.comparingLong((Event e)->e.time).reversed());if(events.size()>300)c.warnings.add("用药事件超过300条，仅显示最近300条，事件可能不完整。");c.medications.addAll(events.subList(0,Math.min(300,events.size())));
   c.warnings.add("用药仅检索近30天开始且不早于本次入科的执行记录；过早开始的用药可能遗漏。起始与操作时间不代表持续时间、实际剂量或疗效，须核对原系统。");
  }catch(Exception e){c.medications.clear();c.warnings.add("用药执行读取失败，不能视为该时段未用药。");}
 }
 static Track track(String id,String label,String unit,String kind){Track t=new Track();t.id=id;t.label=label;t.unit=unit;t.kind=kind;return t;}
 static void point(Track t,long time,double value,String status){Point p=new Point();p.time=time;p.value=value;p.rawStatus=status;t.points.add(p);}
 static Double number(Object v){if(v==null)return null;try{String s=String.valueOf(v).trim();if(!s.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")||s.length()>40)return null;double d=Double.parseDouble(s);return Double.isFinite(d)?d:null;}catch(Exception e){return null;}}
 static String drugName(Object raw){if(!(raw instanceof List))return "未命名用药记录";List<String> names=new ArrayList<>();for(Object o:(List<?>)raw)if(o instanceof Document){String n=Objects.toString(((Document)o).get("name"),"").trim();if(!n.isEmpty()&&!names.contains(n)){names.add(n.length()>100?n.substring(0,100):n);if(names.size()==5)break;}}return names.isEmpty()?"未命名用药记录":String.join("＋",names);}
 static String actionName(Object raw){String a=Objects.toString(raw,"").trim().toLowerCase(Locale.ROOT);if(Set.of("stop","停止","停用").contains(a))return "停止";if(Set.of("pause","暂停").contains(a))return "暂停";if(Set.of("recovery","resume","恢复","继续").contains(a))return "恢复";if(Set.of("cancel","取消").contains(a))return "取消";return "操作类型待核对";}
 static void addEvent(List<Event> events,Object raw,String name,String action,Instant from,Instant to){if(!(raw instanceof Date))return;long t=((Date)raw).getTime();if(t<from.toEpochMilli()||t>to.toEpochMilli())return;Event e=new Event();e.time=t;e.name=name;e.action=action;events.add(e);}
}
