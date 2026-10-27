(() => {
  'use strict';

  const stateEl = document.getElementById('state');
  const grid = document.getElementById('cardGrid');
  const drawer = document.getElementById('drawer');
  const drawerBody = document.getElementById('drawerBody');
  const asOf = document.getElementById('asOf');
  const toastEl = document.createElement('div');
  toastEl.className = 'toast';
  document.body.appendChild(toastEl);

  let deptCode = '';
  let beds = [];
  let patients = [];
  let filter = 'all';
  let groupFilter = '';
  let activeId = null;
  let drawerOpen = false;
  let handoverMode = false;
  let sequence = 0;
  let refreshTimer = null;
  let handshakeTimer = null;
  let handshakeTried = 0;
  const openGroups = new Set();

  const HANDSHAKE_INTERVAL_MS = 2000;
  const HANDSHAKE_MAX_RETRY = 5;
  const REFRESH_MS = 30000;

  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastEl._t);
    toastEl._t = setTimeout(() => toastEl.classList.remove('show'), 1600);
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
    if (data.type === 'HOSPITAL_CONTEXT' || data.type === 'HOSPITAL_CONTEXT_CHANGED') {
      const payload = data.payload || {};
      return String(payload.deptCode || payload.departmentCode || '').trim();
    }
    const value = data.deptCode ?? data.departmentCode ?? data.dept_code
      ?? data.data?.deptCode ?? data.data?.departmentCode ?? data.params?.deptCode
      ?? data.patient?.deptCode ?? data.patient?.deptCode2
      ?? data.payload?.deptCode ?? data.payload?.departmentCode;
    return value == null ? '' : String(value).trim();
  }

  function sendReady() {
    if (window.parent === window) return;
    window.parent.postMessage({ type: 'SmartCareReady' }, '*');
    window.parent.postMessage({ type: 'patient-card-ready' }, '*');
  }

  function stopHandshake() {
    if (handshakeTimer) {
      clearInterval(handshakeTimer);
      handshakeTimer = null;
    }
  }

  function startHandshake() {
    if (deptCode || handshakeTimer || window.parent === window) return;
    handshakeTried = 0;
    const tryOnce = () => {
      if (deptCode) { stopHandshake(); return; }
      handshakeTried += 1;
      sendReady();
      if (handshakeTried >= HANDSHAKE_MAX_RETRY) stopHandshake();
    };
    tryOnce();
    handshakeTimer = setInterval(tryOnce, HANDSHAKE_INTERVAL_MS);
  }

  function showState(title, copy, isError) {
    stateEl.hidden = false;
    grid.hidden = true;
    stateEl.classList.toggle('error', !!isError);
    stateEl.innerHTML = `<h1>${esc(title)}</h1><p>${esc(copy)}</p>`;
  }

  function hideState() {
    stateEl.hidden = true;
    grid.hidden = false;
  }

  function avatarSrc(age, gender) {
    const g = gender === 'Male' || gender === '男' || gender === '1' || gender === 'M' ? 'man' : 'woman';
    if (!age || age === 0) return `assets/baby-${g}.png`;
    if (age <= 6) return `assets/toddler-${g}.png`;
    if (age <= 14) return `assets/child-${g}.png`;
    if (age <= 16) return `assets/teen-${g}.png`;
    if (age <= 24) return `assets/young-${g}.png`;
    if (age < 60) return `assets/adult-${g}.png`;
    return `assets/elderly-${g}.png`;
  }

  function ageOf(birthday) {
    if (!birthday) return 0;
    const birth = new Date(birthday);
    if (Number.isNaN(birth.getTime())) return 0;
    const now = new Date();
    let age = now.getFullYear() - birth.getFullYear();
    const m = now.getMonth() - birth.getMonth();
    if (m < 0 || (m === 0 && now.getDate() < birth.getDate())) age--;
    return Math.max(0, age);
  }

  function icuDays(p) {
    const t = p.icuAdmissionTime || p.admissionTime;
    if (!t) return 0;
    const d = new Date(t);
    if (Number.isNaN(d.getTime())) return 0;
    return Math.max(0, Math.floor((Date.now() - d.getTime()) / 86400000));
  }

  function fmtShort(value) {
    if (!value) return '—';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return '—';
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function bedSort(value) {
    const parsed = parseInt(String(value || '').replace(/[^0-9]/g, ''), 10);
    return Number.isNaN(parsed) ? Number.MAX_SAFE_INTEGER : parsed;
  }

  /** 床号归一化：去掉前导零与非数字，避免 hisName=1 / showName=01 对不上 */
  function bedKey(value) {
    const n = bedSort(value);
    return n === Number.MAX_SAFE_INTEGER ? String(value || '') : String(n);
  }

  function isHigh(risk) {
    return !!risk && (risk.level === 'high' || risk.result === '高危');
  }

  function fmtMl(v) {
    if (v === null || v === undefined || v === '') return '—<small>ml</small>';
    return `${esc(String(v))}<small>ml</small>`;
  }

  function fmtBal(v) {
    if (v === null || v === undefined || v === '') return '—<small>ml</small>';
    const n = Number(v);
    const sign = n > 0 ? '+' : '';
    return `${sign}${esc(String(v))}<small>ml</small>`;
  }

  function balClass(v) {
    if (v === null || v === undefined || v === '') return '';
    if (Number(v) > 0) return 'bal-pos';
    if (Number(v) < 0) return 'bal-neg';
    return '';
  }

  function bloodLabel(p) {
    // 血型不在卡片展示（现场要求取消）
    void p;
    return '';
  }

  function dietLabel(diet) {
    return String(diet || '')
      .replace('糖尿病饮食', '糖尿病')
      .replace('低盐饮食', '低盐')
      .replace('低蛋白饮食', '低蛋白')
      .replace('肠内营养', '肠内')
      .replace('鼻饲饮食', '鼻饲')
      .replace('流质饮食', '流质');
  }

  function buildTags(p) {
    const alert = [];
    const soft = [];
    if (p.ventilator) alert.push({ t: '呼吸机', c: 'alert-vent' });
    if (p.crrt) alert.push({ t: 'CRRT', c: 'alert-crit' });
    if (p.difficultAirway) alert.push({ t: '困难气道', c: 'alert-crit' });
    if (isHigh(p.pressureDamageRisk)) alert.push({ t: '压疮高危', c: 'alert-high' });
    if (isHigh(p.fallRisk)) alert.push({ t: '跌倒高危', c: 'alert-high' });
    if (isHigh(p.pipeRisk)) soft.push({ t: '导管高危', c: 'soft' });
    if (isHigh(p.nutritionRisk)) soft.push({ t: '营养', c: 'soft' });
    if (isHigh(p.vteRisk)) soft.push({ t: 'VTE', c: 'soft' });
    if (p.diet) soft.push({ t: dietLabel(p.diet), c: 'soft' });
    if (p.surgeryAdmission) soft.push({ t: '术', c: 'mute' });
    if (p.transferIn) soft.push({ t: '转', c: 'mute' });
    return { alert, soft };
  }

  function groupKey(p) {
    return (p.medicalGroup || '').trim() || '未分组';
  }

  function filteredPatients() {
    let list = patients.slice();
    if (groupFilter) {
      list = list.filter((p) => groupKey(p) === groupFilter);
    }
    if (filter === 'new') list = list.filter((p) => !!p.newAdmission);
    if (filter === 'long') list = list.filter((p) => icuDays(p) >= 14);
    if (filter === 'vent') list = list.filter((p) => p.ventilator);
    if (filter === 'fall') list = list.filter((p) => isHigh(p.fallRisk));
    if (filter === 'pressure') list = list.filter((p) => isHigh(p.pressureDamageRisk));
    return list;
  }

  function cardHtml(p) {
    const { alert, soft } = buildTags(p);
    const alertHtml = alert.map((t) => `<span class="tag ${t.c}" title="${esc(t.t)}">${esc(t.t)}</span>`).join('');
    const softHtml = soft.map((t) => `<span class="tag ${t.c}" title="${esc(t.t)}">${esc(t.t)}</span>`).join('');
    const age = ageOf(p.birthday);
    const gender = p.gender === 'Male' || p.gender === '男' || p.gender === '1' || p.gender === 'M' ? '男' : '女';
    const gCls = gender === '男' ? 'male' : 'female';
    const days = icuDays(p);
    const longStay = days >= 14;
    const diag = (p.clinicalDiagnosis || p.admissionDiagnosis || '').trim();
    const mrn = p.mrn || '';
    const admit = fmtShort(p.icuAdmissionTime || p.admissionTime);
    const doctor = (p.bedDoctor || '').trim() || '未分配';
    const grp = groupKey(p);
    const bed = p.bedNumber || p.hisBed || '—';
    return `<article class="card ${activeId === p.id ? 'active' : ''}" data-id="${esc(p.id)}" data-bed="${esc(bed)}">
      <header class="card-head">
        <div class="bed">${esc(bed)}床</div>
        <div class="name">${esc(p.name || '—')}</div>
        <div class="head-right">
          <span class="pill-days" title="入科 ${days} 天">入科${days}天</span>
          ${longStay ? `<span class="pill-days long" title="ICU 住院已达 ${days} 天，晨会重点评估">长程</span>` : ''}
        </div>
      </header>
      <div class="card-body">
        <div class="profile">
          <img class="avatar" src="${esc(avatarSrc(age, p.gender))}" alt="" width="52" height="52" />
          <div class="gender ${gCls}">${gender}</div>
          <div class="age">${age}岁</div>
        </div>
        <div class="details">
          <div class="meta-line"><span>住院号 <b>${esc(mrn)}</b></span><span title="${esc(admit)}">入科 <b>${esc(admit)}</b></span></div>
          <div class="patient-diag" title="${esc(diag)}">${esc(diag)}</div>
          <div class="who-line"><b>${esc(doctor)}</b> · ${esc(grp)}</div>
        </div>
      </div>
      <footer class="card-foot">${alertHtml}${softHtml}</footer>
    </article>`;
  }

  function emptyHtml(n) {
    return `<div class="empty-card">
      <svg class="ghost-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <rect x="3" y="8" width="18" height="10" rx="2"/>
        <path d="M3 12h18M7 8V6a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v2"/>
      </svg>
      <div class="ghost-bed">${esc(n)}床</div>
      <div class="ghost-text">备用空床</div>
    </div>`;
  }

  function renderGroupFilter() {
    const box = document.getElementById('groupFilter');
    if (!box) return;
    const counts = new Map();
    patients.forEach((p) => {
      const k = groupKey(p);
      counts.set(k, (counts.get(k) || 0) + 1);
    });
    const keys = [...counts.keys()].sort((a, b) => {
      if (a === '未分组') return 1;
      if (b === '未分组') return -1;
      return a.localeCompare(b, 'zh');
    });
    box.innerHTML = [
      `<button class="chip-btn ${groupFilter ? '' : 'active'}" type="button" data-g="">全部</button>`,
      ...keys.map((k) =>
        `<button class="chip-btn ${groupFilter === k ? 'active' : ''}" type="button" data-g="${esc(k)}" title="${esc(k)} ${counts.get(k)}人">${esc(k)}<em>${counts.get(k)}</em></button>`
      ),
    ].join('');
    box.querySelectorAll('.chip-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        groupFilter = btn.dataset.g || '';
        renderGroupFilter();
        renderGrid();
        renderDrawer();
      });
    });
  }

  function renderGrid() {
    // 仅无医疗组/条件筛选的「全部」才铺空床
    if (filter === 'empty' && !groupFilter) {
      const emptyAll = emptyBedNames();
      grid.innerHTML = emptyAll.length
        ? emptyAll.map((n) => emptyHtml(n)).join('')
        : '<div style="padding:40px;text-align:center;color:#7D7A75">当前无空床</div>';
      return;
    }

    const list = filteredPatients();
    const showEmpty = filter === 'all' && !groupFilter;
    if (!list.length && !showEmpty) {
      grid.innerHTML = '<div style="padding:40px;text-align:center;color:#7D7A75">当前筛选条件下无患者</div>';
      return;
    }

    const occupied = new Set(list.map((p) => bedKey(p.hisBed)).filter(Boolean));
    const emptyNames = showEmpty ? emptyBedNames() : [];
    const rows = [
      ...list.map((p) => ({ key: p.bedNumber || p.hisBed || '', html: cardHtml(p) })),
      ...emptyNames.map((n) => ({ key: n, html: emptyHtml(n) })),
    ].sort((a, b) => bedSort(a.key) - bedSort(b.key));
    if (!rows.length) {
      grid.innerHTML = '<div style="padding:40px;text-align:center;color:#7D7A75">当前科室无在科患者</div>';
      return;
    }
    grid.innerHTML = rows.map((r) => r.html).join('');
    bindCards();
  }

  function emptyBedNames(occupiedSet) {
    const occupied = occupiedSet || new Set(patients.map((p) => bedKey(p.hisBed)).filter(Boolean));
    return beds
      .filter((b) => !occupied.has(bedKey(b.hisName)) && !occupied.has(bedKey(b.showName)))
      .map((b) => b.showName || b.hisName)
      .filter(Boolean);
  }

  /** 宿主「医生-诊断分析-病情概览」路由（相对 hash，不写死主机地址） */
  const PATIENT_REVIEW_HASH = '#/main/doctor/diagnosis-analysis/patient-review';
  const ONLINE_PATIENT_HASH = '#/main/patient/patient-list/online-patient';

  /**
   * 动态解析宿主站点 Origin（iframe 内无法假定固定 IP/端口）。
   * 优先：顶层窗口（若同源可读）→ document.referrer → 当前页 origin。
   */
  function resolveHostOrigin() {
    try {
      if (window.top && window.top !== window) {
        const origin = window.top.location && window.top.location.origin;
        if (origin && origin !== 'null') return origin;
      }
    } catch (_) { /* cross-origin */ }
    try {
      if (document.referrer) {
        const origin = new URL(document.referrer).origin;
        if (origin && origin !== 'null') return origin;
      }
    } catch (_) { /* invalid referrer */ }
    try {
      const origin = window.location.origin;
      if (origin && origin !== 'null' && origin !== 'http://localhost:18088') return origin;
    } catch (_) { /* ignore */ }
    return '';
  }

  function hostUrl(hash) {
    const origin = resolveHostOrigin();
    return origin ? origin + '/' + hash : hash;
  }

  function navigateTop(hashOrUrl) {
    const url = hashOrUrl.charAt(0) === '#'
      ? hostUrl(hashOrUrl)
      : hashOrUrl;
    try {
      if (window.top && window.top !== window) {
        window.top.location.href = url;
      } else {
        window.location.href = url;
      }
    } catch (_) {
      try {
        if (window.top) window.top.location.hash = hashOrUrl.charAt(0) === '#' ? hashOrUrl : PATIENT_REVIEW_HASH;
        else window.location.hash = hashOrUrl.charAt(0) === '#' ? hashOrUrl : PATIENT_REVIEW_HASH;
      } catch (__) { /* ignore */ }
    }
  }

  function patientKeys(patient) {
    return {
      id: String(patient.id || ''),
      mrn: String(patient.mrn || ''),
      name: String(patient.name || ''),
      bed: String(patient.bedNumber || patient.hisBed || patient.showBed || ''),
    };
  }

  function rowMatchesPatient(row, keys) {
    const t = row.textContent || '';
    if (keys.mrn && t.indexOf(keys.mrn) >= 0) return true;
    if (keys.name && t.indexOf(keys.name) >= 0) return true;
    if (keys.id && t.indexOf(keys.id) >= 0) return true;
    // 床号需整格匹配，避免 01 误匹配 010/101
    if (keys.bed) {
      const bedNum = keys.bed.replace(/^0+/, '') || '0';
      const re = new RegExp('(^|\\D)' + bedNum + '(\\D|$)');
      if (re.test(t)) return true;
    }
    return false;
  }

  function trySelectInTop(patient) {
    try {
      if (!window.top || window.top === window) return false;
      const doc = window.top.document;
      if (!doc) return false;
      const keys = patientKeys(patient);
      const rows = Array.prototype.slice.call(doc.querySelectorAll('tbody tr'));
      if (!rows.length) return false;
      const row = rows.find((r) => rowMatchesPatient(r, keys));
      if (!row) return false;
      row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window.top }));
      row.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window.top }));
      return true;
    } catch (_) {
      return false;
    }
  }

  function waitForTopPatientSelected(patient, timeoutMs) {
    return new Promise((resolve) => {
      const started = Date.now();
      const tick = () => {
        let ok = false;
        try {
          if (window.top && window.top !== window) {
            const hash = String((window.top.location && window.top.location.hash) || '');
            // 双击选人后宿主通常切到 execute-drug 等患者页
            if (hash.indexOf('execute-drug') >= 0 || hash.indexOf('patient-review') >= 0) ok = true;
          }
        } catch (_) { /* cross-origin mid-way */ }
        if (ok || Date.now() - started >= timeoutMs) {
          resolve(ok);
          return;
        }
        setTimeout(tick, 150);
      };
      tick();
    });
  }

  /** 同源：通过宿主「医生→病情概览」菜单打开新页签 */
  function openReviewTabViaMenuInTop() {
    try {
      if (!window.top || window.top === window) return false;
      const doc = window.top.document;
      if (!doc) return false;
      const mouseClick = (el) => {
        const r = el.getBoundingClientRect();
        const opts = {
          bubbles: true, cancelable: true, view: window.top,
          clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0,
        };
        ['pointerover', 'mouseover', 'mouseenter', 'mousemove', 'mousedown', 'mouseup', 'click']
          .forEach((t) => el.dispatchEvent(new MouseEvent(t, opts)));
      };
      const findTopMenuAnchor = (name) => {
        const anchors = doc.querySelectorAll('a');
        for (let i = 0; i < anchors.length; i++) {
          const a = anchors[i];
          if ((a.textContent || '').trim() !== name) continue;
          const r = a.getBoundingClientRect();
          if (r.width > 0 && r.height > 0 && r.top < 50 && r.top >= 0) return a;
        }
        return null;
      };
      const findVisibleLink = (text) => {
        const links = doc.querySelectorAll('a.ellipsis, a');
        for (let i = 0; i < links.length; i++) {
          if ((links[i].textContent || '').trim() === text) {
            const r = links[i].getBoundingClientRect();
            if (r.width > 0 && r.height > 0) return links[i];
          }
        }
        return null;
      };
      const doctor = findTopMenuAnchor('医生');
      if (doctor) mouseClick(doctor);
      setTimeout(() => {
        const link = findVisibleLink('病情概览');
        if (link) { mouseClick(link); return; }
        const doctor2 = findTopMenuAnchor('医生');
        if (doctor2) mouseClick(doctor2);
        setTimeout(() => {
          const link2 = findVisibleLink('病情概览');
          if (link2) mouseClick(link2);
        }, 800);
      }, 800);
      return true;
    } catch (_) {
      return false;
    }
  }

  /**
   * 点击患者卡：
   * 1) postMessage 通知宿主桥（桥负责选人 + 打开病情概览新页签）
   * 2) 同源无桥时：双击选人后走宿主菜单打开新页签
   */
  async function openPatientReview(patient) {
    if (!patient) return;
    const keys = patientKeys(patient);

    const payload = {
      type: 'SmartCareNavigate',
      action: 'openPatientReview',
      route: PATIENT_REVIEW_HASH,
      patientId: keys.id,
      mrn: keys.mrn,
      bed: keys.bed,
      name: keys.name,
    };

    const inIframe = window.top && window.top !== window;
    try {
      if (inIframe) {
        window.top.postMessage(payload, '*');
        window.parent.postMessage(payload, '*');
      }
    } catch (_) { /* ignore */ }

    // iframe 内：优先交给宿主桥（选人 + 开新页签），本页不抢 hash
    if (inIframe) {
      await new Promise((r) => setTimeout(r, 2500));
      return;
    }

    // 非 iframe（直接打开本页）：同源选人后用菜单开新页签
    if (trySelectInTop(patient)) {
      await waitForTopPatientSelected(patient, 2500);
      if (openReviewTabViaMenuInTop()) {
        await new Promise((r) => setTimeout(r, 1200));
        return;
      }
    }

    navigateTop(PATIENT_REVIEW_HASH);
  }

  function bindCards() {
    grid.querySelectorAll('.card').forEach((el) => {
      el.addEventListener('click', () => {
        const id = el.dataset.id;
        const p = patients.find((x) => x.id === id);
        pick(id);
        openPatientReview(p);
      });
    });
  }

  function renderKpi() {
    const inDept = patients.length;
    const openBeds = beds.length;
    const emptyCount = emptyBedNames().length;
    const vent = patients.filter((p) => p.ventilator).length;
    const fall = patients.filter((p) => isHigh(p.fallRisk)).length;
    const press = patients.filter((p) => isHigh(p.pressureDamageRisk)).length;
    const neu = patients.filter((p) => !!p.newAdmission).length;
    // 床位接口失败时只显示在科人数，避免出现 22/0
    document.getElementById('kpiInDept').innerHTML = openBeds > 0
      ? `${inDept}/${openBeds}<em>床</em>`
      : `${inDept}<em>人</em>`;
    document.getElementById('kpiEmpty').innerHTML = openBeds > 0 ? `${emptyCount}<em>张</em>` : '—';
    document.getElementById('kpiVent').innerHTML = `${vent}<em>人</em>`;
    document.getElementById('kpiFall').innerHTML = `${fall}<em>人</em>`;
    document.getElementById('kpiPressure').innerHTML = `${press}<em>人</em>`;
    document.getElementById('kpiNew').innerHTML = `${neu}<em>人</em>`;
    asOf.textContent = `数据更新 ${fmtShort(new Date())}`;
  }

  function pick(id, opts) {
    activeId = id;
    // 从抽屉跳转时，若当前筛选看不到该患者则回到全部
    if (!opts || opts.ensureVisible) {
      const p = patients.find((x) => x.id === id);
      if (p && !filteredPatients().some((x) => x.id === id)) {
        filter = 'all';
        groupFilter = '';
        document.querySelectorAll('.chip-btn[data-f]').forEach((b) => b.classList.toggle('active', b.dataset.f === 'all'));
        renderGroupFilter();
        renderGrid();
      }
    }
    grid.querySelectorAll('.card').forEach((el) => el.classList.toggle('active', el.dataset.id === id));
    openDrawer();
    renderDrawer();
  }

  function openDrawer() {
    drawerOpen = true;
    drawer.classList.add('open');
    document.getElementById('btnStats').classList.add('on');
  }

  function closeDrawer() {
    drawerOpen = false;
    drawer.classList.remove('open');
    document.getElementById('btnStats').classList.remove('on');
  }

  function renderDrawer() {
    const p = patients.find((x) => x.id === activeId);
    const pickHtml = p ? `
      <div class="pick-card">
        <div class="t">${esc(p.bedNumber || p.hisBed || '—')}床 ${esc(p.name || '')}</div>
        <div class="s">${ageOf(p.birthday)}岁 · 入科${icuDays(p)}天 · ${esc(p.bedDoctor || '')}</div>
        <div class="d">${esc(p.clinicalDiagnosis || p.admissionDiagnosis || '')}</div>
        <div class="mini-io">
          <div class="cell"><div class="l">总入量</div><div class="v">${fmtMl(p.ioInMl)}</div></div>
          <div class="cell"><div class="l">总出量</div><div class="v">${fmtMl(p.ioOutMl)}</div></div>
          <div class="cell"><div class="l">尿量</div><div class="v">${fmtMl(p.urineMl)}</div></div>
          <div class="cell ${balClass(p.ioBalanceMl)}"><div class="l">净平衡</div><div class="v">${fmtBal(p.ioBalanceMl)}</div></div>
        </div>
        <div style="margin-top:8px;color:#94A3B8;font-size:11px">${esc(p.ioWindowStart || '—')} ~ ${esc(p.ioWindowEnd || '—')}</div>
      </div>` : `<div style="color:#7D7A75;font-size:12.5px;margin-bottom:12px">点击左侧患者卡，此处显示速览</div>`;

    const groups = {};
    patients.forEach((pt) => {
      const k = groupKey(pt);
      (groups[k] = groups[k] || []).push(pt);
    });
    const groupHtml = Object.keys(groups).sort().map((k) => {
      const members = groups[k].slice().sort((a, b) => bedSort(a.hisBed || a.bedNumber) - bedSort(b.hisBed || b.bedNumber));
      const doctors = [...new Set(members.map((m) => m.bedDoctor).filter(Boolean))].join('、') || '未分配';
      const ventN = members.filter((m) => m.ventilator || m.crrt).length;
      const pressN = members.filter((m) => isHigh(m.pressureDamageRisk)).length;
      const open = openGroups.has(k);
      const items = members.map((m) => {
        const flags = [];
        if (m.ventilator) flags.push('<span class="tag alert-vent">机</span>');
        if (m.crrt) flags.push('<span class="tag alert-crit">CRRT</span>');
        if (m.difficultAirway) flags.push('<span class="tag alert-crit">气道</span>');
        if (isHigh(m.pressureDamageRisk) || isHigh(m.fallRisk) || isHigh(m.pipeRisk)) flags.push('<span class="tag alert-high">危</span>');
        return `<div class="grp-item ${activeId === m.id ? 'active' : ''}" data-id="${esc(m.id)}">
          <span class="b">${esc(m.bedNumber || m.hisBed || '—')}床</span>
          <span class="n">${esc(m.name || '')}</span>
          <span class="m" style="color:#7D7A75;font-size:11px">${esc(m.bedDoctor || '')}</span>
          <span>${flags.join('')}</span>
        </div>`;
      }).join('');
      return `<div class="grp ${open ? 'open' : ''}" data-g="${esc(k)}">
        <div class="grp-head">
          <span class="grp-chev"></span>
          <div style="flex:1;min-width:0">
            <div class="grp-title">${esc(k)}</div>
            <div class="grp-doc">负责医生：${esc(doctors)}</div>
          </div>
          <div class="grp-num"><b>${members.length}人</b><span class="sub">呼吸机${ventN} · 压疮${pressN}</span></div>
        </div>
        <div class="grp-list">${items}</div>
      </div>`;
    }).join('');

    drawerBody.innerHTML = pickHtml + `<div class="stat-block"><h3>医疗组一览（点组名展开）</h3>${groupHtml}</div>`;
    drawerBody.querySelectorAll('.grp-head').forEach((el) => {
      el.addEventListener('click', () => {
        const key = el.parentElement.dataset.g;
        if (openGroups.has(key)) openGroups.delete(key);
        else openGroups.add(key);
        renderDrawer();
      });
    });
    drawerBody.querySelectorAll('.grp-item[data-id]').forEach((el) => {
      el.addEventListener('click', () => {
        pick(el.dataset.id, { ensureVisible: true });
        const card = grid.querySelector(`[data-id="${CSS.escape(el.dataset.id)}"]`);
        if (card) card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      });
    });
  }

  async function load() {
    if (!deptCode) return;
    const current = ++sequence;
    const firstLoad = grid.hidden;
    if (firstLoad) showState('正在加载患者', '正在获取在科患者…', false);
    try {
      const [bedResponse, patientResponse] = await Promise.all([
        fetch(`/api/patients/config-beds/${encodeURIComponent(deptCode)}`),
        fetch(`/api/patients/dashboard/${encodeURIComponent(deptCode)}`),
      ]);
      if (!patientResponse.ok) throw new Error('patient request failed');
      beds = bedResponse.ok ? await bedResponse.json() : [];
      const data = await patientResponse.json();
      if (current !== sequence) return;
      patients = Array.isArray(data) ? data : [];
      // 刷新后选中患者可能已出科
      if (activeId && !patients.some((p) => p.id === activeId)) {
        activeId = null;
      }
      renderKpi();
      renderGroupFilter();
      renderGrid();
      hideState();
      if (activeId) renderDrawer();
    } catch (_) {
      if (current === sequence && grid.hidden) {
        showState('患者数据加载失败', '请刷新页面重试', true);
      }
    }
  }

  window.addEventListener('message', (event) => {
    if (event.data && event.data.type === 'HOSPITAL_LOGOUT') {
      deptCode = '';
      patients = [];
      beds = [];
      stopHandshake();
      showState('等待科室数据', '正在获取当前科室的在科患者…', false);
      startHandshake();
      return;
    }
    const received = extractDeptCode(event.data);
    if (received && received !== deptCode) {
      deptCode = received;
      stopHandshake();
      load();
    }
  });

  document.getElementById('btnStats').addEventListener('click', () => {
    if (drawerOpen) { closeDrawer(); toast('已收起统计栏'); }
    else { openDrawer(); renderDrawer(); toast('已打开统计栏'); }
  });
  document.getElementById('btnClose').addEventListener('click', () => { closeDrawer(); toast('已收起统计栏'); });
  document.getElementById('btnRefresh').addEventListener('click', () => { load(); toast('已刷新'); });
  document.getElementById('btnHandover').addEventListener('click', (e) => {
    handoverMode = !handoverMode;
    e.currentTarget.classList.toggle('on', handoverMode);
    toast(handoverMode ? '已进入交班模式（暂停自动刷新）' : '已退出交班模式（恢复自动刷新）');
  });

  document.querySelectorAll('.kpi-item').forEach((el) => {
    el.addEventListener('click', () => {
      const f = el.dataset.filter;
      if (f) {
        filter = f;
        document.querySelectorAll('.chip-btn[data-f]').forEach((b) => b.classList.toggle('active', b.dataset.f === f));
        renderGrid();
      }
      openDrawer();
      renderDrawer();
    });
  });
  document.querySelectorAll('.chip-btn[data-f]').forEach((btn) => {
    btn.addEventListener('click', () => {
      filter = btn.dataset.f;
      document.querySelectorAll('.chip-btn[data-f]').forEach((b) => b.classList.toggle('active', b === btn));
      renderGrid();
    });
  });

  const queryDept = new URLSearchParams(location.search).get('deptCode');
  if (queryDept) {
    deptCode = queryDept.trim();
    load();
  } else {
    startHandshake();
  }
  // 交班模式暂停自动刷新，避免讲解时数据跳动
  refreshTimer = setInterval(() => {
    if (deptCode && !handoverMode) load();
  }, REFRESH_MS);
  window.addEventListener('beforeunload', () => {
    clearInterval(refreshTimer);
    stopHandshake();
  });
})();
