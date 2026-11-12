package com.patientcard.patient_card_backend.handover;

import com.patientcard.patient_card_backend.model.patient.Patient;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import com.patientcard.patient_card_backend.service.PatientRiskService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read only. Patient and encounter are checked both before and after remote reads. */
@Service
public class HandoverData {
 private final MongoTemplate mongo; private final BlbcjlClient client; private final HandoverConfig config; private final ObjectProvider<MongoTemplate> center; private final PatientRiskService risks;
 @Value("${mongo.collections.score:score}") private String scoreCollection;
 @Value("${mongo.collections.patient:patient}") private String patients;
 @Value("${mongo.collections.bedside:bedside}") private String bedside;
 @Value("${CLINICAL_NOTES_BLBCJL_BQDM:}") private String defaultBqdm;
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private static final Map<String,String> CODES=Map.ofEntries(
  Map.entry("param_HR","心率"),Map.entry("param_T","体温"),Map.entry("param_resp","呼吸"),Map.entry("param_spo2","血氧饱和度"),
  Map.entry("param_nibp_s","无创收缩压"),Map.entry("param_nibp_m","无创平均压"),Map.entry("param_nibp_d","无创舒张压"),
  Map.entry("param_ibp_s","有创收缩压"),Map.entry("param_ibp_m","有创平均压"),Map.entry("param_ibp_d","有创舒张压"));
 public HandoverData(MongoTemplate mongo,BlbcjlClient client,HandoverConfig config,@Qualifier("centerMongoTemplate") ObjectProvider<MongoTemplate> center,PatientRiskService risks){this.mongo=mongo;this.client=client;this.config=config;this.center=center;this.risks=risks;}
 public static class Source {public String id, kind, title, time, text; public boolean window,stale,excerpted; public int sentChars;}
 public static class Snapshot {@com.fasterxml.jackson.annotation.JsonIgnore public String mrn; @com.fasterxml.jackson.annotation.JsonIgnore public Date admission,icuAdmission; public String from,to,asOf,patientId;public List<Source> sources=new ArrayList<>();public List<String> warnings=new ArrayList<>();}
 public void verify(String dept, Snapshot snapshot){Patient p=patient(dept,snapshot.patientId);if(!Objects.equals(snapshot.mrn,p.getMrn())||!Objects.equals(snapshot.admission,p.getAdmissionTime())||!Objects.equals(snapshot.icuAdmission,p.getIcuAdmissionTime()))throw new IllegalStateException("患者住院或入科信息已改变，请重新打开");}
 Patient patient(String dept,String id){
  if(dept==null||dept.isBlank()||id==null||id.isBlank()||dept.length()>100||id.length()>100)throw new IllegalArgumentException("患者或科室参数无效");
  Patient p=mongo.findOne(Query.query(Criteria.where("_id").is(id).and("deptCode").is(dept).and("status").is("admitted")),Patient.class,patients);
  if(p==null||p.getMrn()==null||p.getMrn().isBlank())throw new IllegalStateException("当前科室中找不到有效的在科患者或住院号");
  return p;
 }
 public Snapshot load(String dept,String id){
  if(!config.isEnabled())throw new IllegalStateException("智能交班尚未启用");
  Patient first=patient(dept,id); Date admission=first.getAdmissionTime();
  if(admission==null)throw new IllegalStateException("缺少住院时间，无法安全限定本次住院范围");
  String mrn=first.getMrn(); String ward=config.getBqdmByDept().getOrDefault(dept,defaultBqdm);
  // 院内 BLBCJL 可按住院号查询全部病区；空 BQDM 不是缺少配置。
  ward = ward == null ? "" : ward.trim();
  Instant now=Instant.now(); Window win=new Window(now); Snapshot out=new Snapshot();
  out.patientId=id;out.mrn=mrn;out.admission=admission;out.icuAdmission=first.getIcuAdmissionTime();out.from=win.from.toString();out.to=win.to.toString();out.asOf=win.asOf.toString();
  List<NoteParser.Note> notes=NoteParser.parse(client.fetch(mrn,ward),mrn,ward,1000,100000);
  LocalDateTime admitted=LocalDateTime.ofInstant(admission.toInstant(),ZONE);
  int unknown=0,outside=0,index=1;
  notes.sort(Comparator.comparing((NoteParser.Note n)->n.time,Comparator.nullsLast(Comparator.reverseOrder())));
  for(NoteParser.Note n:notes){
   if(n.time==null){unknown++;continue;}
   if(n.time.isBefore(admitted)||n.time.isAfter(win.asOf)){outside++;continue;}
   Source s=new Source();s.id="N"+index++;s.kind="病程";s.title=n.type;s.time=n.time.toString();s.text=n.body;s.window=win.contains(n.time);out.sources.add(s);
  }
  out.warnings.add("病程时间取正文开头，不等同签署时间；交班时段为昨日08:00（含）至今日08:00（不含）。");
  if(unknown>0)out.warnings.add("有 "+unknown+" 份病程无法识别开头时间，未用于生成，需核对原系统。");
  if(outside>0)out.warnings.add("有 "+outside+" 份病程不在本次住院时间范围或为未来时间，已排除。");
  Date start=first.getIcuAdmissionTime();
  if(start==null){out.warnings.add("缺少入科时间，未读取床旁体征，需核对原系统。");}
  else {
   try {
    for(Map.Entry<String,String> e:CODES.entrySet()){
     Query q=Query.query(Criteria.where("pid").is(id).and("code").is(e.getKey()).and("valid").is(true).and("time").gte(start).lte(Date.from(now)).and("strVal").nin(null,""));
     q.with(Sort.by(Sort.Direction.DESC,"time")).limit(1);
     Document d=mongo.findOne(q,Document.class,bedside); if(d==null||!(d.get("time") instanceof Date))continue;
     String value=Objects.toString(d.get("strVal"),"").trim();if(value.isEmpty()||value.length()>100)continue;
     Date t=(Date)d.get("time");Source s=new Source();s.id="V"+index++;s.kind="生命体征";s.title=e.getValue();s.time=LocalDateTime.ofInstant(t.toInstant(),ZONE).toString();s.text=value;s.stale=t.toInstant().isBefore(now.minus(Duration.ofHours(4)));out.sources.add(s);
    }
   } catch(Exception e){out.sources.removeIf(s->"生命体征".equals(s.kind));out.warnings.add("生命体征读取失败，需核对原系统。");}
  }
  out.warnings.add("缺失体征不是正常；超过4小时的体征标记为过期，具体时效以院内要求为准。");
  if(start!=null) addStructured(out,first,start,now,win,index);
  else out.warnings.add("缺少入科时间，医嘱、管路、风险和出入量未读取。");
  Patient last=patient(dept,id);
  if(!mrn.equals(last.getMrn()) || !Objects.equals(admission,last.getAdmissionTime()) || !Objects.equals(first.getIcuAdmissionTime(),last.getIcuAdmissionTime()))throw new IllegalStateException("患者住院或入科信息已改变，请重新打开");
  return out;
 }
 private void addStructured(Snapshot out,Patient p,Date start,Instant now,Window win,int initial){
  int[] next={initial}; Date until=Date.from(now); Date from=Date.from(win.from.atZone(ZONE).toInstant()); Date end=Date.from(win.to.atZone(ZONE).toInstant());
  Date effectiveFrom=from.after(start)?from:start; Date effectiveEnd=end.before(until)?end:until;
  try{
   for(String type:risks.cardScoreTypes()){
    Query q=Query.query(Criteria.where("pid").is(p.getId()).and("scoreType").is(type).and("valid").is(true).and("time").gte(start).lte(until));q.with(Sort.by(Sort.Direction.DESC,"time")).limit(1);
    Document d=mongo.findOne(q,Document.class,scoreCollection);if(d==null||!(d.get("time") instanceof Date))continue;
    String total=Objects.toString(d.get("total"),"未记录"), conclusion=Objects.toString(d.get("conclusion"),"未记录");
    String label=type.equals(risks.getFallScoreType())?"跌倒风险":risks.getPressureTypes().contains(type)?"压力性损伤风险":type.equals(risks.getPipeScoreType())?"管路风险":type.equals(PatientRiskService.NUTRITION)?"营养风险":type;
    String scoreName=type.equals("bradenScore")?"Braden 评分":label+"评分";
    add(out,"R",next,"风险评分",label,(Date)d.get("time"),"评分名称："+scoreName+"；原始评分："+total+"；原始结论："+conclusion,win,false);
   }
  }catch(Exception e){out.sources.removeIf(s->"风险评分".equals(s.kind));out.warnings.add("风险评分读取失败，需核对原系统。");}
  try{
   Query q=Query.query(Criteria.where("pid").is(p.getId()).and("endTime").is(null).and("startTime").gte(start).lte(until));q.with(Sort.by(Sort.Direction.DESC,"startTime")).limit(51);
   List<Document> docs=mongo.find(q,Document.class,"tubeExe");if(docs.size()>50)out.warnings.add("管路记录超过50条，仅显示最近50条。");
   for(Document d:docs.subList(0,Math.min(50,docs.size()))){String type=Objects.toString(d.get("type"),"").trim();if(!type.isEmpty()&&d.get("startTime") instanceof Date)add(out,"T",next,"管路",type,(Date)d.get("startTime"),"置管时间见来源时间；结束时间为空（请核对实时状态）",win,false);}
  }catch(Exception e){out.sources.removeIf(s->"管路".equals(s.kind));out.warnings.add("管路读取失败，需核对原系统。");}
  try{
   if(effectiveFrom.before(effectiveEnd)){
    for(Map.Entry<String,String> e:Map.of("param_in_day","日入量原始值","param_out_day","日出量原始值","param_niaoLiang","尿量原始值").entrySet()){
     Query q=Query.query(Criteria.where("pid").is(p.getId()).and("code").is(e.getKey()).and("valid").is(true).and("time").gte(effectiveFrom).lt(effectiveEnd).and("strVal").nin(null,""));q.with(Sort.by(Sort.Direction.DESC,"time")).limit(1);
     Document d=mongo.findOne(q,Document.class,bedside);if(d!=null&&d.get("time") instanceof Date)add(out,"F",next,"出入量",e.getValue(),(Date)d.get("time"),Objects.toString(d.get("strVal"),""),win,false);
    }
   }
   out.warnings.add("出入量仅展示时段内最新原始采集值，未计算时段总量；日累计和单次值不可混加，须核对原系统。");
  }catch(Exception e){out.sources.removeIf(s->"出入量".equals(s.kind));out.warnings.add("出入量读取失败，需核对原系统。");}
  MongoTemplate dc=center.getIfAvailable();
  if(dc==null||p.getHisPid()==null||p.getHisPid().isBlank())out.warnings.add("医嘱数据源或患者关联缺失，需核对原系统。");
  else try{
   if(effectiveFrom.before(effectiveEnd)){
    Query q=Query.query(Criteria.where("pid").is(p.getHisPid()).and("orderTime").gte(effectiveFrom).lt(effectiveEnd));q.with(Sort.by(Sort.Direction.DESC,"orderTime")).limit(51);
    List<Document> orders=dc.find(q,Document.class,"VI_ICU_ZYYZ");if(orders.size()>50)out.warnings.add("时段内医嘱超过50条，仅显示最近50条。");
    for(Document d:orders.subList(0,Math.min(50,orders.size()))){String name=Objects.toString(d.get("orderName"),"").trim();if(!name.isEmpty()&&d.get("orderTime") instanceof Date)add(out,"O",next,"医嘱",name,(Date)d.get("orderTime"),"原始状态："+Objects.toString(d.get("status"),"未提供"),win,false);}
   }
   out.warnings.add("医嘱按病人标识及入科后开立时间关联；开立不等于执行，执行及本次住院归属需在原系统核对。");
  }catch(Exception e){out.sources.removeIf(s->"医嘱".equals(s.kind));out.warnings.add("医嘱读取失败，需核对原系统。");}
  out.warnings.add("结构化资料仅展示可读取的记录，不代表完整医嘱、完整管路或完整评分；缺项须核对。");
 }
 private static void add(Snapshot out,String prefix,int[] next,String kind,String title,Date time,String text,Window win,boolean stale){
  Source s=new Source();s.id=prefix+next[0]++;s.kind=kind;s.title=title.length()>200?title.substring(0,200):title;s.time=LocalDateTime.ofInstant(time.toInstant(),ZONE).toString();s.text=text.length()>500?text.substring(0,500):text;s.window=win.contains(LocalDateTime.ofInstant(time.toInstant(),ZONE));s.stale=stale;out.sources.add(s);
 }

}
