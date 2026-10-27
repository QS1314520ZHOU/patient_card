/**
 * 宿主桥接：监听科室概览 iframe 的 SmartCareNavigate，
 * 双击选中患者后，通过宿主菜单打开「病情概览」新页签。
 *
 * 安装：宿主 index.html 的 </body> 前加入：
 *   <script src="http://192.168.31.167:18088/patient-nav-bridge.js"></script>
 * 然后 Ctrl+F5 强刷。
 */
(function () {
  'use strict';
  window.__patientNavBridge = true;

  function notifyIframes() {
    try {
      var iframes = document.querySelectorAll('iframe');
      for (var i = 0; i < iframes.length; i++) {
        try {
          iframes[i].contentWindow.postMessage({ type: 'SmartCareNavigateReady' }, '*');
        } catch (e) { /* ignore */ }
      }
    } catch (e) { /* ignore */ }
  }
  notifyIframes();
  setInterval(notifyIframes, 2000);

  function mouseClick(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    var opts = {
      bubbles: true, cancelable: true, view: window,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0
    };
    ['pointerover', 'mouseover', 'mouseenter', 'mousemove',
      'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']
      .forEach(function (t) { el.dispatchEvent(new MouseEvent(t, opts)); });
    return true;
  }

  function closeOverlays() {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
    document.body.click();
  }

  function matchRow(row, keys) {
    var t = row.textContent || '';
    if (keys.mrn && t.indexOf(keys.mrn) >= 0) return true;
    if (keys.name && t.indexOf(keys.name) >= 0) return true;
    if (keys.id && t.indexOf(keys.id) >= 0) return true;
    if (keys.bed) {
      var bedNum = String(keys.bed).replace(/^0+/, '') || '0';
      var re = new RegExp('(^|\\D)' + bedNum + '(\\D|$)');
      if (re.test(t)) return true;
    }
    return false;
  }

  function findRow(keys) {
    var rows = document.querySelectorAll('tbody tr');
    for (var i = 0; i < rows.length; i++) {
      if (matchRow(rows[i], keys)) return rows[i];
    }
    return null;
  }

  function findTopMenuAnchor(name) {
    var anchors = document.querySelectorAll('a');
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      if ((a.textContent || '').trim() !== name) continue;
      var r = a.getBoundingClientRect();
      if (r.width > 0 && r.height > 0 && r.top < 50 && r.top >= 0) return a;
    }
    return null;
  }

  function findVisibleLink(text) {
    var links = document.querySelectorAll('a.ellipsis, a');
    for (var i = 0; i < links.length; i++) {
      if ((links[i].textContent || '').trim() === text) {
        var r = links[i].getBoundingClientRect();
        if (r.width > 0 && r.height > 0) return links[i];
      }
    }
    return null;
  }

  function openReviewTab() {
    closeOverlays();
    setTimeout(function () {
      var doctor = findTopMenuAnchor('医生');
      if (doctor) mouseClick(doctor);
      setTimeout(function () {
        var link = findVisibleLink('病情概览');
        if (link) { mouseClick(link); return; }
        closeOverlays();
        setTimeout(function () {
          var doctor2 = findTopMenuAnchor('医生');
          if (doctor2) mouseClick(doctor2);
          setTimeout(function () {
            var link2 = findVisibleLink('病情概览');
            if (link2) mouseClick(link2);
          }, 800);
        }, 400);
      }, 800);
    }, 300);
  }

  function selectPatient(keys, done) {
    closeOverlays();
    var row = findRow(keys);
    if (row) {
      row.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));
      setTimeout(done, 1200);
      return;
    }
    var t0 = Date.now();
    var timer = setInterval(function () {
      var r = findRow(keys);
      if (r) {
        clearInterval(timer);
        r.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));
        setTimeout(done, 1200);
        return;
      }
      if (Date.now() - t0 > 2500) {
        clearInterval(timer);
        var hash = location.hash || '';
        if (hash.indexOf('online-patient') < 0 && hash.indexOf('patient-list') < 0 && hash.indexOf('iframe-view') < 0) {
          location.hash = '#/main/patient/patient-list/online-patient';
        }
        var t1 = Date.now();
        var timer2 = setInterval(function () {
          var r2 = findRow(keys);
          if (r2) {
            clearInterval(timer2);
            r2.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));
            setTimeout(done, 1200);
          } else if (Date.now() - t1 > 5000) {
            clearInterval(timer2);
            done();
          }
        }, 200);
      }
    }, 200);
  }

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || d.type !== 'SmartCareNavigate' || d.action !== 'openPatientReview') return;
    try {
      if (e.origin && e.origin !== location.origin) {
        if (!d.patientId && !d.mrn && !d.name) return;
      }
    } catch (err) { /* ignore */ }

    notifyIframes();
    selectPatient({ id: d.patientId, mrn: d.mrn, bed: d.bed, name: d.name }, function () {
      openReviewTab();
    });
  });
})();
