(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const dlg=$('aiHandoverDialog'), select=$('aiPatient'), draft=$('aiDraft'), status=$('aiStatus'), warnings=$('aiWarnings'), sources=$('aiSources');
  let generation=0, currentDept='', controller=null, chartController=null;
  function resizeDraft(){draft.style.height='auto';draft.style.height=(draft.scrollHeight+2)+'px';}
  function clear(){ draft.value='';draft.style.height='';warnings.replaceChildren();sources.replaceChildren();$('aiConfirm').disabled=true;$('aiConfirmed').textContent='';status.textContent=''; $('aiChart').replaceChildren();$('aiChartStatus').textContent='';$('aiChartGenerate').disabled=false; }
  function cancel(){generation++;if(controller)controller.abort();controller=null;if(chartController)chartController.abort();chartController=null;}
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
    try {const q=new URLSearchParams({dept,patient:id}),resp=await fetch('/api/handover/'+(generate?'generate':'snapshot')+'?'+q,{method:generate?'POST':'GET',cache:'no-store',signal:controller.signal,headers:generate?{'Content-Type':'application/json'}:{}});const data=await resp.json();if(!valid(dept,id,serial))return;if(!resp.ok)throw new Error(data.error||'请求失败');renderWarnings(data.warnings);renderSources(data.sources,generate);if(generate){draft.value=data.draft||'';resizeDraft();$('aiConfirm').disabled=!draft.value;status.textContent='草稿已生成，请结合来源逐条核对。';}else status.textContent=`读取完成，交班时段 ${data.from} 至 ${data.to}（不含结束）。`;
    }catch(e){if(valid(dept,id,serial)&&e.name!=='AbortError')status.textContent=e.message||'读取失败，请重试。';}
    finally{if(serial===generation){$('aiLoad').disabled=false;$('aiGenerate').disabled=false;controller=null;}}
  }
  const chartDate = n => new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(n));
  const shortDate = n => chartDate(n).replace(/^\d{4}\//,'');
  const el = (tag,cls,text) => {const e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e;};
  function renderChart(data){
    const root=$('aiChart');root.replaceChildren();
    const selected=new Set(data.selected||[]),selectedDrugs=new Set(data.selectedDrugs||[]),metrics=data.tracks||[],drugs=data.medications||[];
    const intro=el('div','trend-intro'),period=el('div','trend-period'),badge=el('span',data.aiSelected?'trend-badge':'trend-badge fallback',data.aiSelected?'AI 推荐':'默认展示');period.append(el('strong',null,'患者近 24 小时'),el('span',null,chartDate(data.from)+' — '+chartDate(data.to)+'（北京时间）'));intro.append(period,badge);root.append(intro);
    const sub=el('p','trend-sub',data.aiSelected?'AI 根据诊断、近期病程和部分实际记录推荐；理由仅供参考，需结合原系统核对。':'AI 推荐未完成：以下为默认指标，药物未自动选择。可手动调整。');root.append(sub);
    const choices=el('details','trend-options'),summary=el('summary',null,'调整显示的指标和药物'),options=el('div','trend-options-content');choices.append(summary,options);root.append(choices);
    function optionSection(label,items,set,aiIds){const group=el('div','trend-option-group');group.append(el('h5',null,label));const list=el('div','trend-option-list');if(!items.length)list.append(el('p','trend-empty','当前没有可选记录。'));items.forEach(item=>{const labelEl=el('label','trend-option'),checkbox=document.createElement('input');checkbox.type='checkbox';checkbox.checked=set.has(item.id);const text=el('span',null,(item.label||item.name)+(item.unit?'（'+item.unit+'）':'')+(data.aiSelected&&aiIds.includes(item.id)?' · AI 推荐':''));labelEl.append(checkbox,text);list.append(labelEl);checkbox.addEventListener('change',()=>{if(checkbox.checked)set.add(item.id);else set.delete(item.id);draw();});});group.append(list);options.append(group);}
    optionSection('指标',metrics,selected,data.selected||[]);optionSection('用药',drugs,selectedDrugs,data.selectedDrugs||[]);
    const plot=el('div','trend-surface');root.append(plot);
    function pct(t){return Math.max(0,Math.min(100,(t-data.from)/(data.to-data.from)*100));}
    function axis(){const row=el('div','trend-axis'),label=el('div','trend-axis-label','时间'),ticks=el('div','trend-ticks');for(let i=0;i<=4;i++){const tick=el('span','trend-tick tick-'+i,shortDate(data.from+(data.to-data.from)*i/4));tick.style.left=(i*25)+'%';ticks.append(tick);}row.append(label,ticks);return row;}
    function reason(text){if(!text)return null;return el('p','trend-reason','AI 推荐理由（待核对）：'+text);}
    function details(title,lines){const box=el('details','trend-details'),summ=el('summary',null,title),list=el('ul');lines.forEach(t=>list.append(el('li',null,t)));box.append(summ,list);return box;}
    function pointPlot(track,pts){
      const cell=el('div','trend-plot-cell'),svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 1000 64');svg.setAttribute('preserveAspectRatio','none');svg.setAttribute('aria-hidden','true');const vals=pts.map(p=>p.value),min=Math.min(...vals),max=Math.max(...vals),y=v=>max===min?32:53-(v-min)/(max-min)*42;
      if(track.kind==='体征')for(let i=1;i<pts.length;i++){if(pts[i].time-pts[i-1].time>7200000)continue;const l=document.createElementNS('http://www.w3.org/2000/svg','line');l.setAttribute('x1',pct(pts[i-1].time)*10);l.setAttribute('x2',pct(pts[i].time)*10);l.setAttribute('y1',y(pts[i-1].value));l.setAttribute('y2',y(pts[i].value));l.setAttribute('stroke','currentColor');l.setAttribute('stroke-width','2');l.setAttribute('vector-effect','non-scaling-stroke');svg.append(l);}
      cell.append(svg);pts.forEach(p=>{const point=el('span',track.kind==='检验'?'trend-point lab':'trend-point');point.style.left=pct(p.time)+'%';point.style.top=(y(p.value)/64*100)+'%';point.title=chartDate(p.time)+'：'+p.value+' '+track.unit+(p.rawStatus?'；原始状态：'+p.rawStatus:'');cell.append(point);});return cell;
    }
    function draw(){
      plot.replaceChildren();plot.append(axis());const picked=metrics.filter(t=>selected.has(t.id)),pickedDrugs=drugs.filter(d=>selectedDrugs.has(d.id));
      plot.append(el('div','trend-group-label','体征、检验与出入量'));
      if(!picked.length)plot.append(el('p','trend-empty','尚未选择指标，可在上方调整。'));
      picked.forEach(t=>{const pts=(t.points||[]).filter(p=>Number.isFinite(p.time)&&Number.isFinite(p.value)&&p.time>=data.from&&p.time<=data.to).sort((a,b)=>a.time-b.time);if(!pts.length)return;const wrap=el('section','trend-track'),row=el('div','trend-track-row'),info=el('div','trend-track-label'),last=pts[pts.length-1];const vals=pts.map(p=>p.value),lo=Math.min(...vals),hi=Math.max(...vals);info.append(el('strong',null,t.label),el('span','trend-meta',t.kind+' · '+t.unit),el('span','trend-latest','最近 '+last.value+' '+t.unit),el('span','trend-meta','记录范围 '+(lo===hi?lo:lo+'–'+hi))); const graph=pointPlot(t,pts);graph.setAttribute('role','img');graph.setAttribute('aria-label',t.label+'，'+pts.length+' 个记录，最近 '+last.value+' '+t.unit+'；具体记录可展开查看');row.append(info,graph);wrap.append(row);const why=reason((data.metricReasons||{})[t.id]);if(why)wrap.append(why);wrap.append(details('查看 '+pts.length+' 条原始记录',pts.slice().reverse().map(p=>chartDate(p.time)+'  '+p.value+' '+t.unit+(p.rawStatus?' · 原始状态：'+p.rawStatus:''))));plot.append(wrap);});
      const header=el('div','trend-group-label drug-label');header.append(el('span',null,'关键用药'),el('small',null,'蓝色：开始至暂停/停止 · 斜纹：暂停 · 虚线：待核对'));plot.append(header);if(pickedDrugs.length)plot.append(axis());
      if(!pickedDrugs.length)plot.append(el('p','trend-empty',data.aiSelected?'AI 未推荐用药，或尚未选择；可在上方查看并手动选择。':'尚未选择用药，可在上方手动选择。'));
      pickedDrugs.forEach(d=>{const wrap=el('section','trend-track drug-track'),reasonEl=reason((data.drugReasons||{})[d.id]);(d.records||[]).forEach((rec,i)=>{const row=el('div','trend-track-row'),info=el('div','trend-track-label');info.append(el('strong',null,d.name),el('span','trend-meta','执行记录 '+(i+1)+' / '+d.records.length));if(rec.status)info.append(el('span','trend-meta','原始状态：'+rec.status));if(rec.hisStatus)info.append(el('span','trend-raw-status','HIS 状态：'+rec.hisStatus+'（请核对）'));const cell=el('div','trend-plot-cell drug-plot');cell.setAttribute('role','img');cell.setAttribute('aria-label',d.name+' 执行记录 '+(i+1)+'，详情见操作列表');(rec.segments||[]).forEach(seg=>{const bar=el('span','trend-segment '+(seg.state==='运行'?'run':seg.state==='暂停'?'pause':'unknown'));bar.style.left=pct(seg.from)+'%';bar.style.width=Math.max(0,pct(seg.to)-pct(seg.from))+'%';bar.title=seg.state+'：'+chartDate(seg.from)+' — '+chartDate(seg.to)+'；依操作记录推导，须核对';cell.append(bar);});(rec.actions||[]).forEach(a=>{const mark=el('span','trend-action');mark.style.left=pct(a.time)+'%';mark.title=chartDate(a.time)+' '+a.action+(a.speed?' · 速度 '+a.speed:'');cell.append(mark);});row.append(info,cell);wrap.append(row);let lines=(rec.actions||[]).map(a=>chartDate(a.time)+' · '+a.action+(a.rawAction?'（原始：'+a.rawAction+'）':'')+(a.speed?' · 记录速度 '+a.speed:''));lines=lines.concat((rec.issues||[]).map(x=>'注意：'+x));if(!lines.length)lines=['无该时段可显示的操作记录，请核对原系统。'];wrap.append(details('查看执行记录 '+(i+1)+' 的操作与状态',lines));});if(reasonEl)wrap.append(reasonEl);plot.append(wrap);});
    }
    draw();const foot=el('div','trend-notes');foot.append(el('p',null,'体征连线只连接相隔不超过两小时的记录，检验与日累计出入量仅显示原始点。用药区间根据操作记录推导，并非实际给药或疗效证明；虚线表示未确认状态。'));const more=el('details','trend-notes-details'),sum=el('summary',null,'查看数据范围与注意事项');more.append(sum);(data.warnings||[]).forEach(w=>more.append(el('p',null,w)));foot.append(more);root.append(foot);
  }
  async function loadChart(){
    const ctx=state(),dept=ctx.deptCode,id=select.value,serial=generation;if(!dept||!id||dept!==currentDept||!valid(dept,id,serial)){$('aiChartStatus').textContent='科室或患者已变化，请关闭后重新打开。';return;}
    if(chartController)chartController.abort();const active=new AbortController();chartController=active;$('aiChartGenerate').disabled=true;$('aiChart').replaceChildren();$('aiChartStatus').textContent='正在读取近24小时资料并推荐指标，请稍候…';
    try{const q=new URLSearchParams({dept,patient:id}),res=await fetch('/api/handover/trends?'+q,{method:'POST',cache:'no-store',signal:active.signal});const data=await res.json();if(!valid(dept,id,serial)||chartController!==active)return;if(!res.ok)throw new Error(data.error||'图表读取失败');if(!data.tracks.length&&!data.medications.length){$('aiChartStatus').textContent='未读取到可展示的记录，不代表没有记录。';}else $('aiChartStatus').textContent=data.aiSelected?'AI 推荐已完成，请结合原系统核对。':'AI 推荐未完成，请手动选择并核对。';renderChart(data);
    }catch(e){if(valid(dept,id,serial)&&e.name!=='AbortError')$('aiChartStatus').textContent=e.message||'图表读取失败';}finally{if(chartController===active){chartController=null;$('aiChartGenerate').disabled=false;}}
  }
  $('aiChartGenerate').addEventListener('click',loadChart);
  $('btnAiHandover').addEventListener('click',()=>{cancel();clear();const ctx=state();currentDept=ctx.deptCode;select.replaceChildren();ctx.patients.forEach(p=>{if(!p.id)return;const opt=document.createElement('option');opt.value=String(p.id);opt.textContent=(p.bed?String(p.bed)+'床 · ':'')+(p.name||'未命名患者');select.append(opt);});if(!ctx.deptCode||!select.options.length)status.textContent='当前科室没有可选患者。';dlg.showModal();});
  $('aiClose').addEventListener('click',()=>dlg.close());dlg.addEventListener('close',()=>{cancel();clear();select.replaceChildren();currentDept='';$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});
  let lastWidth=0;if(window.ResizeObserver)new ResizeObserver(entries=>{const width=entries[0].contentRect.width;if(width!==lastWidth){lastWidth=width;if(dlg.open)resizeDraft();}}).observe(draft);
  select.addEventListener('change',()=>{cancel();clear();$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});$('aiLoad').addEventListener('click',()=>run(false));$('aiGenerate').addEventListener('click',()=>run(true));
  draft.addEventListener('input',()=>{resizeDraft();$('aiConfirmed').textContent='';$('aiConfirm').disabled=!draft.value.trim();});$('aiConfirm').addEventListener('click',()=>{$('aiConfirmed').textContent='已核对（仅本次页面）';});
})();
