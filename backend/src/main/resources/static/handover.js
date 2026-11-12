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
  function renderSources(items, generated){
    sources.replaceChildren();
    $('aiTimelineTip').textContent=generated
      ? '以下为本次送入模型的资料，按时间倒序排列；请结合原系统逐条核对。标记部分送入的资料，完整原文可展开查看。'
      : '以下为本次读取的资料，按时间倒序排列；尚未经模型筛选。';
    if(!items||!items.length){sources.textContent='暂无可用资料，请核对原系统。';return;}
    const sorted=items.map((item,index)=>({item,index})).sort((a,b)=>{
      const at=a.item.time||'',bt=b.item.time||'';
      if(!at||!bt)return at===bt?a.index-b.index:at?-1:1;
      return bt.localeCompare(at)||a.index-b.index;
    });
    const groups=new Map();
    sorted.forEach(({item})=>{const key=formatTime(item.time);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);});
    groups.forEach((entries,time)=>{
      const group=document.createElement('section'),stamp=document.createElement('div'),list=document.createElement('div');
      group.className='ai-timeline-group';stamp.className='ai-timeline-time';stamp.textContent=time;list.className='ai-timeline-list';
      entries.forEach(s=>{
        const entry=document.createElement('article'),header=document.createElement('div'),kind=document.createElement('span'),title=document.createElement('span'),value=document.createElement('div');
        entry.className='ai-timeline-entry';header.className='ai-timeline-entry-head';kind.className='ai-timeline-kind';kind.textContent=s.kind||'资料';title.className='ai-timeline-title';title.textContent=s.title||'';header.append(kind,title);
        if(s.window){const flag=document.createElement('span');flag.className='ai-timeline-flag';flag.textContent='交班时段';header.append(flag);}
        if(s.stale){const flag=document.createElement('span');flag.className='ai-timeline-flag stale';flag.textContent='超过4小时';header.append(flag);}
        if(s.excerpted){const flag=document.createElement('span');flag.className='ai-timeline-flag stale';flag.textContent='模型仅使用前 '+s.sentChars+' 字';header.append(flag);}
        entry.append(header);const text=String(s.text||'').trim();value.className='ai-timeline-value';
        if(text.length>240){value.textContent=text.slice(0,240)+'…';entry.append(value);const details=document.createElement('details'),summary=document.createElement('summary'),full=document.createElement('pre');summary.textContent='查看完整原文';full.textContent=text;details.append(summary,full);entry.append(details);}
        else{value.textContent=text||'无内容';entry.append(value);}
        list.append(entry);
      });group.append(stamp,list);sources.append(group);
    });
  }
  async function run(generate){
    const ctx=state(), dept=ctx.deptCode,id=select.value;cancel();clear();const serial=generation;
    if(!dept||!id||dept!==currentDept||!ctx.patients.some(p=>String(p.id)===id)){status.textContent='科室或患者已变化，请关闭后重新打开。';return;}
    controller=new AbortController();$('aiLoad').disabled=true;$('aiGenerate').disabled=true;status.textContent=generate?'正在读取资料并生成草稿，请稍候…':'正在读取病程和体征…';
    try {const q=new URLSearchParams({dept,patient:id}),resp=await fetch('/api/handover/'+(generate?'generate':'snapshot')+'?'+q,{method:generate?'POST':'GET',cache:'no-store',signal:controller.signal,headers:generate?{'Content-Type':'application/json'}:{}});const data=await resp.json();if(!valid(dept,id,serial))return;if(!resp.ok)throw new Error(data.error||'请求失败');renderWarnings(data.warnings);renderSources(data.sources,generate);if(generate){draft.value=data.draft||'';$('aiConfirm').disabled=!draft.value;status.textContent='草稿已生成，请结合来源逐条核对。';}else status.textContent=`读取完成，交班时段 ${data.from} 至 ${data.to}（不含结束）。`;
    }catch(e){if(valid(dept,id,serial)&&e.name!=='AbortError')status.textContent=e.message||'读取失败，请重试。';}
    finally{if(serial===generation){$('aiLoad').disabled=false;$('aiGenerate').disabled=false;controller=null;}}
  }
  $('btnAiHandover').addEventListener('click',()=>{cancel();clear();const ctx=state();currentDept=ctx.deptCode;select.replaceChildren();ctx.patients.forEach(p=>{if(!p.id)return;const opt=document.createElement('option');opt.value=String(p.id);opt.textContent=(p.bed?String(p.bed)+'床 · ':'')+(p.name||'未命名患者');select.append(opt);});if(!ctx.deptCode||!select.options.length)status.textContent='当前科室没有可选患者。';dlg.showModal();});
  $('aiClose').addEventListener('click',()=>dlg.close());dlg.addEventListener('close',()=>{cancel();clear();select.replaceChildren();currentDept='';$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});
  select.addEventListener('change',()=>{cancel();clear();$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});$('aiLoad').addEventListener('click',()=>run(false));$('aiGenerate').addEventListener('click',()=>run(true));
  draft.addEventListener('input',()=>{$('aiConfirmed').textContent='';$('aiConfirm').disabled=!draft.value.trim();});$('aiConfirm').addEventListener('click',()=>{$('aiConfirmed').textContent='已核对（仅本次页面）';});
})();
