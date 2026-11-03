/* ============================================================
 * 重症医学科 · 早交班大屏 — 颜色标识配置（配色面板）
 * - 每个标识可用 色相/饱和度/亮度 滑块、取色器或输入 #色号 调整
 * - 修改即时作用于整屏卡片（CSS 变量）
 * - 点「保存」→ PUT /api/v1/icu-overview/theme，后端回写 config/application.yml 的 overview.theme，
 *   所有大屏统一配色（各屏每 60 秒同步一次）；localStorage 仅作首屏缓存
 * - 离线预览（file:// 或 ?demo=1）没有后端，只保存在本机
 * - 取消/关闭未保存时回退到上次保存的配色
 * 扩展新标识：在 ITEMS 中追加一项，并在 overview.css 中使用对应 CSS 变量
 * ============================================================ */
(function () {
  'use strict';

  var STORAGE_KEY = 'icuOverview.theme.v1';
  var KEY_STORAGE = 'icuOverview.themeKey';
  var API = '/api/v1/icu-overview/theme';
  var SYNC_MS = 60 * 1000;
  var offline = window.location.protocol === 'file:'
    || new URLSearchParams(window.location.search).get('demo') === '1';

  /** vars(hex) 返回需要写入 :root 的 CSS 变量；contrastOn 用于可读性提示 */
  var ITEMS = [
    {
      key: 'newHead', label: '新收卡头', desc: '昨日 08:00 ~ 今日 08:00 入科的病人卡片顶部',
      def: '#0EA5A4', contrastOn: '#FFFFFF', contrastLabel: '白字',
      presets: ['#0EA5A4', '#10B981', '#0891B2', '#7C3AED', '#DB2777', '#EA580C'],
      vars: function (hex) { return { '--new-head': hex, '--new-head-2': shiftL(hex, 6) }; }
    },
    {
      key: 'newPill', label: '新收标签文字', desc: '卡头右侧白底「新收 HH:mm」的文字与圆点',
      def: '#059669', contrastOn: '#FFFFFF', contrastLabel: '白底',
      presets: ['#059669', '#0F766E', '#0369A1', '#6D28D9', '#BE185D', '#C2410C'],
      vars: function (hex) { return { '--new-pill-tx': hex, '--new-pill-ring': rgba(hex, 0.22) }; }
    },
    {
      key: 'normalHead', label: '普通卡头', desc: '非新收病人卡片顶部',
      def: '#5392FF', contrastOn: '#FFFFFF', contrastLabel: '白字',
      presets: ['#5392FF', '#3B82F6', '#2563EB', '#0284C7', '#475569', '#4F46E5'],
      vars: function (hex) { return { '--head-bg': hex, '--head-bg-2': hex }; }
    },
    {
      key: 'longBg', label: '长程标签底色', desc: '卡头「入科N天 / 长程」高亮胶囊',
      def: '#FDE68A', contrastOn: null,
      presets: ['#FDE68A', '#FED7AA', '#FBCFE8', '#DDD6FE', '#BFDBFE', '#FFFFFF'],
      vars: function (hex) { return { '--long-bg': hex, '--long-bd': shiftL(hex, -8) }; }
    },
    {
      key: 'longTx', label: '长程标签文字', desc: '长程胶囊上的文字颜色',
      def: '#7C2D12', contrastOn: 'longBg', contrastLabel: '长程底色',
      presets: ['#7C2D12', '#92400E', '#9F1239', '#4C1D95', '#1E3A8A', '#0F172A'],
      vars: function (hex) { return { '--long-tx': hex }; }
    }
  ];

  // ================= 颜色工具 =================
  function clamp(v, a, b) { return Math.min(b, Math.max(a, v)); }
  function normHex(input) {
    var s = String(input || '').trim().replace(/^#/, '');
    if (/^[0-9a-f]{3}$/i.test(s)) s = s.split('').map(function (c) { return c + c; }).join('');
    if (!/^[0-9a-f]{6}$/i.test(s)) return null;
    return '#' + s.toUpperCase();
  }
  function hexToRgb(hex) {
    var n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function rgbToHex(r, g, b) {
    return '#' + [r, g, b].map(function (v) {
      return ('0' + Math.round(clamp(v, 0, 255)).toString(16)).slice(-2);
    }).join('').toUpperCase();
  }
  function hexToHsl(hex) {
    var c = hexToRgb(hex).map(function (v) { return v / 255; });
    var r = c[0], g = c[1], b = c[2];
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var h = 0, s = 0, l = (max + min) / 2;
    if (max !== min) {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
      else if (max === g) h = (b - r) / d + 2;
      else h = (r - g) / d + 4;
      h *= 60;
    }
    return [Math.round(h), Math.round(s * 100), Math.round(l * 100)];
  }
  function hslToHex(h, s, l) {
    s /= 100; l /= 100;
    var k = function (n) { return (n + h / 30) % 12; };
    var a = s * Math.min(l, 1 - l);
    var f = function (n) { return l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1))); };
    return rgbToHex(f(0) * 255, f(8) * 255, f(4) * 255);
  }
  function shiftL(hex, delta) {
    var hsl = hexToHsl(hex);
    return hslToHex(hsl[0], hsl[1], clamp(hsl[2] + delta, 0, 100));
  }
  function rgba(hex, a) { var c = hexToRgb(hex); return 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + a + ')'; }
  function luminance(hex) {
    var c = hexToRgb(hex).map(function (v) {
      v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }
  function contrast(a, b) {
    var la = luminance(a), lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  // ================= 状态与持久化 =================
  function defaults() {
    var o = {};
    ITEMS.forEach(function (it) { o[it.key] = it.def; });
    return o;
  }
  function loadSaved() {
    var o = defaults();
    try {
      var raw = JSON.parse(window.localStorage.getItem(STORAGE_KEY) || '{}');
      ITEMS.forEach(function (it) { var h = normHex(raw[it.key]); if (h) o[it.key] = h; });
    } catch (e) { /* 本机存储不可用时使用默认配色 */ }
    return o;
  }
  function persist(o) {
    try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify(o)); return true; } catch (e) { return false; }
  }
  function sanitize(raw) {
    var o = defaults();
    ITEMS.forEach(function (it) { var h = normHex(raw && raw[it.key]); if (h) o[it.key] = h; });
    return o;
  }
  function same(a, b) {
    return ITEMS.every(function (it) { return a[it.key] === b[it.key]; });
  }

  // ================= 服务端（config/application.yml）=================
  function fetchServer() {
    return fetch(API, { cache: 'no-store' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (body) { return sanitize(body && body.colors); });
  }

  /** 从服务端同步统一配色；配色面板打开编辑时不打断 */
  function syncFromServer() {
    if (offline) return;
    fetchServer().then(function (theme) {
      persist(theme);
      if (same(theme, saved)) return;
      saved = theme;
      if (!panel || panel.hidden) { current = Object.assign({}, saved); apply(saved); }
    }).catch(function () { /* 后端暂不可用时沿用缓存配色 */ });
  }

  function getKey() {
    try { return window.sessionStorage.getItem(KEY_STORAGE) || ''; } catch (e) { return ''; }
  }
  function setKey(k) {
    try { window.sessionStorage.setItem(KEY_STORAGE, k); } catch (e) { /* ignore */ }
  }

  function putServer(theme, retried) {
    var headers = { 'Content-Type': 'application/json' };
    var key = getKey();
    if (key) headers['X-Theme-Key'] = key;
    return fetch(API, { method: 'PUT', headers: headers, body: JSON.stringify(theme) })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (body) {
          if (r.status === 403 && !retried) {
            var k = window.prompt((body && body.error ? body.error + '，' : '') + '请输入配色口令（config/application.yml 中 overview.theme.edit-key）');
            if (k == null) throw new Error('已取消');
            setKey(k.trim());
            return putServer(theme, true);
          }
          if (!r.ok) throw new Error((body && body.error) || ('HTTP ' + r.status));
          return sanitize(body.colors);
        });
      });
  }

  var saved = loadSaved();
  var current = Object.assign({}, saved);

  function apply(theme) {
    var root = document.documentElement.style;
    ITEMS.forEach(function (it) {
      var vars = it.vars(theme[it.key]);
      Object.keys(vars).forEach(function (k) { root.setProperty(k, vars[k]); });
    });
  }
  apply(saved); // 尽早生效，避免首屏闪回默认色

  // ================= 面板 =================
  var panel = null;
  var rows = {};

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function previewHtml() {
    var card = function (bed, name, who, right, isNew) {
      return '<div class="p-card tp-card' + (isNew ? ' is-new24' : '') + '">'
        + '<div class="p-head"><span class="p-bed">' + bed + '床</span>'
        + '<span class="p-ident"><span class="p-name">' + name + '</span><span class="p-who">' + who + '</span></span>'
        + '<span class="p-head-right">' + right + '</span></div>'
        + '<div class="tp-body">' + (isNew ? '肾功能不全 · 住院号2081093' : '慢阻肺急性加重 · 住院号2068345') + '</div></div>';
    };
    return card('16', '彭光学', '男76岁', '<span class="pill pill-new">新收 00:30</span>', true)
      + card('03', '陈康强', '男72岁', '<span class="pill pill-iso">接触隔离</span>'
        + '<span class="pill pill-days long">入科27天</span><span class="pill pill-days long">长程</span>', false);
  }

  function buildPanel() {
    panel = el('aside', 'theme-panel');
    panel.id = 'themePanel';
    panel.hidden = true;
    panel.setAttribute('aria-label', '颜色标识配置');
    panel.innerHTML = '<div class="tp-head"><span>颜色标识配置</span>'
      + '<span class="tp-sub">' + (offline ? '离线预览：保存仅本机生效' : '修改即时预览，保存后所有大屏统一生效') + '</span>'
      + '<button type="button" class="icon-btn" data-act="cancel">关闭</button></div>'
      + '<div class="tp-preview">' + previewHtml() + '</div>'
      + '<div class="tp-list"></div>'
      + '<div class="tp-foot"><button type="button" data-act="reset">恢复默认</button>'
      + '<span class="tp-gap"></span><button type="button" data-act="cancel">取消</button>'
      + '<button type="button" class="active" data-act="save">保存</button></div>';

    var list = panel.querySelector('.tp-list');
    ITEMS.forEach(function (it) { list.appendChild(buildRow(it)); });

    panel.addEventListener('click', function (e) {
      var btn = e.target.closest('[data-act]');
      if (!btn) return;
      var act = btn.getAttribute('data-act');
      if (act === 'save') {
        if (offline) {
          saved = Object.assign({}, current);
          notify(persist(saved) ? '离线预览：配色已保存在本机' : '保存失败：浏览器禁止本地存储');
          close(true);
          return;
        }
        btn.disabled = true;
        btn.textContent = '保存中…';
        putServer(Object.assign({}, current), false).then(function (theme) {
          saved = theme;
          current = Object.assign({}, theme);
          persist(saved);
          apply(saved);
          notify('配色已保存到服务器（config/application.yml），所有大屏统一生效');
          close(true);
        }).catch(function (err) {
          notify('配色保存失败：' + err.message);
        }).then(function () {
          btn.disabled = false;
          btn.textContent = '保存';
        });
      } else if (act === 'cancel') {
        close(false);
      } else if (act === 'reset') {
        current = defaults();
        refreshAll();
      }
    });
    document.body.appendChild(panel);
  }

  function slider(name, label) {
    return '<label class="tp-slider"><span>' + label + '</span>'
      + '<input type="range" data-ch="' + name + '" min="0" max="' + (name === 'h' ? 360 : 100) + '" step="1">'
      + '<b data-val="' + name + '"></b></label>';
  }

  function buildRow(it) {
    var row = el('div', 'tp-item');
    row.innerHTML = '<div class="tp-item-head">'
      + '<div class="tp-item-title"><b>' + it.label + '</b><small>' + it.desc + '</small></div>'
      + '<input type="color" class="tp-picker" title="取色器">'
      + '<input type="text" class="tp-hex" maxlength="7" spellcheck="false" placeholder="#RRGGBB" title="输入色号，如 #0EA5A4">'
      + '</div>'
      + slider('h', '色相') + slider('s', '饱和度') + slider('l', '亮度')
      + '<div class="tp-item-foot"><span class="tp-presets">'
      + it.presets.map(function (c) {
        return '<button type="button" class="tp-swatch" data-color="' + c + '" title="' + c + '" style="background:' + c + '"></button>';
      }).join('')
      + '</span><span class="tp-contrast"></span>'
      + '<button type="button" class="tp-link" data-reset="1" title="恢复本项默认 ' + it.def + '">默认</button></div>';

    var r = {
      row: row,
      picker: row.querySelector('.tp-picker'),
      hex: row.querySelector('.tp-hex'),
      h: row.querySelector('[data-ch="h"]'),
      s: row.querySelector('[data-ch="s"]'),
      l: row.querySelector('[data-ch="l"]'),
      contrast: row.querySelector('.tp-contrast')
    };
    rows[it.key] = r;

    var onSlide = function () {
      set(it.key, hslToHex(+r.h.value, +r.s.value, +r.l.value), 'slider');
    };
    [r.h, r.s, r.l].forEach(function (inp) { inp.addEventListener('input', onSlide); });
    r.picker.addEventListener('input', function () { set(it.key, normHex(r.picker.value), 'picker'); });
    r.hex.addEventListener('input', function () {
      var h = normHex(r.hex.value);
      r.hex.classList.toggle('invalid', !h);
      if (h) set(it.key, h, 'hex');
    });
    r.hex.addEventListener('blur', function () { r.hex.classList.remove('invalid'); syncRow(it, null); });
    row.addEventListener('click', function (e) {
      var sw = e.target.closest('.tp-swatch');
      if (sw) set(it.key, sw.getAttribute('data-color'), null);
      if (e.target.closest('[data-reset]')) set(it.key, it.def, null);
    });
    return row;
  }

  function set(key, hex, source) {
    if (!hex) return;
    current[key] = hex;
    apply(current);
    ITEMS.forEach(function (it) {
      // 滑块拖动时不回写本行 HSL，避免取整导致色相/饱和度跳动
      syncRow(it, it.key === key ? source : null);
    });
  }

  function syncRow(it, source) {
    var r = rows[it.key];
    if (!r) return;
    var hex = current[it.key];
    var hsl = hexToHsl(hex);
    if (source !== 'slider') { r.h.value = hsl[0]; r.s.value = hsl[1]; r.l.value = hsl[2]; }
    if (source !== 'hex') { r.hex.value = hex; r.hex.classList.remove('invalid'); }
    if (source !== 'picker') r.picker.value = hex.toLowerCase();
    var h = +r.h.value, s = +r.s.value, l = +r.l.value;
    r.row.querySelector('[data-val="h"]').textContent = h + '°';
    r.row.querySelector('[data-val="s"]').textContent = s + '%';
    r.row.querySelector('[data-val="l"]').textContent = l + '%';
    r.h.style.background = 'linear-gradient(90deg,' + [0, 60, 120, 180, 240, 300, 360].map(function (x) {
      return 'hsl(' + x + ',' + s + '%,' + l + '%)';
    }).join(',') + ')';
    r.s.style.background = 'linear-gradient(90deg,hsl(' + h + ',0%,' + l + '%),hsl(' + h + ',100%,' + l + '%))';
    r.l.style.background = 'linear-gradient(90deg,#000,hsl(' + h + ',' + s + '%,50%),#fff)';
    r.row.querySelectorAll('.tp-swatch').forEach(function (b) {
      b.classList.toggle('on', b.getAttribute('data-color') === hex);
    });

    // 可读性提示（WCAG：≥4.5 优，≥3 大字可用）
    if (it.contrastOn) {
      var bg = it.contrastOn.charAt(0) === '#' ? it.contrastOn : current[it.contrastOn];
      var c = contrast(hex, bg);
      var level = c >= 4.5 ? 'good' : c >= 3 ? 'ok' : 'bad';
      r.contrast.className = 'tp-contrast ' + level;
      r.contrast.textContent = '与' + it.contrastLabel + '对比 ' + c.toFixed(1)
        + (level === 'good' ? ' 清晰' : level === 'ok' ? ' 可用' : ' 偏低');
    } else {
      r.contrast.textContent = '';
    }
  }

  function refreshAll() {
    apply(current);
    ITEMS.forEach(function (it) { syncRow(it, null); });
  }

  function open() {
    if (!panel) buildPanel();
    current = Object.assign({}, saved);
    refreshAll();
    panel.hidden = false;
    document.body.classList.add('theme-open');
    var btn = document.getElementById('btnTheme');
    if (btn) btn.classList.add('active');
  }

  function close(keep) {
    if (!keep) { current = Object.assign({}, saved); apply(saved); }
    if (panel) panel.hidden = true;
    document.body.classList.remove('theme-open');
    var btn = document.getElementById('btnTheme');
    if (btn) btn.classList.remove('active');
  }

  function notify(msg) {
    var t = document.getElementById('toast');
    if (!t) return;
    t.textContent = msg;
    t.hidden = false;
    setTimeout(function () { t.hidden = true; }, 2500);
  }

  function init() {
    var btn = document.getElementById('btnTheme');
    if (btn) btn.addEventListener('click', function () {
      if (panel && !panel.hidden) close(false); else open();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && panel && !panel.hidden) close(false);
    });
    syncFromServer();
    if (!offline) setInterval(syncFromServer, SYNC_MS);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  window.IcuTheme = { open: open, close: close, defaults: defaults };
})();
