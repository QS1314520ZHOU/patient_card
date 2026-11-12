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
  const chartDate = n => new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(n));
  const svgEl = name => document.createElementNS('http://www.w3.org/2000/svg',name);
  function renderChart(data){
    const root=$('aiChart');root.replaceChildren();
    const picker=document.createElement('div');picker.className='trend-picker';const selected=new Set(data.selected||[]);
    const caption=document.createElement('p');caption.className='ai-tip';caption.textContent=(data.aiSelected?'AI推荐的初选指标':'默认展示指标（非AI推荐）')+'，可手动勾选；各行独立纵轴，横轴为同一时间。';root.append(caption,picker);
    const holder=document.createElement('div');root.append(holder);
    (data.tracks||[]).forEach(t=>{const label=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.checked=selected.has(t.id);label.append(input,document.createTextNode(t.label+'（'+t.unit+'）'));picker.append(label);input.addEventListener('change',()=>{if(input.checked)selected.add(t.id);else selected.delete(t.id);draw();});});
    function draw(){
      holder.replaceChildren();const axis=document.createElement('div'),blank=document.createElement('span'),times=document.createElement('div');axis.className='trend-axis';times.className='trend-axis-times';for(let i=0;i<=4;i++){const tick=document.createElement('span');tick.textContent=chartDate(data.from+(data.to-data.from)*i/4);times.append(tick);}axis.append(blank,times);holder.append(axis);
      const chosen=(data.tracks||[]).filter(t=>selected.has(t.id));if(!chosen.length){const p=document.createElement('p');p.className='trend-no-data';p.textContent='未选择指标；可在上方勾选。';holder.append(p);}
      function x(t){return Math.max(0,Math.min(1000,(t-data.from)/(data.to-data.from)*1000));}
      chosen.forEach(track=>{
        const points=(track.points||[]).filter(p=>Number.isFinite(p.value)&&Number.isFinite(p.time)&&p.time>=data.from&&p.time<=data.to).sort((a,b)=>a.time-b.time);if(!points.length)return;
        const row=document.createElement('div'),label=document.createElement('div'),name=document.createElement('strong'),range=document.createElement('span'),svg=svgEl('svg');row.className='trend-lane';label.className='trend-label';name.textContent=track.label+' · '+track.kind;const vals=points.map(p=>p.value),min=Math.min(...vals),max=Math.max(...vals);range.textContent=(min===max?String(min):min+'～'+max)+' '+track.unit;label.append(name,range);
        svg.classList.add('trend-plot');svg.setAttribute('viewBox','0 0 1000 64');svg.setAttribute('preserveAspectRatio','none');svg.setAttribute('role','img');svg.setAttribute('aria-label',track.label+'，'+points.length+'个原始记录点，详情见下方数据列表');
        const y=v=>max===min?32:54-(v-min)/(max-min)*44;
        if(track.kind==='体征')for(let i=1;i<points.length;i++){if(points[i].time-points[i-1].time>2*60*60*1000)continue;const line=svgEl('line');line.setAttribute('x1',x(points[i-1].time));line.setAttribute('y1',y(points[i-1].value));line.setAttribute('x2',x(points[i].time));line.setAttribute('y2',y(points[i].value));line.setAttribute('stroke','#2a73ca');line.setAttribute('stroke-width','2');line.setAttribute('vector-effect','non-scaling-stroke');svg.append(line);}
        points.forEach(p=>{const dot=svgEl('circle'),title=svgEl('title');dot.setAttribute('cx',x(p.time));dot.setAttribute('cy',y(p.value));dot.setAttribute('r','3');dot.setAttribute('fill',track.kind==='检验'?'#fff':'#2a73ca');dot.setAttribute('stroke','#2a73ca');dot.setAttribute('stroke-width','3');dot.setAttribute('vector-effect','non-scaling-stroke');title.textContent=chartDate(p.time)+' '+p.value+' '+track.unit+(p.rawStatus?'；原始状态：'+p.rawStatus:'');dot.append(title);svg.append(dot);});row.append(label,svg);holder.append(row);
        const details=document.createElement('details'),summary=document.createElement('summary'),ul=document.createElement('ul');details.className='trend-values';summary.textContent='查看'+track.label+'原始记录（'+points.length+'条）';points.slice().reverse().forEach(p=>{const li=document.createElement('li');li.textContent=chartDate(p.time)+'：'+p.value+' '+track.unit+(p.rawStatus?'；原始状态：'+p.rawStatus:'');ul.append(li);});details.append(summary,ul);holder.append(details);
      });
      const meds=data.medications||[];if(meds.length){const row=document.createElement('div'),label=document.createElement('div'),svg=svgEl('svg');row.className='trend-lane';label.className='trend-label';label.textContent='执行用药事件';svg.classList.add('trend-plot');svg.setAttribute('viewBox','0 0 1000 64');svg.setAttribute('preserveAspectRatio','none');svg.setAttribute('role','img');svg.setAttribute('aria-label','执行用药事件 '+meds.length+' 条，详情见下方列表');meds.forEach((m,i)=>{const dot=svgEl('circle'),title=svgEl('title');dot.setAttribute('cx',x(m.time));dot.setAttribute('cy',16+(i%3)*16);dot.setAttribute('r','4');dot.setAttribute('fill','#a25c20');dot.setAttribute('stroke','#a25c20');dot.setAttribute('stroke-width','3');dot.setAttribute('vector-effect','non-scaling-stroke');title.textContent=chartDate(m.time)+' '+m.name+' '+m.action;dot.append(title);svg.append(dot);});row.append(label,svg);holder.append(row);const details=document.createElement('details'),summary=document.createElement('summary'),ul=document.createElement('ul');details.className='trend-values';ul.className='trend-med-list';summary.textContent='查看用药执行事件（'+meds.length+'条）';meds.forEach(m=>{const li=document.createElement('li');li.textContent=chartDate(m.time)+' '+m.name+'：'+m.action;ul.append(li);});details.append(summary,ul);holder.append(details);}
    }draw();
    const note=document.createElement('p');note.className='trend-warning';note.textContent='体征连线仅为视觉提示；检验和当日累计出入量只显示原始记录点，不推算中间值。用药仅显示记录的事件，不代表持续时间。';root.append(note);(data.warnings||[]).forEach(w=>{const p=document.createElement('p');p.className='trend-warning';p.textContent=w;root.append(p);});
  }
  async function loadChart(){
    const ctx=state(),dept=ctx.deptCode,id=select.value,serial=generation;if(!dept||!id||dept!==currentDept||!valid(dept,id,serial)){$('aiChartStatus').textContent='科室或患者已变化，请关闭后重新打开。';return;}
    if(chartController)chartController.abort();const active=new AbortController();chartController=active;$('aiChartGenerate').disabled=true;$('aiChart').replaceChildren();$('aiChartStatus').textContent='正在读取近24小时资料并推荐指标，请稍候…';
    try{const q=new URLSearchParams({dept,patient:id}),res=await fetch('/api/handover/trends?'+q,{method:'POST',cache:'no-store',signal:active.signal});const data=await res.json();if(!valid(dept,id,serial)||chartController!==active)return;if(!res.ok)throw new Error(data.error||'图表读取失败');if(!data.tracks.length&&!data.medications.length){$('aiChartStatus').textContent='未读取到可展示的记录，不代表没有记录。';}else $('aiChartStatus').textContent='近24小时资料，AI推荐仅供人工选择和核对。';renderChart(data);
    }catch(e){if(valid(dept,id,serial)&&e.name!=='AbortError')$('aiChartStatus').textContent=e.message||'图表读取失败';}finally{if(chartController===active){chartController=null;$('aiChartGenerate').disabled=false;}}
  }
  $('aiChartGenerate').addEventListener('click',loadChart);
  $('btnAiHandover').addEventListener('click',()=>{cancel();clear();const ctx=state();currentDept=ctx.deptCode;select.replaceChildren();ctx.patients.forEach(p=>{if(!p.id)return;const opt=document.createElement('option');opt.value=String(p.id);opt.textContent=(p.bed?String(p.bed)+'床 · ':'')+(p.name||'未命名患者');select.append(opt);});if(!ctx.deptCode||!select.options.length)status.textContent='当前科室没有可选患者。';dlg.showModal();});
  $('aiClose').addEventListener('click',()=>dlg.close());dlg.addEventListener('close',()=>{cancel();clear();select.replaceChildren();currentDept='';$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});
  let lastWidth=0;if(window.ResizeObserver)new ResizeObserver(entries=>{const width=entries[0].contentRect.width;if(width!==lastWidth){lastWidth=width;if(dlg.open)resizeDraft();}}).observe(draft);
  select.addEventListener('change',()=>{cancel();clear();$('aiLoad').disabled=false;$('aiGenerate').disabled=false;});$('aiLoad').addEventListener('click',()=>run(false));$('aiGenerate').addEventListener('click',()=>run(true));
  draft.addEventListener('input',()=>{resizeDraft();$('aiConfirmed').textContent='';$('aiConfirm').disabled=!draft.value.trim();});$('aiConfirm').addEventListener('click',()=>{$('aiConfirmed').textContent='已核对（仅本次页面）';});
})();
