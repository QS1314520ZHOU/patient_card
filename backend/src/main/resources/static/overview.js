(() => {
  'use strict';

  /* ==========================================================================
     重症医学科 · 早交班大屏（前端纯原生 HTML/CSS/ES6）
     - 数据：GET /api/v1/icu-overview 快照；file:// 双击打开时回落到离线预览数据
     - 渲染原则：只输出临床可读文案，绝不输出任何技术字段名/调试信息
     ========================================================================== */

  // ================= 部署配置 =================
  // 宿主 Origin 白名单：留空 = 联调模式接受任意来源；正式部署填 ['http://宿主地址']
  const ALLOWED_ORIGINS = [];
  const HANDSHAKE_INTERVAL_MS = 2000;
  const HANDSHAKE_MAX_RETRY = 3;
  const HANDSHAKE_TIMEOUT_MS = 10000;
  const DEFAULT_REFRESH_MS = 60000;
  const CAROUSEL_INTERVAL_MS = 30000;

  /**
   * 线下手工待办（外出检查 / 拔管计划等暂无结构化数据源的重点事项）。
   * 科室可在此手工维护；接口若返回 manualTodos 字段则以后端为准。
   * 例：{ cat: '外出检查', bed: '01', text: '09:30 增强CT（需配转运呼吸机）', level: 'info' }
   */
  const MANUAL_TODOS = [];

  // ================= 状态 =================
  const state = {
    deptCode: '',
    snapshot: null,
    sequence: 0,
    handoverSequence: 0,
    refreshTimer: null,
    refreshMs: DEFAULT_REFRESH_MS,
    carouselTimer: null,
    carouselOn: false,
    handoverMode: false,
    sortMode: 'group',
    windowPreset: 'prevShift',
    viewMode: 'both',
    filter: 'all',
    collapsed: new Set(),
    pinnedId: null,
    hoverId: null,
    autoPinned: false,
    handshakeTried: 0,
    handshakeTimer: null,
    contextResolved: false,
    groupsHover: false,
    preview: false,
    doneTodos: new Set(),
  };

  const $ = (id) => document.getElementById(id);

  /** 患者字段来自源系统，进入 innerHTML 前必须转义（含属性值） */
  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  // ================= 时钟 =================
  function pad(n) { return String(n).padStart(2, '0'); }
  function toDate(value) {
    if (!value) return null;
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  function fmtTime(value) {
    const d = toDate(value);
    if (!d) return '—';
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  function fmtShort(value) {
    const d = toDate(value);
    if (!d) return '—';
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  // ================= 交班视图（只换展示，不换统计口径） =================
  const VIEW_CONFIG = {
    both: {
      label: '综合',
      meta: ['mrn', 'nurse'],
      vitals: { io: true, urine: true, rass: true, gcs: true, sofa: true, apache: true },
      tags: { fall: true, pressure: true, vte: true, nutrition: true, pipe: true },
      aux: ['pipeHigh', 'nutritionHigh', 'vteHigh', 'fallUnassessed', 'pressureUnassessed',
        'attention', 'criticalCarried', 'criticalHandled', 'transferred', 'discharged', 'died',
        'surgery', 'transferIn'],
    },
    doctor: {
      label: '医生',
      meta: ['mrn', 'doctor', 'group', 'admission'],
      vitals: { io: true, urine: true, rass: true, gcs: true, sofa: true, apache: true },
      tags: { fall: false, pressure: false, nutrition: false, vte: true, pipe: true },
      aux: ['vteHigh', 'criticalCarried', 'criticalHandled', 'attention', 'transferred',
        'discharged', 'died', 'surgery', 'transferIn'],
    },
    nurse: {
      label: '护理',
      meta: ['mrn', 'nurse', 'doctor'],
      vitals: { io: true, urine: true, rass: true, gcs: true, sofa: false, apache: false },
      tags: { fall: true, pressure: true, vte: true, nutrition: true, pipe: true },
      aux: ['pipeHigh', 'nutritionHigh', 'fallUnassessed', 'pressureUnassessed',
        'criticalCarried', 'criticalHandled', 'attention', 'surgery', 'transferIn'],
    },
  };
  function viewConfig() { return VIEW_CONFIG[state.viewMode] || VIEW_CONFIG.both; }

  // ================= 快速筛选 =================
  function severityScore(p) {
    let score = 0;
    if (p.ventilator) score += 3;
    if (p.crrt) score += 3;
    if (p.organSupport) score += 3;
    if (p.vasoactive) score += 3;
    if (p.uexHigh) score += 4;
    if (p.criticalPending > 0) score += 2;
    if (p.airwayInUse) score += 2;
    if (p.pipeLevel === 'high') score += 2;
    if (p.rescueNight) score += 2;
    if (p.fallLevel === 'high') score += 1;
    if (p.pressureLevel === 'high') score += 1;
    return score;
  }

  const FILTERS = {
    all: { label: '全部', test: () => true },
    vent: { label: '有创通气', test: (p) => !!p.ventilator },
    shock: { label: '休克/升压药', test: (p) => !!p.vasoactive },
    critical: { label: '极危重', test: (p) => severityScore(p) >= 6 || (p.criticalPending || 0) > 0 },
    new: { label: '新入科', test: (p) => !!p.newAdmission },
  };

  // ================= 提示 / 状态层 =================
  function showState(title, copy, kind, retryFn) {
    const overlay = $('stateOverlay');
    overlay.hidden = false;
    const titleEl = $('stateTitle');
    titleEl.textContent = title;
    titleEl.classList.toggle('error', kind === 'error');
    $('stateCopy').textContent = copy || '';
    const retry = $('stateRetry');
    retry.hidden = !retryFn;
    retry.onclick = retryFn || null;
  }
  function hideState() { $('stateOverlay').hidden = true; }

  let toastTimer = null;
  function toast(message) {
    const el = $('toast');
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 4000);
  }

  // ================= postMessage 上下文 =================
  function isOriginAllowed(origin) {
    return ALLOWED_ORIGINS.length === 0 || ALLOWED_ORIGINS.includes(origin);
  }

  function extractDeptCode(raw) {
    let data = raw;
    if (typeof data === 'string') {
      const text = data.trim();
      if (!text) return '';
      try { data = JSON.parse(text); } catch (_) { return text; }
    }
    if (!data || typeof data !== 'object') return '';
    if (data.type === 'SmartCare') {
      const patient = data.patient || {};
      return String(patient.deptCode || patient.deptCode2 || '').trim();
    }
    if (data.type === 'HOSPITAL_CONTEXT' && data.payload) {
      return String(data.payload.deptCode || '').trim();
    }
    const value = data.deptCode ?? data.departmentCode ?? data.dept_code
      ?? data.data?.deptCode ?? data.data?.departmentCode ?? data.params?.deptCode;
    return value === undefined || value === null ? '' : String(value).trim();
  }

  function onHostMessage(event) {
    if (!isOriginAllowed(event.origin)) return;
    if (window.parent !== window && event.source !== window.parent) return;
    const data = event.data;
    if (data && data.type === 'HOSPITAL_LOGOUT') {
      resetToWaiting('宿主已退出，交班数据已清理');
      return;
    }
    const deptCode = extractDeptCode(data);
    if (deptCode) {
      stopHandshake();
      state.contextResolved = true;
      switchDept(deptCode);
    }
  }

  function sendReady() {
    if (window.parent !== window) {
      window.parent.postMessage({ type: 'SmartCareReady' }, ALLOWED_ORIGINS[0] || '*');
    }
  }

  function startHandshake() {
    stopHandshake();
    state.handshakeTried = 0;
    const tryOnce = () => {
      state.handshakeTried += 1;
      sendReady();
      if (state.handshakeTried >= HANDSHAKE_MAX_RETRY) {
        clearInterval(state.handshakeTimer);
        state.handshakeTimer = null;
        if (!state.contextResolved) {
          showState('未获取科室数据',
            '暂时无法获取科室信息，请刷新页面或重新打开本功能。',
            'error', () => { hideState(); startHandshake(); });
        }
      }
    };
    tryOnce();
    state.handshakeTimer = setInterval(tryOnce, HANDSHAKE_INTERVAL_MS);
    setTimeout(() => {
      if (state.handshakeTimer) {
        clearInterval(state.handshakeTimer);
        state.handshakeTimer = null;
      }
    }, HANDSHAKE_TIMEOUT_MS);
  }

  function stopHandshake() {
    if (state.handshakeTimer) {
      clearInterval(state.handshakeTimer);
      state.handshakeTimer = null;
    }
  }

  function urlParam(name) {
    return new URLSearchParams(window.location.search).get(name) || '';
  }

  /** 离线预览：直接双击打开（file://）或显式 ?demo=1 */
  function isPreview() {
    return window.location.protocol === 'file:' || urlParam('demo') === '1';
  }

  function resetToWaiting(copy) {
    stopRefresh();
    state.sequence += 1;
    state.handoverSequence += 1;
    state.deptCode = '';
    state.snapshot = null;
    state.pinnedId = null;
    state.hoverId = null;
    state.autoPinned = false;
    state.contextResolved = false;
    clearScreens();
    showState('等待科室数据', copy || '正在获取当前科室的交班数据…', null, null);
    startHandshake();
  }

  function clearScreens() {
    $('groupsScroll').innerHTML = '';
    $('kpiItems').innerHTML = '';
    $('auxBar').innerHTML = '';
    $('todoList').innerHTML = '';
    $('todoCount').textContent = '';
    $('inspector').innerHTML = '';
    $('btnInspectorDetail').hidden = true;
    $('deptTitle').textContent = '重症医学科 · 早交班';
    $('shiftPill').textContent = '—';
    $('asOf').textContent = '数据更新 —';
    $('toolbarNote').textContent = '';
    $('snapshotText').textContent = '';
    renderChangesBadge();
  }

  function switchDept(deptCode) {
    if (!deptCode || deptCode === state.deptCode) return;
    state.deptCode = deptCode;
    state.sequence += 1;
    state.snapshot = null;
    clearScreens();
    showState('正在加载交班数据', '正在获取本科室床位与患者快照…', null, null);
    startRefresh(true);
  }

  // ================= 数据加载 =================
  function previewSnapshot() {
    const demo = window.ICU_DEMO_SNAPSHOT;
    if (!demo) return null;
    return Object.assign({}, demo.meta, demo.snapshot, { patients: demo.patients });
  }

  async function loadSnapshot(manual) {
    if (state.preview) {
      const snap = previewSnapshot();
      if (!snap) {
        showState('缺少预览数据', '未找到离线预览数据文件，无法在本地打开本页。', 'error', null);
        return;
      }
      state.sequence += 1;
      state.snapshot = snap;
      state.refreshMs = (snap.refreshSeconds || 60) * 1000;
      renderAll();
      hideState();
      if (manual) toast('离线演示数据（未连接后端）');
      return;
    }

    if (!state.deptCode) return;
    const seq = ++state.sequence;
    // 窗口只传口径，起止时间由后端按班次配置计算，避免前端时钟偏差进入统计
    const url = `/api/v1/icu-overview?deptCode=${encodeURIComponent(state.deptCode)}`
      + `&windowPreset=${encodeURIComponent(state.windowPreset)}`;
    try {
      const response = await fetch(url, { cache: 'no-store' });
      if (seq !== state.sequence) return;
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.error || `HTTP ${response.status}`);
      }
      const snapshot = await response.json();
      if (seq !== state.sequence) return;
      const firstLoad = !state.snapshot;
      const sameSnapshot = state.snapshot && state.snapshot.snapshotId === snapshot.snapshotId;
      state.snapshot = snapshot;
      state.refreshMs = (snapshot.refreshSeconds || 60) * 1000;
      renderAll();
      hideState();
      if (!firstLoad && !sameSnapshot && state.handoverMode) {
        toast('数据已更新（交班模式下不跳动焦点）');
      }
      restartRefreshTimer();
    } catch (error) {
      if (seq !== state.sequence) return;
      if (!state.snapshot) {
        showState('数据加载失败', '交班数据暂时无法获取，请稍后重试。', 'error',
          () => { hideState(); loadSnapshot(true); });
      } else {
        toast('刷新失败，当前显示最后一次成功获取的数据');
      }
    }
  }

  function startRefresh(immediate) {
    stopRefresh();
    if (immediate) loadSnapshot(true);
    restartRefreshTimer();
  }
  function restartRefreshTimer() {
    clearInterval(state.refreshTimer);
    if (state.preview) return; // 离线预览不轮询
    state.refreshTimer = setInterval(() => loadSnapshot(false), state.refreshMs || DEFAULT_REFRESH_MS);
  }
  function stopRefresh() {
    clearInterval(state.refreshTimer);
    state.refreshTimer = null;
  }

  // ================= 指标 =================
  function metric(key) {
    const snap = state.snapshot;
    if (!snap) return null;
    return (snap.metrics || []).find((m) => m.metricKey === key) || null;
  }
  function metricValue(key) {
    const m = metric(key);
    return m && m.value !== null && m.value !== undefined ? m.value : null;
  }

  const DRILLABLE = new Set(['inDept', 'ventilator', 'fallHigh', 'pressureHigh', 'newAdmissions',
    'pipeHigh', 'nutritionHigh', 'vteHigh', 'fallUnassessed', 'pressureUnassessed',
    'surgery', 'transferIn', 'attention', 'transferred', 'discharged', 'died',
    'criticalPending', 'criticalCarried', 'criticalHandled']);

  // ================= 顶栏 =================
  function groupName(raw) {
    const name = String(raw == null ? '' : raw).trim() || '未分组';
    return /^(一|二|三|四|五|六)组$/.test(name) ? '医疗' + name : name;
  }

  function renderHeader() {
    const snap = state.snapshot;
    if (!snap) return;
    const deptName = (snap.scope && snap.scope.deptName) || '重症医学科';
    $('deptTitle').textContent = `${deptName} · 早交班`;

    const from = snap.eventFrom ? fmtShort(snap.eventFrom) : '—';
    const to = snap.eventTo ? fmtShort(snap.eventTo) : '—';
    const label = snap.eventWindowLabel || '事件窗';
    $('shiftPill').textContent = `${label} ${from} – ${to}`;
    $('shiftPill').title = `交班事件窗（含起点不含终点）：${fmtTime(snap.eventFrom)} ~ ${fmtTime(snap.eventTo)}`;
    $('asOf').textContent = `数据更新 ${snap.asOf ? fmtTime(snap.asOf) : '—'}`;
    $('demoBadge').hidden = !state.preview;

    const notes = (snap.quality && snap.quality.notes) || [];
    const degraded = snap.quality && snap.quality.overall && snap.quality.overall !== 'ok';
    $('qualityText').textContent = '本屏用于交班辅助，非床旁实时监护。'
      + (degraded && notes.length ? ' 部分指标来源缺失，以标记为准。' : '');
    $('snapshotText').textContent = state.preview
      ? '离线演示数据（不自动刷新）'
      : `自动刷新 ${snap.refreshSeconds || 60}s`;
  }

  // ================= KPI 聚合条 =================
  function kpiItem(cfg) {
    const el = document.createElement('div');
    const canDrill = cfg.key && DRILLABLE.has(cfg.key) && cfg.drill !== false;
    el.className = 'kpi-item' + (canDrill ? ' clickable' : '') + (cfg.alert ? ' alert' : '');
    const value = cfg.value === null || cfg.value === undefined ? '—' : cfg.value;
    el.innerHTML = `<span class="kpi-label">${esc(cfg.label)}</span>`
      + `<span class="kpi-value">${esc(String(value))}`
      + (cfg.em ? `<em>${esc(cfg.em)}</em>` : '')
      + (cfg.extra ? `<span class="kpi-extra">${esc(cfg.extra)}</span>` : '')
      + `</span>`;
    if (cfg.title) el.title = cfg.title;
    if (canDrill) el.addEventListener('click', () => openDrill(cfg.key));
    return el;
  }

  function renderKpis() {
    const row = $('kpiItems');
    row.innerHTML = '';
    const snap = state.snapshot;
    if (!snap) return;

    const inDept = metricValue('inDept');
    const beds = metricValue('openBeds');
    const occ = metricValue('occupancyPercent');
    const empties = emptyBedsOf(snap);

    const items = [
      {
        label: '在科人数',
        value: inDept === null ? '—' : `${inDept}/${beds === null ? '—' : beds}床`,
        em: occ === null ? '' : `(${occ}%)`,
        key: 'inDept',
        title: `${metric('inDept') ? metric('inDept').label : '在科人数'} · 床位占用比例`,
      },
      {
        label: '空床',
        value: empties.length ? `${empties.length}张` : '0张',
        extra: empties.length ? empties.join(', ') : '',
        title: empties.length ? `可调度空床：${empties.join('、')}` : '当前无空床',
      },
      { label: '呼吸机在用', value: metricValue('ventilator'), key: 'ventilator' },
      { label: '跌倒高风险', value: metricValue('fallHigh'), key: 'fallHigh' },
      { label: '压疮高风险', value: metricValue('pressureHigh'), key: 'pressureHigh' },
      { label: '本班新收', value: metricValue('newAdmissions'), key: 'newAdmissions' },
      {
        label: '待闭环危急值',
        value: metricValue('criticalPending'),
        key: 'criticalPending',
        alert: (metricValue('criticalPending') || 0) > 0,
        title: '交班需逐项督办的未闭环危急值',
      },
    ];

    for (const cfg of items) row.appendChild(kpiItem(cfg));
  }

  // ================= 底栏辅助指标 =================
  function renderAux() {
    const bar = $('auxBar');
    bar.innerHTML = '';
    const visible = new Set(viewConfig().aux);
    for (const key of visible) {
      const m = metric(key);
      if (!m) continue;
      const value = m.value === null || m.value === undefined ? '—' : m.value;
      const canDrill = DRILLABLE.has(key) && m.drilldownAvailable;
      const el = document.createElement(canDrill ? 'button' : 'span');
      el.type = canDrill ? 'button' : undefined;
      const tone = (m.qualityStatus === 'unavailable') ? ''
        : (key.startsWith('critical') && value > 0) ? ' danger'
          : (key === 'fallUnassessed' || key === 'pressureUnassessed' || key === 'attention'
            || key === 'died' || key === 'nutritionHigh') ? ' warn' : '';
      el.className = 'aux-chip' + tone + (canDrill ? ' clickable' : ' static');
      const affected = (m.unit === '项' && m.affectedPatientCount != null && m.affectedPatientCount)
        ? `（涉及 ${m.affectedPatientCount} 人）` : '';
      el.title = canDrill ? `${m.label} · 点击查看名单` : m.label;
      el.innerHTML = `${esc(m.label)}<b>${esc(String(value))}</b>${esc(affected)}`;
      if (canDrill) el.addEventListener('click', () => openDrill(key));
      bar.appendChild(el);
    }
  }

  // ================= 患者卡 =================
  function patientById(id) {
    if (!id || !state.snapshot) return null;
    return (state.snapshot.patients || []).find((p) => p.id === id) || null;
  }

  function bedSortValue(value) {
    const digits = String(value == null ? '' : value).replace(/[^0-9]/g, '');
    const parsed = parseInt(digits, 10);
    return Number.isNaN(parsed) ? Number.MAX_SAFE_INTEGER : parsed;
  }

  function hasAllergy(p) {
    const raw = (p.allergy || '').trim();
    return !!raw && !/^(无|否|没有|不详)/.test(raw);
  }

  function emptyBedsOf(snap) {
    const occupied = new Set((snap.patients || []).map((p) => p.bed).filter(Boolean));
    return (snap.beds || [])
      .map((b) => b.showName || b.hisName)
      .filter((name) => name && !occupied.has(name))
      .sort((a, b) => bedSortValue(a) - bedSortValue(b));
  }

  function chip(cls, label, value, title, raw) {
    const empty = value === undefined || value === null || value === '';
    const inner = empty ? '' : `<b>${raw ? String(value) : esc(String(value))}</b>`;
    return `<span class="chip ${cls}"${title ? ` title="${esc(title)}"` : ''}>${esc(label)}${inner}</span>`;
  }

  /** 支持与评分行：RASS / GCS / SOFA / APACHE II + 微泵与生命支持 */
  function supportChips(p) {
    const v = viewConfig().vitals;
    const out = [];

    if (v.rass && p.rass != null) {
      out.push(chip('chip-score', 'RASS', p.rass, `RASS 评估 ${fmtTime(p.rassTime)}`));
    }
    if (v.gcs && p.gcs != null) {
      out.push(chip('chip-score', 'GCS', p.gcs, `GCS 评估 ${fmtTime(p.gcsTime)}`));
    }
    if (v.sofa && p.sofa != null) {
      const arrow = p.sofaDelta > 0 ? `<i class="up">↑${p.sofaDelta}</i>`
        : p.sofaDelta < 0 ? `<i class="down">↓${Math.abs(p.sofaDelta)}</i>` : '';
      out.push(chip('chip-score', 'SOFA', `${p.sofa}${arrow}`,
        `SOFA ${fmtTime(p.sofaTime)}（较上次${p.sofaDelta > 0 ? '恶化' : p.sofaDelta < 0 ? '好转' : '持平'}）`, true));
    }
    if (v.apache && p.apacheII != null) {
      const arrow = p.apacheDelta > 0 ? `<i class="up">↑${p.apacheDelta}</i>`
        : p.apacheDelta < 0 ? `<i class="down">↓${Math.abs(p.apacheDelta)}</i>` : '';
      out.push(chip('chip-score', 'APACHE II', `${p.apacheII}${arrow}`,
        `APACHE II ${fmtTime(p.apacheTime)}（较上次${p.apacheDelta > 0 ? '恶化' : p.apacheDelta < 0 ? '好转' : '持平'}）`, true));
    }

    // 血管活性微泵（红）
    if (p.vasoactive) {
      const drugs = p.vasoactiveDrugs || [];
      const tip = `血管活性微泵在用：${drugs.join('、') || '药名未取到'}`;
      if (drugs.length) {
        drugs.slice(0, 2).forEach((d) => out.push(chip('chip-danger', d, '', tip)));
        if (drugs.length > 2) out.push(chip('chip-danger', `等${drugs.length}种`, '', tip));
      } else {
        out.push(chip('chip-danger', '血管活性', '', tip));
      }
    }

    // 生命支持（蓝）
    if (p.ventilator) {
      const mode = p.ventMode || '';
      out.push(chip('chip-info', `呼吸机${mode ? `(${mode})` : ''}`, '',
        mode ? `呼吸机在用 · 当前模式 ${mode}` : '呼吸机在用 · 当前呼吸模式未采集'));
    }
    if (p.crrt) out.push(chip('chip-info', 'CRRT', '', 'CRRT 在用'));
    if (p.organSupport) {
      out.push(chip('chip-info', p.organSupportDetail || '高级脏器支持', '',
        `${p.organSupportDetail || '高级脏器支持'} 在用`));
    }

    // 危急值 / 安全预警（红）
    const pending = p.criticalPending || 0;
    const handled = p.criticalHandled || 0;
    if (pending > 0) {
      const carried = p.criticalCarried || 0;
      out.push(chip('chip-danger', `危急值${pending}待闭环${carried ? `（承${carried}）` : ''}`, '',
        criticalTip(p)));
    } else if (handled > 0) {
      out.push(chip('chip-mute', `危急值${handled}已处置`, '', criticalTip(p)));
    }
    if (p.uexHigh) {
      out.push(chip('chip-danger', 'UEX高危', '',
        'RASS +1~+2 且人工气道在用：交接检查约束手套与牙垫固定'));
    }
    if (p.rescueNight) {
      const el = chip('chip-danger', '夜间抢救', '',
        p.rescueDetail || '夜间发生抢救相关医嘱，交接复苏与用药经过');
      out.push(el.replace('class="chip ', 'class="chip pulse '));
    }
    if (hasAllergy(p)) {
      out.push(chip('chip-danger', '过敏', '', `过敏史：${p.allergy}`));
    }
    // 管路高风险不重复打标：具体三管留置天数已在下方安全行逐条红/黄标出，
    // 指标「管道高风险」仍在底栏辅助指标中可下钻。

    // 收治类型（灰/蓝）
    if (p.newAdmission) out.push(chip('chip-info', '新收', '', `入科 ${fmtTime(p.admissionTime)}`));
    if (p.surgeryAdmission) out.push(chip('chip-mute', '术', '', '手术入科'));
    if (p.transferIn) out.push(chip('chip-mute', '转', '', '转入入科'));

    return out.join('');
  }

  function criticalTip(p) {
    const items = (p.criticals || []).map((c) => {
      const mark = c.handled ? '已闭环' : (c.carriedOver ? '跨班承接' : '未闭环');
      const closed = c.handled && c.handleTime ? `，闭环 ${fmtTime(c.handleTime)}` : '';
      return `${mark}：${c.item || ''} ${c.value || ''} ${fmtTime(c.time)}${closed}`;
    });
    return items.join(' | ') || '危急值记录';
  }

  const TUBE_ADVICE = {
    '气道': '气切 / 脱机评估',
    'PICC': 'CRBSI 评估',
    'CVC': 'CRBSI 评估',
    '尿管': 'CAUTI 评估',
  };

  /** 安全与管路行：三管留置天数 + 跌倒/压疮/VTE/营养风险 */
  function safetyChips(p) {
    const out = [];
    for (const t of (p.tubes || [])) {
      const cls = t.level === 'crit' ? 'chip-danger' : t.level === 'warn' ? 'chip-warn' : 'chip-mute';
      const title = t.title || `${t.label}留置 ${t.days} 天`;
      out.push(chip(cls, t.label, `${t.days}d`, title));
    }

    const tags = viewConfig().tags;
    // base 只取风险类目名，高危/中危由本函数补齐，避免出现「跌倒高危中危」
    const risk = (on, base, level, title) => {
      if (!on) return;
      if (level === 'high') out.push(chip('chip-warn', `${base}高危`, '', title));
      else if (level === 'medium') out.push(chip('chip-mid', `${base}中危`, '', title));
      else out.push(`<span class="dot" title="${esc(title)}"></span>`);
    };

    risk(tags.fall, '跌倒', p.fallLevel,
      p.fallResult ? `跌倒${p.fallResult} 评分${p.fallScore || ''} ${fmtTime(p.fallAssessTime)}` : '跌倒风险未评估（未评估≠低风险）');
    risk(tags.pressure, '压疮', p.pressureLevel,
      p.pressureResult ? `压力性损伤${p.pressureResult} 评分${p.pressureScore || ''} ${fmtTime(p.pressureAssessTime)}` : '压力性损伤未评估（未评估≠低风险）');
    risk(tags.vte, 'VTE', p.vteLevel, p.vteResult || 'VTE 风险未评估（未评估≠低风险）');
    risk(tags.nutrition, '营养', p.nutritionLevel, '营养风险未评估（未评估≠低风险）');

    return out.join('');
  }

  function metaHtml(p) {
    const fields = viewConfig().meta;
    const parts = [];
    if (fields.includes('mrn') && p.mrn) parts.push(`<b>住院号</b>${esc(p.mrn)}`);
    if (fields.includes('nurse')) parts.push(`<b>责任护士</b>${esc(p.responsibleNurse || '未分配')}`);
    if (fields.includes('doctor')) parts.push(`<b>责任医生</b>${esc(p.bedDoctor || '未分配')}`);
    if (fields.includes('group')) parts.push(`<b>责任组</b>${esc(groupName(p.medicalGroup))}`);
    if (fields.includes('admission')) parts.push(`<b>入科</b>${esc(fmtShort(p.admissionTime))}`);
    if (!parts.length && p.mrn) parts.push(`<b>住院号</b>${esc(p.mrn)}`);
    return parts.join(' · ');
  }

  function isolationPills(p) {
    return (p.bedTags || []).map((t) =>
      `<span class="pill pill-iso" title="${esc(t.title || t.label)}">${esc(t.label)}</span>`).join('');
  }

  function patientCard(p) {
    const el = document.createElement('div');
    el.className = 'p-card';
    el.dataset.bed = p.bed || '';
    el.dataset.pid = p.id || '';
    if (state.pinnedId === p.id || state.hoverId === p.id) el.classList.add('is-active');

    const male = ['Male', 'M', '1', '男'].includes(String(p.gender == null ? '' : p.gender));
    const snap = state.snapshot || {};
    const longStay = p.icuDays != null && snap.longStayDays != null && p.icuDays >= snap.longStayDays;
    const daysTitle = longStay
      ? `ICU 住院已达 ${p.icuDays} 天（阈值 ${snap.longStayDays} 天），晨会重点评估 MDT / 脱机与营养`
      : `入科 ${fmtTime(p.admissionTime)}`;
    const daysPill = p.icuDays == null ? ''
      : `<span class="pill pill-days${longStay ? ' long' : ''}" title="${esc(daysTitle)}">`
        + `入科${p.icuDays}天</span>`
      + (longStay ? `<span class="pill pill-days long" title="${esc(daysTitle)}">长程</span>` : '');

    // 头部右侧只放隔离 + 入科天数（需求指定的两项），过敏/夜间抢救
    // 属于支持行的红色告警胶囊，避免头部胶囊互相挤压导致姓名截断
    const diag = (p.diagnosis || '').trim();
    el.innerHTML = `
      <div class="p-head">
        <span class="p-bed">${esc(p.bed || '—')}床</span>
        <span class="p-ident">
          <span class="p-name" title="${esc(diag)}">${esc(p.name || '—')}</span>
          <span class="p-who">${male ? '男' : '女'}${p.age != null ? p.age + '岁' : ''}</span>
        </span>
        <span class="p-head-right">${isolationPills(p)}${daysPill}</span>
      </div>
      <div class="p-body">
        <div class="p-diag" title="${esc(diag)}">${diag ? esc(diag) : '<span class="diag-label">暂无诊断记录</span>'}</div>
        <div class="p-meta" title="${esc(diag ? metaHtml(p).replace(/<[^>]+>/g, '') : '')}">${metaHtml(p)}</div>
      </div>
      <div class="p-foot">
        <div class="chip-row">${supportChips(p)}</div>
        <div class="chip-row">${safetyChips(p)}</div>
      </div>`;

    el.addEventListener('click', () => pinPatient(p.id));
    el.addEventListener('mouseenter', () => {
      if (state.hoverId === p.id) return;
      state.hoverId = p.id;
      renderInspector();
      syncActiveCard();
    });
    el.addEventListener('mouseleave', () => {
      if (state.hoverId !== p.id) return;
      state.hoverId = null;
      renderInspector();
      syncActiveCard();
    });
    return el;
  }

  function emptyCard(bedNumber) {
    const el = document.createElement('div');
    el.className = 'p-card empty-bed';
    el.innerHTML = `<span class="eb-bed">${esc(bedNumber || '—')}床</span><span class="eb-text">(备用空床)</span>`;
    return el;
  }

  function syncActiveCard() {
    const active = state.hoverId || state.pinnedId;
    for (const card of document.querySelectorAll('#groupsScroll .p-card[data-pid]')) {
      card.classList.toggle('is-active', !!active && card.dataset.pid === active);
    }
  }

  function pinPatient(id) {
    state.pinnedId = state.pinnedId === id ? null : id;
    state.hoverId = null;
    syncActiveCard();
    renderInspector();
  }

  // ================= 床位分组渲染 =================
  function renderGroups() {
    const scroll = $('groupsScroll');
    const prevTop = scroll.scrollTop;
    renderGroupList(scroll);
    scroll.scrollTop = Math.min(prevTop, Math.max(0, scroll.scrollHeight - scroll.clientHeight));
  }

  function groupHeadHtml(group, shownCount) {
    const doctors = (group.doctors || []).join('、') || '未分配';
    const mini = (label, value, tone) => (value
      ? `<span>${label}<b class="${tone}">${value}</b></span>` : `<span>${label}<b>${value || 0}</b></span>`);
    return `
      <span class="chev"></span>
      <span class="group-name">${esc(groupName(group.groupName))}</span>
      <span class="group-meta">负责医生：${esc(doctors)} · 在科 ${shownCount} 人</span>
      <span class="group-mini">
        ${mini('呼吸机', group.ventilatorCount, 'info')}
        ${mini('跌倒高', group.fallHighCount, 'warn')}
        ${mini('压疮高', group.pressureHighCount, 'warn')}
      </span>`;
  }

  function makeGroupBlock(group, patientIds) {
    const block = document.createElement('div');
    block.className = 'group-block' + (state.collapsed.has(group.groupName) ? ' collapsed' : '');
    const head = document.createElement('div');
    head.className = 'group-head';
    head.innerHTML = groupHeadHtml(group, patientIds.length);
    head.addEventListener('click', () => {
      block.classList.toggle('collapsed');
      if (block.classList.contains('collapsed')) state.collapsed.add(group.groupName);
      else state.collapsed.delete(group.groupName);
    });
    const grid = document.createElement('div');
    grid.className = 'patient-grid';
    for (const id of patientIds) {
      const p = patientById(id);
      if (p) grid.appendChild(patientCard(p));
    }
    block.appendChild(head);
    block.appendChild(grid);
    return block;
  }

  function appendEmptyBlock(scroll, bedNumbers) {
    if (!bedNumbers.length) return;
    const block = document.createElement('div');
    block.className = 'group-block';
    block.innerHTML = `<div class="empty-block-title">空床 ${bedNumbers.length} 张 · 可调度</div>`;
    const grid = document.createElement('div');
    grid.className = 'patient-grid';
    bedNumbers.forEach((n) => grid.appendChild(emptyCard(n)));
    block.appendChild(grid);
    scroll.appendChild(block);
  }

  function renderToolbarNote(shown, total) {
    const note = $('toolbarNote');
    if (!note) return;
    if (state.filter !== 'all') {
      note.textContent = `筛选「${FILTERS[state.filter].label}」· 命中 ${shown} / ${total} 人`;
    } else {
      note.textContent = '';
    }
  }

  function renderGroupList(scroll) {
    const snap = state.snapshot;
    scroll.innerHTML = '';
    if (!snap) return;

    const all = snap.patients || [];
    const filter = FILTERS[state.filter] || FILTERS.all;
    const patients = all.filter(filter.test);
    const empties = state.filter === 'all' ? emptyBedsOf(snap) : [];
    renderToolbarNote(patients.length, all.length);

    if (!patients.length && !empties.length) {
      scroll.innerHTML = state.filter === 'all'
        ? '<div class="insp-empty" style="padding:40px 0">当前科室无在科患者</div>'
        : '<div class="insp-empty" style="padding:40px 0">当前筛选条件下无命中患者</div>';
      return;
    }

    const shown = new Set(patients.map((p) => p.id));
    const mode = state.sortMode || 'group';

    if (mode === 'group') {
      for (const group of snap.groups || []) {
        const ids = (group.patientIds || []).filter((id) => shown.has(id));
        if (!ids.length) continue;
        scroll.appendChild(makeGroupBlock(group, ids));
      }
      // 未归入任何医疗组的患者兜底展示
      const grouped = new Set((snap.groups || []).flatMap((g) => g.patientIds || []));
      const rest = patients.filter((p) => !grouped.has(p.id)).map((p) => p.id);
      if (rest.length) {
        scroll.appendChild(makeGroupBlock({ groupName: '未分组', doctors: [], patientIds: rest }, rest));
      }
      appendEmptyBlock(scroll, empties);
      return;
    }

    const singleBlock = (title) => {
      const wrap = document.createElement('div');
      wrap.className = 'group-block';
      wrap.innerHTML = `<div class="group-head" style="cursor:default">
        <span class="group-name">${esc(title)}</span>
        <span class="group-meta">共 ${patients.length} 人</span></div>`;
      const grid = document.createElement('div');
      grid.className = 'patient-grid';
      wrap.appendChild(grid);
      scroll.appendChild(wrap);
      return grid;
    };

    if (mode === 'bed') {
      const grid = singleBlock('按床号（顺床查房）');
      const merged = [
        ...patients.map((p) => ({ key: p.bed, card: () => patientCard(p) })),
        ...empties.map((n) => ({ key: n, card: () => emptyCard(n) })),
      ].sort((a, b) => bedSortValue(a.key) - bedSortValue(b.key));
      merged.forEach((m) => grid.appendChild(m.card()));
      return;
    }

    if (mode === 'severity') {
      const grid = singleBlock('按病情严重度（生命支持 / 休克 / 即刻危险优先）');
      patients.slice()
        .sort((a, b) => (severityScore(b) - severityScore(a))
          || (bedSortValue(a.bed) - bedSortValue(b.bed)))
        .forEach((p) => grid.appendChild(patientCard(p)));
      appendEmptyBlock(scroll, empties);
      return;
    }

    const grid = singleBlock('按入科时间（新收优先）');
    patients.slice()
      .sort((a, b) => {
        const ta = a.admissionTime ? new Date(a.admissionTime).getTime() : 0;
        const tb = b.admissionTime ? new Date(b.admissionTime).getTime() : 0;
        return tb - ta || (bedSortValue(a.bed) - bedSortValue(b.bed));
      })
      .forEach((p) => grid.appendChild(patientCard(p)));
    appendEmptyBlock(scroll, empties);
  }

  // ================= 今日交班重点待办 =================
  const TUBE_LABEL = { '气道': '人工气道', 'PICC': 'PICC', 'CVC': 'CVC', '尿管': '导尿管' };

  function buildTodos() {
    const snap = state.snapshot;
    if (!snap) return [];
    const list = [];
    let seq = 0;
    const push = (order, item) => list.push(Object.assign({ order, seq: seq++ }, item));

    // 1) 线下手工待办（无结构化数据源的重点事项）
    for (const t of (snap.manualTodos || MANUAL_TODOS)) {
      push(0, {
        key: `m-${t.cat}-${t.bed}-${t.text}`,
        cat: t.cat,
        level: t.level || 'info',
        bed: t.bed,
        text: t.text,
      });
    }

    const patients = (snap.patients || []).slice();

    // 2) 未闭环危急值：交班第一督办事项
    for (const p of patients) {
      for (const c of (p.criticals || [])) {
        if (c.handled) continue;
        push(1, {
          key: `c-${p.id}-${c.item}-${c.time}`,
          cat: c.carriedOver ? '跨班承接' : '待闭环危急值',
          level: 'danger',
          bed: p.bed,
          text: `${fmtShort(c.time)} ${c.item || ''} ${c.value || ''}`
            + `（未闭环${c.ageHours != null ? ' ' + c.ageHours + 'h' : ''}）`,
          pid: p.id,
        });
      }
    }

    // 3) 管路留置超期预警（同一名患者的多条超期管路合并为一条，按留置天数排序）
    for (const p of patients) {
      const over = (p.tubes || []).filter((t) => t.level === 'crit')
        .sort((a, b) => (b.days || 0) - (a.days || 0));
      if (!over.length) continue;
      const names = over.map((t) => `${TUBE_LABEL[t.label] || t.label} ${t.days}天`);
      const advice = [...new Set(over.map((t) => TUBE_ADVICE[t.label] || '拔管指征评估'))];
      push(2, {
        key: `t-${p.id}`,
        cat: '管路预警',
        level: 'warn',
        bed: p.bed,
        text: `${names.join(' · ')}（${advice.join(' / ')}）`,
        tip: over.map((t) => t.title || '').filter(Boolean).join(' | '),
        pid: p.id,
        rank: over[0].days || 0,
      });
    }

    // 4) 非计划拔管 / 夜间抢救
    for (const p of patients) {
      if (p.uexHigh) {
        push(3, {
          key: `u-${p.id}`,
          cat: '约束安全',
          level: 'danger',
          bed: p.bed,
          text: 'RASS +1~+2 且人工气道在用，检查约束手套与牙垫',
          pid: p.id,
        });
      }
      if (p.rescueNight) {
        push(4, {
          key: `r-${p.id}`,
          cat: '夜间抢救',
          level: 'danger',
          bed: p.bed,
          text: p.rescueDetail || '夜间发生抢救相关医嘱，交接复苏与用药经过',
          pid: p.id,
        });
      }
    }

    return list
      .sort((a, b) => a.order - b.order
        || (b.rank || 0) - (a.rank || 0)
        || a.seq - b.seq)
      .slice(0, 40);
  }

  function renderTodos() {
    const box = $('todoList');
    const prevTop = box.scrollTop;
    box.innerHTML = '';
    const todos = buildTodos();
    $('todoCount').textContent = todos.length ? `共 ${todos.length} 项` : '';

    if (!todos.length) {
      box.innerHTML = '<div class="insp-empty">当前无交班重点待办</div>';
      return;
    }

    for (const t of todos) {
      const el = document.createElement('div');
      const done = state.doneTodos.has(t.key);
      el.className = 'todo-item' + (done ? ' done' : '');
      if (t.tip) el.title = t.tip;
      el.innerHTML = `
        <span class="todo-check"></span>
        <div class="todo-body">
          <div class="todo-top">
            <span class="todo-cat ${esc(t.level)}">${esc(t.cat)}</span>
            <span class="todo-bed">${esc(t.bed || '—')}床</span>
          </div>
          <div class="todo-text">${esc(t.text)}</div>
        </div>`;
      el.addEventListener('click', () => {
        if (state.doneTodos.has(t.key)) state.doneTodos.delete(t.key);
        else state.doneTodos.add(t.key);
        el.classList.toggle('done');
        focusBed(t.bed, t.pid);
      });
      box.appendChild(el);
    }
    box.scrollTop = prevTop;
  }

  function focusBed(bed, pid) {
    if (!state.snapshot) return;
    const p = pid ? patientById(pid)
      : (state.snapshot.patients || []).find((x) => x.bed === bed) || null;
    if (!p || !p.id) return;
    state.pinnedId = p.id;
    syncActiveCard();
    renderInspector();
    const card = document.querySelector(`#groupsScroll .p-card[data-pid="${CSS.escape(p.id)}"]`);
    if (card) card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  // ================= 床位联动速览（Quick Inspector） =================
  const ABG_FIELDS = [
    { key: 'ph', label: 'pH', bad: (v) => v < 7.30 || v > 7.50 },
    { key: 'pao2', label: 'PaO₂ mmHg', bad: (v) => v < 60 },
    { key: 'paco2', label: 'PaCO₂ mmHg', bad: (v) => v < 30 || v > 50 },
    { key: 'lac', label: 'Lac mmol/L', bad: (v) => v > 2.0 },
    { key: 'oi', label: 'OI', bad: (v) => v < 100 },
  ];

  function ioCell(label, value, unit) {
    const na = value === null || value === undefined;
    const sign = !na && label === '净平衡' && value > 0 ? '+' : '';
    return `<div class="io-cell">
      <div class="io-label">${esc(label)}</div>
      <div class="io-value${na ? ' na' : ''}">${na ? '—' : sign + esc(String(value))}
        ${na ? '' : `<small>${esc(unit)}</small>`}</div>
    </div>`;
  }

  function renderInspector() {
    const box = $('inspector');
    const detailBtn = $('btnInspectorDetail');
    const id = state.hoverId || state.pinnedId;
    const p = id ? patientById(id) : null;

    if (!p) {
      detailBtn.hidden = true;
      box.innerHTML = `<div class="insp-hint insp-enter">悬浮或点击左侧任意床位卡片<br>查看该患者 24 小时出入量与最近动脉血气</div>`;
      return;
    }

    detailBtn.hidden = false;
    const snap = state.snapshot || {};
    const male = ['Male', 'M', '1', '男'].includes(String(p.gender == null ? '' : p.gender));
    const diag = (p.diagnosis || '').trim();

    const abg = p.abg;
    const abgHtml = abg
      ? `<div class="abg-grid">${ABG_FIELDS.map((f) => {
        const v = abg[f.key];
        const na = v === null || v === undefined;
        const flag = !na && f.bad(Number(v));
        return `<div class="abg-cell">
            <div class="abg-label">${esc(f.label)}</div>
            <div class="abg-value${na ? ' na' : flag ? ' flag' : ''}">${na ? '—' : esc(String(v))}</div>
          </div>`;
      }).join('')}</div>
        <div class="abg-time">采集时间 ${esc(fmtTime(abg.time))}</div>`
      : '<div class="insp-empty">暂无动脉血气数据</div>';

    box.innerHTML = `
      <div class="insp-enter">
        <div class="insp-head">
          <span class="insp-bed">${esc(p.bed || '—')}床</span>
          <span class="insp-name">${esc(p.name || '—')}</span>
          <span class="insp-who">${male ? '男' : '女'}${p.age != null ? ' ' + p.age + '岁' : ''} · ${esc(groupName(p.medicalGroup))}</span>
        </div>
        <div class="insp-diag" title="${esc(diag)}">${diag ? esc(diag) : '暂无诊断记录'}</div>

        <div class="insp-sec">24 小时出入量<span class="sec-note">${esc(snap.ioWindowStart || '—')} ~ ${esc(snap.ioWindowEnd || '—')}</span></div>
        <div class="io-grid">
          ${ioCell('总入量', p.ioInMl, 'ml')}
          ${ioCell('总出量', p.ioOutMl, 'ml')}
          ${ioCell('尿量', p.urineMl, 'ml')}
          ${ioCell('净平衡', p.ioBalanceMl, 'ml')}
        </div>

        <div class="insp-sec">最近动脉血气</div>
        ${abgHtml}
      </div>`;

    detailBtn.onclick = () => openHandover(p);
  }

  // ================= 统计下钻 =================
  function openDrill(metricKey) {
    const snap = state.snapshot;
    if (!snap) return;
    const m = metric(metricKey);
    const patientIds = (snap.metricPatientIds || {})[metricKey] || [];
    const departureIds = new Set((snap.metricDepartureIds || {})[metricKey] || []);
    const patients = patientIds.map(patientById).filter(Boolean);
    const departures = (snap.departures || []).filter((e) => departureIds.has(e.id));

    $('drillTitle').textContent = m ? m.label : '名单';
    $('drillMeta').textContent = `数据截至 ${snap.asOf ? fmtTime(snap.asOf) : '—'}`;
    const body = $('drillBody');
    body.innerHTML = '';

    if (!patients.length && !departures.length) {
      body.innerHTML = '<div class="drill-empty">该口径下无命中记录</div>';
    } else if (patients.length) {
      const table = document.createElement('table');
      table.className = 'drill-table';
      table.innerHTML = '<thead><tr><th>床号</th><th>姓名</th><th>责任组</th><th>诊断</th>'
        + '<th>命中依据 / 时间</th></tr></thead>';
      const tbody = document.createElement('tbody');
      for (const p of patients) {
        const basis = drillBasis(metricKey, p, m);
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(p.bed || '—')}</td><td>${esc(p.name || '—')}</td>`
          + `<td>${esc(groupName(p.medicalGroup))}</td>`
          + `<td title="${esc(p.diagnosis || '')}">${esc(p.diagnosis || '—')}</td>`
          + `<td title="${esc(basis)}">${esc(basis)}</td>`;
        tr.style.cursor = 'pointer';
        tr.addEventListener('click', () => { closeDrill(); openHandover(p); });
        tbody.appendChild(tr);
      }
      table.appendChild(tbody);
      body.appendChild(table);
    }

    if (departures.length) {
      const table = document.createElement('table');
      table.className = 'drill-table';
      table.style.marginTop = patients.length ? '14px' : '0';
      table.innerHTML = '<thead><tr><th>床号</th><th>姓名</th><th>事件</th><th>时间</th></tr></thead>';
      const tbody = document.createElement('tbody');
      for (const e of departures) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(e.bed || '—')}</td><td>${esc(e.name || '—')}</td>`
          + `<td>${esc(e.typeLabel)}</td><td>${fmtTime(e.time)}</td>`;
        tbody.appendChild(tr);
      }
      table.appendChild(tbody);
      body.appendChild(table);
    }
    $('drillModal').hidden = false;
  }

  function drillBasis(key, p, m) {
    switch (key) {
      case 'fallHigh':
        return `跌倒 ${p.fallResult || '高危'} 评分${p.fallScore || '-'} · ${fmtTime(p.fallAssessTime)}`;
      case 'pressureHigh':
        return `压疮 ${p.pressureResult || '高危'} 评分${p.pressureScore || '-'} · ${fmtTime(p.pressureAssessTime)}`;
      case 'ventilator':
        return p.ventMode ? `呼吸模式 ${p.ventMode}` : '呼吸机在用 · 呼吸模式未采集';
      case 'newAdmissions':
        return `入科 ${fmtTime(p.admissionTime)}`;
      case 'fallUnassessed':
        return '无有效跌倒评估记录（未评估≠低风险）';
      case 'pressureUnassessed':
        return '无有效压力性损伤评估记录（未评估≠低风险）';
      case 'criticalPending':
        return `未闭环 ${p.criticalPending} 项 · 跨班承接 ${p.criticalCarried || 0} 项`;
      case 'criticalCarried':
        return `跨班承接 ${p.criticalCarried} 项（上一班未闭环，本班继续督办）`;
      case 'criticalHandled':
        return `事件窗内已处置 ${p.criticalHandled} 项`;
      default:
        return m && m.note ? m.note : '';
    }
  }
  function closeDrill() { $('drillModal').hidden = true; }

  // ================= 本班变化清单 =================
  const CHANGE_TONE = {
    admission: 'chip-info',
    departure: 'chip-mute',
    critical: 'chip-danger',
    criticalClosed: 'chip-mute',
    score: 'chip-mid',
    tube: 'chip-warn',
    device: 'chip-info',
    drug: 'chip-danger',
  };

  /** 视图过滤：综合看全部；医生不看护理专属（管路置入）；护理不看医生专属（SOFA/APACHE 变化） */
  function changesInView() {
    const all = ((state.snapshot || {}).changes) || [];
    if (state.viewMode === 'doctor') return all.filter((c) => c.scope === 'both' || c.scope === 'doctor');
    if (state.viewMode === 'nurse') return all.filter((c) => c.scope === 'both' || c.scope === 'nurse');
    return all;
  }

  function renderChangesBadge() {
    const btn = $('btnChanges');
    if (!btn) return;
    const n = changesInView().length;
    btn.innerHTML = '本班变化' + (n ? `<b>${n}</b>` : '');
  }

  function openChanges() {
    const snap = state.snapshot;
    if (!snap) return;
    const all = snap.changes || [];
    const timeOf = (c) => { const d = toDate(c.time); return d ? d.getTime() : 0; };
    const list = changesInView().slice().sort((a, b) => timeOf(b) - timeOf(a));

    $('drillTitle').textContent = '本班变化';
    const from = snap.eventFrom ? fmtShort(snap.eventFrom) : '—';
    const to = snap.eventTo ? fmtShort(snap.eventTo) : '—';
    $('drillMeta').textContent = `${snap.eventWindowLabel || '事件窗'} ${from} – ${to} · ${list.length} 条`
      + (list.length !== all.length ? `（全量 ${all.length} 条，已按${viewConfig().label}视图过滤）` : '');

    const body = $('drillBody');
    body.innerHTML = '';
    if (!list.length) {
      body.innerHTML = '<div class="drill-empty">该视图与事件窗内无变化记录</div>';
    } else {
      const table = document.createElement('table');
      table.className = 'drill-table';
      table.innerHTML = '<thead><tr><th>时间</th><th>床号</th><th>姓名</th><th>事件</th><th>说明</th></tr></thead>';
      const tbody = document.createElement('tbody');
      for (const c of list) {
        const tone = CHANGE_TONE[c.kind] || 'chip-mute';
        const detail = c.detail == null ? '' : String(c.detail);
        const tr = document.createElement('tr');
        tr.innerHTML = `<td>${esc(fmtShort(c.time))}</td>`
          + `<td>${esc(c.bed || '—')}</td>`
          + `<td>${esc(c.name || '—')}</td>`
          + `<td><span class="chip ${tone}">${esc(c.label || '—')}</span></td>`
          + `<td title="${esc(detail)}">${esc(detail || '—')}</td>`;
        const p = patientById(c.patientId);
        if (p) {
          tr.style.cursor = 'pointer';
          tr.addEventListener('click', () => { closeDrill(); openHandover(p); });
        }
        tbody.appendChild(tr);
      }
      table.appendChild(tbody);
      body.appendChild(table);
    }
    $('drillModal').hidden = false;
  }

  // ================= 患者交班详情 =================
  function localHandoverDetail(patient) {
    const p = patient;
    const lines = [
      `入科时间：${fmtTime(p.admissionTime)}`,
      `入科天数：${p.icuDays ?? '-'}`,
      `主要诊断：${p.diagnosis || '未记录'}`,
      `责任组：${groupName(p.medicalGroup)} · 责任医生 ${p.bedDoctor || '未分配'} · 责任护士 ${p.responsibleNurse || '未分配'}`,
    ];
    const support = [];
    if (p.ventilator) support.push(`呼吸机${p.ventMode ? `（${p.ventMode}）` : '（模式未采集）'}`);
    if (p.crrt) support.push('CRRT');
    if (p.organSupport) support.push(p.organSupportDetail || '高级脏器支持');
    if (p.vasoactive) support.push(`血管活性微泵：${(p.vasoactiveDrugs || []).join('、') || '药名未取到'}`);
    const tubes = (p.tubes || []).map((t) => `${t.label} ${t.days} 天`);
    const LEVEL_TEXT = { high: '高危', medium: '中危', low: '低危' };
    const riskLine = (kind, level, result, score) => {
      if (!level || level === 'none') return `${kind} 未评估（未评估≠低风险）`;
      return `${kind} ${result || LEVEL_TEXT[level] || '未评估'}`
        + (score ? `（评分 ${score}）` : '');
    };
    const risks = [];
    if (p.fallLevel !== undefined) {
      risks.push(riskLine('跌倒', p.fallLevel, p.fallResult, p.fallScore));
    }
    if (p.pressureLevel !== undefined) {
      risks.push(riskLine('压力性损伤', p.pressureLevel, p.pressureResult, p.pressureScore));
    }
    if (p.nutritionLevel) risks.push(riskLine('营养', p.nutritionLevel));
    if (p.vteLevel) risks.push(riskLine('VTE', p.vteLevel));

    return {
      patient: p,
      asOf: (state.snapshot || {}).asOf,
      blocks: [
        { title: '患者摘要', lines },
        { title: '器官支持与治疗', lines: support.length ? support : ['当前无生命支持设备在用'] },
        { title: '管路留置', lines: tubes.length ? tubes : ['当前无留置管路记录'] },
        { title: '风险评估', lines: risks.length ? risks : ['暂无评估结论'] },
      ],
    };
  }

  async function openHandover(patient) {
    const seq = ++state.handoverSequence;
    $('handoverTitle').textContent = `${patient.bed || '—'}床 · ${patient.name || '—'} 交班详情`;
    const body = $('handoverBody');
    body.innerHTML = '<div class="h-summary">加载中…</div>';
    $('handoverDrawer').hidden = false;

    if (state.preview) { renderHandover(localHandoverDetail(patient)); return; }

    try {
      const snap = state.snapshot;
      const url = `/api/v1/icu-overview/patients/${encodeURIComponent(patient.id)}/handover`
        + (snap ? `?snapshotId=${encodeURIComponent(snap.snapshotId)}` : '');
      const response = await fetch(url);
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const detail = await response.json();
      if (seq !== state.handoverSequence) return; // 已切换患者/已关闭，丢弃迟到响应
      renderHandover(detail);
    } catch (error) {
      if (seq !== state.handoverSequence) return;
      renderHandover(localHandoverDetail(patient));
    }
  }

  function renderHandover(detail) {
    const body = $('handoverBody');
    body.innerHTML = '';
    const p = detail.patient || {};
    const summary = document.createElement('div');
    summary.className = 'h-summary';
    summary.textContent = `${p.bed || '—'}床 ${p.name || ''} · ${groupName(p.medicalGroup)}`
      + ` · ${p.bedDoctor ? '医生 ' + p.bedDoctor : ''}`
      + ` · 数据截至 ${detail.asOf ? fmtTime(detail.asOf) : '—'}`;
    body.appendChild(summary);

    for (const block of detail.blocks || []) {
      const el = document.createElement('div');
      el.className = 'h-block';
      const quality = block.quality === 'unavailable'
        ? '<div class="h-quality">暂不可用</div>'
        : block.quality === 'partial' ? '<div class="h-quality">部分来源缺失</div>' : '';
      el.innerHTML = `<h4>${esc(block.title)}</h4>${quality}<ul>`
        + (block.lines || []).map((l) => `<li>${esc(l)}</li>`).join('') + '</ul>';
      body.appendChild(el);
    }
  }

  function closeHandover() {
    state.handoverSequence += 1; // 关闭后迟到的详情响应不再回填
    $('handoverDrawer').hidden = true;
  }

  // ================= 轮播 / 模式 =================
  function stopCarousel() {
    clearInterval(state.carouselTimer);
    state.carouselTimer = null;
  }
  function restartCarousel() {
    stopCarousel();
    if (!state.carouselOn || state.handoverMode) return;
    state.carouselTimer = setInterval(() => {
      if (state.groupsHover || state.handoverMode) return;
      const scroller = $('groupsScroll');
      const max = scroller.scrollHeight - scroller.clientHeight;
      if (max <= 8) { scroller.scrollTop = 0; return; }
      const next = scroller.scrollTop + scroller.clientHeight - 20;
      scroller.scrollTop = next >= max ? 0 : next;
    }, CAROUSEL_INTERVAL_MS);
  }

  function toggleHandover() {
    state.handoverMode = !state.handoverMode;
    document.body.classList.toggle('handover-mode', state.handoverMode);
    $('btnHandover').classList.toggle('active', state.handoverMode);
    if (state.handoverMode) {
      state.carouselOn = false;
      $('btnCarousel').classList.remove('active');
      stopCarousel();
      toast('已进入交接模式：停止轮播，讲解顺序固定');
    }
  }

  function toggleCarousel() {
    if (state.handoverMode) { toast('交接模式下已停用轮播'); return; }
    state.carouselOn = !state.carouselOn;
    $('btnCarousel').classList.toggle('active', state.carouselOn);
    restartCarousel();
  }

  function toggleFullscreen() {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => toast('全屏失败，请在浏览器菜单中开启'));
    } else {
      document.exitFullscreen();
    }
  }

  function renderAll() {
    renderHeader();
    renderKpis();
    renderAux();
    renderGroups();
    renderTodos();
    renderChangesBadge();

    // 首屏默认聚焦最小床号患者，右侧速览不空置；悬浮任意床位可随时切换
    if (!state.autoPinned && !state.pinnedId && !state.hoverId) {
      const list = ((state.snapshot || {}).patients || []);
      const first = list.slice()
        .sort((a, b) => bedSortValue(a.bed) - bedSortValue(b.bed))[0];
      if (first) {
        state.pinnedId = first.id;
        state.autoPinned = true;
      }
    }

    renderInspector();
    syncActiveCard();
  }

  // ================= 初始化 =================
  function init() {
    // 1. 先注册监听（避免宿主提前发消息丢失），再请求上下文
    window.addEventListener('message', onHostMessage);

    $('btnHandover').addEventListener('click', toggleHandover);
    $('btnCarousel').addEventListener('click', toggleCarousel);
    $('btnRefresh').addEventListener('click', () => loadSnapshot(true));
    $('btnFullscreen').addEventListener('click', toggleFullscreen);
    $('btnExport').addEventListener('click', () => window.print());

    $('sortMode').addEventListener('change', (e) => {
      state.sortMode = e.target.value;
      renderGroups();
      syncActiveCard();
    });

    // 事件窗口径：切换后重新取快照，窗口起止始终由后端计算
    $('windowSeg').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-preset]');
      if (!btn || btn.dataset.preset === state.windowPreset) return;
      state.windowPreset = btn.dataset.preset;
      for (const b of $('windowSeg').querySelectorAll('button')) {
        b.classList.toggle('active', b === btn);
      }
      loadSnapshot(true);
      toast(`事件窗切换为：${btn.textContent.trim()}`);
    });

    // 交班视图：只换展示，统计口径与下钻名单不变，无需重新请求
    $('viewSeg').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-view]');
      if (!btn || btn.dataset.view === state.viewMode) return;
      state.viewMode = btn.dataset.view;
      for (const b of $('viewSeg').querySelectorAll('button')) {
        b.classList.toggle('active', b === btn);
      }
      renderAll();
      toast(`已切换到${viewConfig().label}视图（统计口径不变）`);
    });

    // 快速筛选：仅前端过滤展示，不改变任何统计口径
    $('quickFilter').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-filter]');
      if (!btn || btn.dataset.filter === state.filter) return;
      state.filter = btn.dataset.filter;
      for (const b of $('quickFilter').querySelectorAll('button')) {
        b.classList.toggle('active', b === btn);
      }
      renderGroups();
      syncActiveCard();
    });

    $('drillClose').addEventListener('click', closeDrill);
    $('drillModal').addEventListener('click', (e) => { if (e.target === $('drillModal')) closeDrill(); });
    $('btnChanges').addEventListener('click', openChanges);
    $('handoverClose').addEventListener('click', closeHandover);
    $('handoverDrawer').addEventListener('click', (e) => { if (e.target === $('handoverDrawer')) closeHandover(); });
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      if (!$('drillModal').hidden) closeDrill();
      else if (!$('handoverDrawer').hidden) closeHandover();
    });

    const scroller = $('groupsScroll');
    scroller.addEventListener('mouseenter', () => { state.groupsHover = true; });
    scroller.addEventListener('mouseleave', () => { state.groupsHover = false; });

    // 离线预览：直接双击打开（file://）或 ?demo=1，无需宿主与后端
    if (isPreview()) {
      state.preview = true;
      state.contextResolved = true;
      loadSnapshot(false);
      return;
    }

    showState('等待科室数据', '正在获取当前科室的交班数据…', null, null);
    startHandshake();

    const debug = urlParam('deptCode');
    if (debug) {
      stopHandshake();
      state.contextResolved = true;
      switchDept(debug);
    }
  }

  document.addEventListener('DOMContentLoaded', init);
})();
