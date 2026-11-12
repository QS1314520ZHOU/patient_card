(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const dlg=$('aiHandoverDialog'), select=$('aiPatient'), draft=$('aiDraft'), status=$('aiStatus'), warnings=$('aiWarnings'), sources=$('aiSources');
  let generation=0, currentDept='', controller=null;
  function clear(){ draft.value='';warnings.replaceChildren();sources.replaceChildren();$('aiConfirm').disabled=true;$('aiConfirmed').textContent='';status.textContent=''; }
  function cancel(){generation++;if(controller)controller.abort();controller=null;}
  function state(){return window.patientCardHandoverContext ? window.patientCardHandoverContext() : {deptCode:'',patients:[]};}
  function valid(dept,id,serial){const ctx=state();return dlg.open&&serial===generation&&dept===ctx.deptCode&&id===select.value&&ctx.patients.some(p=>String(p.id)===id);}
  function renderWarnings(items){warnings.replaceChildren();(items||[]).forEach(t=>{const p=document.createElement('p');p.textContent='• '+t;warnings.append(p);});}
  const formatTime = value => value ? String(value).replace('T',' ').slice(0,16) : '时间未知';
  function renderSources(items){sources.replaceChildren();(items||[]).forEach(s=>{const d=document.createElement('details'), h=document.createElement('summary'), body=document.createElement('pre');h.textContent=`[${s.id}] ${s.kind} · ${s.title || ''} · ${formatTime(s.time)}${s.window?' · 交班时段':''}${s.stale?' · 超过4小时':''}${s.excerpted?' · 模型仅使用前 '+s.sentChars+' 字':''}`;body.textContent=s.text||'';d.append(h,body);sources.append(d);});if(!items||!items.length)sources.textContent='暂无可用来源，请核对原系统。';}
  async function run(generate){
    const ctx=state(), dept=ctx.deptCode,id=select.value;cancel();clear();const serial=generation;
    if(!dept||!id||dept!==currentDept||!ctx.patients.some(p=>String(p.id)===id)){status.textContent='科室或患者已变化，请关闭后重新打开。';return;}
    controller=new AbortController();$('aiLoad').disabled=true;$('aiGenerate').disabled=true;status.textContent=generate?'正在读取资料并生成草稿，请稍候…':'正在读取病程和体征…';
    try {const q=new URLSearchParams({dept,patient:id}),resp=await fetch('/api/handover/'+(generate?'generate':'snapshot')+'?'+q,{method:generate?'POST':'GET',cache:'no-store',signal:controller.signal,headers:generate?{'Content-Type':'application/json'}:{}});const data=await resp.json();if(!valid(dept,id,serial))return;if(!resp.ok)throw new Error(data.error||'请求失败');renderWarnings(data.warnings);renderSources(data.sources);if(generate){draft.value=data.draft||'';$('aiConfirm').disabled=!draft.value;status.textContent='草稿已生成，请结合来源逐条核对。';}else status.textContent=`读取完成，交班时段 ${data.from} 至 ${data.to}（不含结束）。`;
    }catch(e){if(valid(dept,id,serial)&&e.name!=='AbortError')status.textContent=e.message||'读取失败，请重试。';}
    finally{if(serial===generation){$('aiLoad').disabled=false;$('aiGenerate').disabled=false;controller=null;}}
  }
  $('btnAiHandover').addEventListener('click',()=>{cancel();clear();const ctx=state();currentDept=ctx.deptCode;select.replaceChildren();ctx.patients.forEach(p=>{if(!p.id)return;const opt=document.createElement('option');opt.value=String(p.id);opt.textContent=(p.bed?String(p.bed)+'床 · ':'')+(p.name||'未命名患者');select.append(opt);});if(!ctx.deptCode||!select.options.length)status.textContent='当前科室没有可选患者。';dlg.showModal();});
  $('aiClose').addEventListener('click',()=>dlg.close());dlg.addEventListener('close',()=>{cancel();clear();select.replaceChildren();currentDept='';$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});
  select.addEventListener('change',()=>{cancel();clear();$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});$('aiLoad').addEventListener('click',()=>run(false));$('aiGenerate').addEventListener('click',()=>run(true));
  draft.addEventListener('input',()=>{$('aiConfirmed').textContent='';$('aiConfirm').disabled=!draft.value.trim();});$('aiConfirm').addEventListener('click',()=>{$('aiConfirmed').textContent='已核对（仅本次页面）';});
})();
