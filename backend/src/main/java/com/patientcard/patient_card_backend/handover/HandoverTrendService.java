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
 public static class Event { public long time;public String action,rawAction,speed; }
 public static class Segment { public long from,to;public String state; }
 public static class DrugRecord { public String status,hisStatus;public List<Event> actions=new ArrayList<>();public List<Segment> segments=new ArrayList<>();public List<String> issues=new ArrayList<>(); }
 public static class DrugTrack { public String id,name;public List<DrugRecord> records=new ArrayList<>(); }
 public static class Chart {public long from,to;public List<Track> tracks=new ArrayList<>();public List<DrugTrack> medications=new ArrayList<>();public List<String> selected=new ArrayList<>(),selectedDrugs=new ArrayList<>(),warnings=new ArrayList<>();public Map<String,String> metricReasons=new LinkedHashMap<>(),drugReasons=new LinkedHashMap<>();public boolean aiSelected;}
 public HandoverTrendService(MongoTemplate mongo,@Qualifier("centerMongoTemplate") ObjectProvider<MongoTemplate> center,HandoverData handover,HandoverAi ai){this.mongo=mongo;this.center=center;this.handover=handover;this.ai=ai;}
 public Chart load(String dept,String id){
  HandoverData.Snapshot snap=handover.load(dept,id);Patient p=handover.patient(dept,id);handover.verify(dept,snap);
  if(!Objects.equals(snap.mrn,p.getMrn())||!Objects.equals(snap.hisPid,p.getHisPid())||!Objects.equals(snap.admission,p.getAdmissionTime())||!Objects.equals(snap.icuAdmission,p.getIcuAdmissionTime()))throw new IllegalStateException("患者住院或入科信息已改变，请重新打开");
  if(p.getIcuAdmissionTime()==null)throw new IllegalStateException("缺少入科时间，无法安全读取趋势资料");
  Instant to=Instant.now(),from=to.minus(Duration.ofHours(24)),admitted=p.getIcuAdmissionTime().toInstant();if(admitted.isAfter(from))from=admitted;
  if(!from.isBefore(to))throw new IllegalStateException("入科时间无效，无法读取趋势资料");
  Chart c=new Chart();c.from=from.toEpochMilli();c.to=to.toEpochMilli();
  c.warnings.add("回顾近24小时（不早于本次入科），与交班草稿固定时段不同；仅显示有记录的时点和操作记录区间，不推断因果或实际给药。体征单位按项目既有映射，须院内核对。");
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
   Query q=Query.query(Criteria.where("pid").in(ids).and("startTime").gte(Date.from(earliest)).lte(Date.from(to)));q.with(Sort.by(Sort.Direction.DESC,"startTime")).limit(2001);q.fields().include("startTime").include("endTime").include("status").include("hisStartTime.statusFlag").include("drugList").include("drugActionList");
   List<Document> rows=mongo.find(q,Document.class,"drugExe");if(rows.size()>2000)c.warnings.add("用药执行记录超过2000条，仅查看最近2000条，可能遗漏。");
   Map<String,DrugTrack> groups=new LinkedHashMap<>();boolean excess=false;
   for(Document d:rows.subList(0,Math.min(2000,rows.size()))){
    String name=drugName(d.get("drugList"));DrugRecord record=drugRecord(d,from,to);
    if(record.actions.isEmpty()&&record.segments.isEmpty())continue;
    DrugTrack drug=groups.get(name);if(drug==null){if(groups.size()>=120){excess=true;continue;}drug=new DrugTrack();drug.id="drug:"+(groups.size()+1);drug.name=name;groups.put(name,drug);}drug.records.add(record);
   }
   c.medications.addAll(groups.values());if(excess)c.warnings.add("用药种类超过120组，仅纳入最近记录对应的120组，其他可能遗漏。");
   c.warnings.add("用药记录检索从图表起点向前最多30天且不早于本次入科；更早启动的用药可能遗漏。时间段由操作记录推导，不证明实际给药；请核对原系统和状态。");
  }catch(Exception e){c.medications.clear();c.warnings.add("用药执行读取失败，不能视为该时段未用药。");}
 }
 static DrugRecord drugRecord(Document d,Instant from,Instant to){
  DrugRecord r=new DrugRecord();r.status=shortText(d.get("status"),80);Object his=d.get("hisStartTime");r.hisStatus=his instanceof Document?shortText(((Document)his).get("statusFlag"),80):"";
  Object raw=d.get("drugActionList");List<Event> all=new ArrayList<>();boolean incomplete=false;
  if(raw instanceof List){List<?> actions=(List<?>)raw;if(actions.size()>500){incomplete=true;r.issues.add("操作记录超过500条，未推算区间，请核对原系统。");}
   for(Object v:actions.subList(0,Math.min(actions.size(),500)))if(v instanceof Document){Document a=(Document)v;Object at=a.get("time");if(!(at instanceof Date)){r.issues.add("存在无时间的操作，区间待核对。");incomplete=true;continue;}long t=((Date)at).getTime();if(t>to.toEpochMilli())continue;if(d.get("startTime") instanceof Date && t<((Date)d.get("startTime")).getTime()){incomplete=true;r.issues.add("操作时间早于起始时间，未推算区间。");}String rawAction=shortText(a.get("action")!=null?a.get("action"):a.get("type"),60);Event e=new Event();e.time=t;e.rawAction=rawAction;e.action=actionName(rawAction);String speed=shortText(a.get("speed"),40),unit=shortText(a.get("speedUnit"),40);e.speed=speed.isBlank()?"":speed+(unit.isBlank()?"（单位未记录）":" "+unit);all.add(e);}
  }
  all.sort(Comparator.comparingLong(e->e.time));
  for(int i=1;i<all.size();i++)if(all.get(i).time==all.get(i-1).time){incomplete=true;r.issues.add("操作存在相同时间，先后顺序待核对；未推算区间。");break;}
  for(Event e:all)if(e.time>=from.toEpochMilli()&&e.time<=to.toEpochMilli())r.actions.add(e);
  if(!incomplete && !all.isEmpty()){
   Event previous=null;
   for(Event e:all){
    if(previous!=null){String st=stateAfter(previous.rawAction);if(st!=null && e.time>previous.time){String next=e.action;boolean paired="运行".equals(st)?Set.of("暂停","停止").contains(next):Set.of("恢复","停止").contains(next);addSegment(r,previous.time,e.time,paired?st:"过程未知",from,to);if(!paired)r.issues.add("操作顺序不完整或未知，区间待核对。");}}
    previous=e;
   }
   if(previous!=null && "运行".equals(stateAfter(previous.rawAction)) && previous.time<to.toEpochMilli()){
    if(d.get("endTime") instanceof Date){long end=((Date)d.get("endTime")).getTime();if(end>previous.time){addSegment(r,previous.time,end,"过程未知",from,to);r.issues.add("有结束时间但无对应停止操作，该区间待核对。");}else r.issues.add("结束时间不晚于最后开始或恢复操作，未推算后续区间。");}
    else addSegment(r,previous.time,to.toEpochMilli(),"未见结束",from,to);
   }
  }else if(!incomplete && all.isEmpty() && d.get("startTime") instanceof Date && d.get("endTime") instanceof Date){
   long a=((Date)d.get("startTime")).getTime(),b=((Date)d.get("endTime")).getTime();if(b>a){addSegment(r,a,b,"过程未知",from,to);r.issues.add("仅有起止时间，期间是否暂停未知。");}else r.issues.add("起止时间异常，未推算区间。");
  }
  if(all.isEmpty()){
   addFieldEvent(r,d.get("startTime"),"起始时间字段（待核对）",from,to);addFieldEvent(r,d.get("endTime"),"结束时间字段（待核对）",from,to);
  }
  if(!r.hisStatus.isEmpty())r.issues.add("HIS 原始状态："+r.hisStatus+"；与操作记录可能不一致，请核对。");
  if(!r.status.isEmpty())r.issues.add("执行记录原始状态："+r.status+"。请核对原系统。");
  return r;
 }
 static void addFieldEvent(DrugRecord r,Object value,String action,Instant from,Instant to){if(!(value instanceof Date))return;long time=((Date)value).getTime();if(time<from.toEpochMilli()||time>to.toEpochMilli())return;Event e=new Event();e.time=time;e.action=action;e.rawAction="";e.speed="";r.actions.add(e);}
 static void addSegment(DrugRecord r,long a,long b,String state,Instant from,Instant to){long left=Math.max(a,from.toEpochMilli()),right=Math.min(b,to.toEpochMilli());if(right<=left)return;Segment s=new Segment();s.from=left;s.to=right;s.state=state;r.segments.add(s);}
 static String stateAfter(String raw){String a=raw.toLowerCase(Locale.ROOT);if(Set.of("start","recovery","resume","开始","恢复","继续").contains(a))return "运行";if(Set.of("pause","暂停").contains(a))return "暂停";return null;}
 static String shortText(Object value,int max){String s=Objects.toString(value,"").trim().replaceAll("[\\r\\n\\t]"," ");return s.length()>max?s.substring(0,max):s;}
 static Track track(String id,String label,String unit,String kind){Track t=new Track();t.id=id;t.label=label;t.unit=unit;t.kind=kind;return t;}
 static void point(Track t,long time,double value,String status){Point p=new Point();p.time=time;p.value=value;p.rawStatus=status;t.points.add(p);}
 static Double number(Object v){if(v==null)return null;try{String s=String.valueOf(v).trim();if(!s.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")||s.length()>40)return null;double d=Double.parseDouble(s);return Double.isFinite(d)?d:null;}catch(Exception e){return null;}}
 static String drugName(Object raw){if(!(raw instanceof List))return "未命名用药记录";List<String> names=new ArrayList<>();boolean truncated=false;for(Object o:(List<?>)raw)if(o instanceof Document){String n=Objects.toString(((Document)o).get("name"),"").trim();if(!n.isEmpty()&&!names.contains(n)){if(names.size()>=20){truncated=true;break;}if(n.length()>200){n=n.substring(0,200)+"…";truncated=true;}names.add(n);}}return names.isEmpty()?"未命名用药记录":String.join("＋",names)+(truncated?"（名称已截断，请核对原系统）":"");}
 static String actionName(Object raw){String a=Objects.toString(raw,"").trim().toLowerCase(Locale.ROOT);if(Set.of("start","开始").contains(a))return "开始";if(Set.of("stop","停止","停用").contains(a))return "停止";if(Set.of("pause","暂停").contains(a))return "暂停";if(Set.of("recovery","resume","恢复","继续").contains(a))return "恢复";if(Set.of("cancel","取消").contains(a))return "取消";return "操作类型待核对";}
}
