package com.patientcard.patient_card_backend.handover;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Access depends on hospital network controls, not on patient/department parameters. */
@RestController
@RequestMapping("/api/handover")
public class HandoverController {
 private final HandoverData data;private final HandoverAi ai;private final Semaphore slots=new Semaphore(3);
 public HandoverController(HandoverData data,HandoverAi ai){this.data=data;this.ai=ai;}
 @GetMapping("/snapshot") public ResponseEntity<?> snapshot(@RequestParam String dept,@RequestParam String patient,HttpServletRequest req){
  if(!sameOrigin(req))return response(403,Map.of("error","不允许跨站访问"));
  if(!slots.tryAcquire())return response(429,Map.of("error","交班请求较多，请稍后再试"));
  try {return response(200,data.load(dept,patient));}catch(IllegalArgumentException|IllegalStateException e){return response(400,Map.of("error",safeMessage(e)));}catch(Exception e){return response(500,Map.of("error","读取失败，请核对院内服务"));}finally{slots.release();}
 }
 @PostMapping("/generate") public ResponseEntity<?> generate(@RequestParam String dept,@RequestParam String patient,HttpServletRequest req){
  if(!sameOrigin(req))return response(403,Map.of("error","不允许跨站访问"));
  if(!slots.tryAcquire())return response(429,Map.of("error","交班请求较多，请稍后再试"));
  try {HandoverData.Snapshot s=data.load(dept,patient);HandoverAi.Result r=ai.generate(s);data.verify(dept,s);return response(200,r);}
  catch(IllegalArgumentException|IllegalStateException e){return response(400,Map.of("error",safeMessage(e)));}catch(Exception e){return response(500,Map.of("error","生成失败，请核对院内服务"));}finally{slots.release();}
 }
 private static String safeMessage(Exception e){
  String m=e.getMessage();
  if(m!=null && java.util.Set.of("智能交班尚未启用","患者或科室参数无效","当前科室中找不到有效的在科患者或住院号","缺少住院时间，无法安全限定本次住院范围","病程接口尚未配置完整","病程接口读取失败，请检查院内接口配置和网络","病程响应格式、范围或患者归属校验失败","患者住院或入科信息已改变，请重新打开","院内模型或向量接口未配置完整","院内 AI 接口调用失败，请检查模型服务和配置","向量接口响应无效，未生成交班草稿","向量接口响应无效","向量维度不一致","向量值无效","向量接口响应不完整","模型未返回可用草稿，请重试或核对模型配置","模型输出达到长度上限，未显示不完整草稿；请检查模型输出额度或缩短资料","模型服务过滤了本次输出，未显示草稿；请联系院内模型服务管理员核查","交班模型参数无效，请检查 thinking 和 max-tokens 配置","模型引用了不存在的来源，未显示草稿，请重试","模型未返回来源编号，未显示草稿，请重试").contains(m))return m;
  return "读取或生成失败，请核对院内服务配置";
 }
 private ResponseEntity<?> response(int status,Object value){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(value);}
 static boolean sameOrigin(HttpServletRequest r){
  String site=r.getHeader("Sec-Fetch-Site");if("cross-site".equalsIgnoreCase(site))return false;
  String origin=r.getHeader("Origin");if(origin==null||origin.isBlank())return true;
  try{URI a=URI.create(origin),b=URI.create(r.getRequestURL().toString());int ap=a.getPort()<0?("https".equals(a.getScheme())?443:80):a.getPort();int bp=b.getPort()<0?("https".equals(b.getScheme())?443:80):b.getPort();return a.getUserInfo()==null&&a.getHost()!=null&&a.getHost().equalsIgnoreCase(b.getHost())&&a.getScheme().equalsIgnoreCase(b.getScheme())&&ap==bp;}catch(Exception ignored){return false;}
 }
}
