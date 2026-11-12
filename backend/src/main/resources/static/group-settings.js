(() => {
  'use strict';
  const button = document.getElementById('btnGroupSettings');
  const dialog = document.createElement('dialog');
  dialog.className = 'group-settings';
  dialog.setAttribute('aria-labelledby', 'group-settings-title');
  dialog.innerHTML = `<form method="dialog" class="group-form">
    <header><div><h2 id="group-settings-title">组别配置</h2><p id="group-settings-dept"></p></div><button class="group-close" value="cancel" aria-label="关闭">×</button></header>
    <div class="group-content"><p>将原医疗组名称映射为本科室使用的名称；留空则显示原名称。相同显示名称会合并统计和筛选。</p>
    <div class="group-labels"><span>原组别</span><span>显示名称</span><span></span></div>
    <div id="group-rows"></div><button id="group-add" class="group-secondary" type="button">＋ 添加原组别</button>
    <label class="group-key-label" for="group-key">管理口令</label><input id="group-key" type="password" autocomplete="off" placeholder="输入组别管理口令">
    <p id="group-status" role="status" aria-live="polite"></p></div>
    <footer><button class="group-secondary" value="cancel">取消</button><button id="group-save" type="button">保存配置</button></footer></form>`;
  document.body.appendChild(dialog);
  const rows = dialog.querySelector('#group-rows'), status = dialog.querySelector('#group-status');
  const saveButton = dialog.querySelector('#group-save');
  let department = '', revision = 0, requestSeq = 0;
  function message(text, error) { status.textContent = text; status.className = error ? 'error' : ''; }
  function addRow(source = '', display = '') {
    const row = document.createElement('div'); row.className = 'group-row';
    const a = document.createElement('input'); a.placeholder = '原组别名称'; a.value = source; a.maxLength = 100; a.setAttribute('aria-label', '原组别');
    const b = document.createElement('input'); b.placeholder = '留空则使用原名称'; b.value = display; b.maxLength = 100; b.setAttribute('aria-label', '显示名称');
    const del = document.createElement('button'); del.type = 'button'; del.className = 'group-remove'; del.textContent = '删除'; del.setAttribute('aria-label', '删除此行');
    del.addEventListener('click', () => row.remove()); row.append(a, b, del); rows.appendChild(row);
  }
  button.addEventListener('click', async () => {
    department = window.patientCardGroupContext();
    if (!department) { alert('尚未获取科室信息，请稍后再试。'); return; }
    const requestDept = department, seq = ++requestSeq;
    rows.replaceChildren(); revision = 0; saveButton.disabled = true;
    dialog.querySelector('#group-key').value = '';
    dialog.querySelector('#group-settings-dept').textContent = `当前科室编码：${department}`;
    message('正在读取配置…'); dialog.showModal();
    try {
      const response = await fetch(`/api/group-mappings/${encodeURIComponent(department)}`, {cache: 'no-store'});
      if (!response.ok) throw new Error('读取失败，请稍后重试');
      const data = await response.json();
      if (seq !== requestSeq || !dialog.open) return;
      if (requestDept !== window.patientCardGroupContext()) { dialog.close(); return; }
      revision = data.revision;
      const mapped = new Map((data.mappings || []).map(x => [x.source, x.display]));
      const names = new Set([...(data.availableGroups || []), ...mapped.keys()]);
      [...names].sort((a,b) => a.localeCompare(b, 'zh')).forEach(name => addRow(name, mapped.get(name) || ''));
      if (!names.size) addRow();
      saveButton.disabled = !data.editingEnabled;
      message(data.editingEnabled ? '修改后点击保存，所有客户端刷新后生效。' : '尚未设置组别管理口令，请联系管理员配置后重启服务。', !data.editingEnabled);
    } catch (e) { if (seq === requestSeq && dialog.open) message(e.message || '读取失败，请稍后重试', true); }
  });
  dialog.querySelector('#group-add').addEventListener('click', () => addRow());
  saveButton.addEventListener('click', async () => {
    if (department !== window.patientCardGroupContext()) { message('科室已切换，请关闭后重新配置。', true); return; }
    const mapping = [], seen = new Set();
    for (const row of rows.querySelectorAll('.group-row')) {
      const inputs = row.querySelectorAll('input'), source = inputs[0].value.trim(), display = inputs[1].value.trim();
      if (!source && !display) continue;
      if (!source) { message('请填写原组别名称。', true); inputs[0].focus(); return; }
      if (!display) continue; // blank removes an existing alias and preserves original
      if (source === '未分组' || display === '未分组') { message('“未分组”为系统保留名称。', true); return; }
      if (seen.has(source)) { message(`原组别重复：${source}`, true); return; }
      seen.add(source); mapping.push({source, display});
    }
    const key = dialog.querySelector('#group-key').value;
    if (!key.trim()) { message('请输入组别管理口令。', true); return; }
    const saveDept = department, seq = requestSeq;
    saveButton.disabled = true; message('正在保存…');
    try {
      const response = await fetch(`/api/group-mappings/${encodeURIComponent(department)}`, {
        method: 'PUT', headers: {'Content-Type':'application/json', 'X-Group-Edit-Key':key},
        body: JSON.stringify({revision, mappings:mapping})
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || '保存失败，请重试');
      if (seq === requestSeq && dialog.open) dialog.close();
      if (saveDept === window.patientCardGroupContext()) window.patientCardReload();
    } catch (e) { if (seq === requestSeq && dialog.open) { message(e.message || '保存失败，请重试', true); saveButton.disabled = false; } }
  });
  dialog.addEventListener('close', () => { requestSeq++; dialog.querySelector('#group-key').value = ''; });
})();
