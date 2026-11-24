/* PhotoVault 网页控制台。独立文件，避免 Python 字符串转义踩坑。 */
let TK = localStorage.getItem('pv_token') || '';
let API_BASE = (function () {
  var m = document.querySelector('meta[name="pv-base"]');
  if (!m) return '';
  var s = m.getAttribute('content') || '';
  while (s.length && s.charAt(s.length - 1) === '/') s = s.slice(0, -1);
  return s;
})();

let ME = null;
let IS_ADMIN = false;
let ALL = [];
let TOTAL = 0;
let OFFSET = 0;
const PAGE = 300;
let inTrash = false;
let selecting = false;
let loading = false;
let loadSeq = 0;
let loadTimer = null;
let FOLDERS = [];
let FOLDER = '';
const picked = new Set();

/** HTML 转义。用户名、显示名、文件名都可能含引号/尖括号，直接拼 innerHTML 会 XSS。 */
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
  });
}

// 自己的弹窗：原生 confirm/alert 在用户点过几次后会被浏览器永久拦截。
let modalResolve = null;
let modalTextMode = false;

function ask(msg, okText) {
  return new Promise(function (resolve) {
    if (document.getElementById('modal').style.display === 'flex') { resolve(false); return; }
    modalTextMode = false;
    modalResolve = resolve;
    const inp = document.getElementById('modalInput');
    inp.classList.add('hide');
    document.getElementById('modalMsg').innerText = msg;
    document.getElementById('modalOk').textContent = okText || '确定';
    document.getElementById('modal').style.display = 'flex';
  });
}

/** 带输入框的确认框：替代 prompt()，避免被浏览器拦截、也能统一验证。 */
function askText(msg, placeholder, okText) {
  return new Promise(function (resolve) {
    if (document.getElementById('modal').style.display === 'flex') { resolve(null); return; }
    modalTextMode = true;
    modalResolve = resolve;
    const inp = document.getElementById('modalInput');
    inp.value = '';
    inp.placeholder = placeholder || '';
    inp.classList.remove('hide');
    document.getElementById('modalMsg').innerText = msg;
    document.getElementById('modalOk').textContent = okText || '确定';
    document.getElementById('modal').style.display = 'flex';
    setTimeout(function () { inp.focus(); }, 30);
  });
}

function modalAnswer(v) {
  document.getElementById('modal').style.display = 'none';
  if (!modalResolve) return;
  const resolve = modalResolve;
  modalResolve = null;
  if (modalTextMode) {
    const val = document.getElementById('modalInput').value;
    modalTextMode = false;
    resolve(v ? val : null);
  } else {
    resolve(v);
  }
}

function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.style.display = 'block';
  clearTimeout(window.__toastTimer);
  window.__toastTimer = setTimeout(function () { t.style.display = 'none'; }, 3600);
}

async function api(path, opt = {}) {
  const headers = Object.assign({'Authorization': 'Bearer ' + TK}, opt.headers || {});
  const r = await fetch(API_BASE + path, Object.assign(
    {credentials: 'same-origin'}, opt, {headers: headers}));
  if (r.status === 401) {
    localStorage.removeItem('pv_token');
    location.reload();
    throw new Error('401');
  }
  if (!r.ok) {
    const detail = await r.json().catch(function () { return {}; });
    throw new Error(detail.detail || ('HTTP ' + r.status));
  }
  return r.json();
}

async function boot() {
  if (!TK) { document.getElementById('login').classList.remove('hide'); return; }
  try {
    const d = await api('/api/auth/me');
    ME = d.user;
    IS_ADMIN = !!d.is_admin;
    if (ME && ME.must_change_password) {
      document.getElementById('force').classList.remove('hide');
      return;
    }
    document.getElementById('app').classList.remove('hide');
    document.getElementById('who').textContent = (d.legacy ? '兼容 Token' : (ME.display_name + '（' + ME.role + '）'));
    if (!IS_ADMIN) {
      document.getElementById('tab-users').classList.add('hide');
      document.getElementById('tab-maint').classList.add('hide');
      document.getElementById('ownerSel').classList.add('hide');
    }
    if (IS_ADMIN) await loadOwners();
    loadPhotos(true);
    loadStats();
    loadFolders();
    loadAbout();
    if (IS_ADMIN) { loadUsers(); loadDevices(); }
  } catch (e) {
    TK = '';
    document.getElementById('login').classList.remove('hide');
  }
}

async function doLogin() {
  const u = document.getElementById('lu').value;
  const p = document.getElementById('lp').value;
  document.getElementById('lerr').textContent = '';
  try {
    const r = await fetch(API_BASE + '/api/auth/login', {
      method: 'POST', credentials: 'same-origin',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({username: u, password: p}),
    });
    const d = await r.json();
    if (!r.ok) throw new Error(d.detail || '登录失败');
    TK = d.token;
    localStorage.setItem('pv_token', TK);
    location.reload();
  } catch (e) { document.getElementById('lerr').textContent = e.message; }
}

async function doLogout() {
  try { await api('/api/auth/logout', {method: 'POST'}); } catch (e) {}
  localStorage.removeItem('pv_token');
  location.reload();
}

function show(v) {
  const isPhotos = v === 'photos' || v === 'trash';
  document.getElementById('v-photos').classList.toggle('hide', !isPhotos);
  document.getElementById('v-users').classList.toggle('hide', v !== 'users');
  document.getElementById('v-maint').classList.toggle('hide', v !== 'maint');
  document.getElementById('v-about').classList.toggle('hide', v !== 'about');
  document.getElementById('tab-photos').classList.toggle('on', v === 'photos');
  document.getElementById('tab-trash').classList.toggle('on', v === 'trash');
  document.getElementById('tab-users').classList.toggle('on', v === 'users');
  document.getElementById('tab-maint').classList.toggle('on', v === 'maint');
  document.getElementById('tab-about').classList.toggle('on', v === 'about');

  if (isPhotos) {
    inTrash = v === 'trash';
    clearSelectionUi();
    document.getElementById('folderBar').classList.toggle('hide', inTrash);
    document.getElementById('delBtn').textContent = inTrash ? '永久删除所选' : '删除所选';
    loadPhotos(true);
  } else if (v === 'about') {
    loadAbout();
  }
}

function clearSelectionUi() {
  selecting = false;
  picked.clear();
  document.querySelectorAll('.pick').forEach(function (e) {
    e.checked = false;
    e.classList.add('hide');
  });
  document.getElementById('delBtn').classList.add('hide');
  document.getElementById('allBtn').classList.add('hide');
  document.getElementById('restoreSelBtn').classList.add('hide');
  updateDelBtn();
}

async function loadAbout() {
  const build = document.getElementById('build');
  document.getElementById('aboutBuild').textContent = build ? build.textContent : '__BUILD__';
  document.getElementById('aboutBase').textContent = API_BASE || location.origin;
  document.getElementById('aboutUser').textContent = ME
    ? (ME.display_name + '（' + ME.username + ' / ' + ME.role + '）')
    : (IS_ADMIN ? '兼容 Token' : '—');
  const health = document.getElementById('aboutHealth');
  try {
    const r = await fetch(API_BASE + '/api/health', {cache: 'no-store'});
    const h = await r.json();
    document.getElementById('aboutVersion').textContent = h.version || '—';
    health.textContent = h.ok ? '正常' : '异常';
    health.style.color = h.ok ? 'var(--text)' : 'var(--danger)';
  } catch (e) {
    document.getElementById('aboutVersion').textContent = '—';
    health.textContent = '无法连接';
    health.style.color = 'var(--danger)';
  }
  loadHttpsState();
}

async function loadHttpsState() {
  const row = document.getElementById('httpsAdminRow');
  if (!row) return;
  if (!IS_ADMIN) { row.classList.add('hide'); return; }
  row.classList.remove('hide');
  const toggle = document.getElementById('httpsToggle');
  const state = document.getElementById('httpsState');
  try {
    const d = await api('/api/admin/https');
    toggle.checked = !!d.enabled;
    toggle.disabled = !d.cert_present;
    if (d.enabled) {
      state.textContent = '当前：已启用' + (d.cert_valid ? '（证书有效）' : '（证书可能无效）');
    } else if (!d.cert_present) {
      state.textContent = '未找到证书：请先把 .crt / .key 放到 data/certs';
    } else {
      state.textContent = '当前：已关闭，8765 使用 HTTP';
    }
  } catch (e) {
    state.textContent = '读取 HTTPS 状态失败：' + e.message;
  }
}

async function toggleHttps(el) {
  const on = el.checked;
  const ok = await ask(
    on
      ? '启用 HTTPS？\n\n服务会重启，当前页面会短暂断开。\n重启后请用 https:// 地址重新打开。'
      : '关闭 HTTPS？\n\n8765 会回到 HTTP，当前 HTTPS 页面会断开。\n重启后请用 http:// 地址重新打开。',
    on ? '启用' : '关闭');
  if (!ok) { el.checked = !on; return; }
  el.disabled = true;
  const state = document.getElementById('httpsState');
  state.textContent = '正在写入设置并重启服务…';
  try {
    const d = await api('/api/admin/https', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({enabled: on}),
    });
    if (!d.restarting) {
      state.textContent = d.enabled ? '当前：已启用' : '当前：已关闭';
      el.disabled = false;
      return;
    }
    state.textContent = on
      ? 'HTTPS 已开启，服务重启中；请改用 https:// 地址重新打开'
      : 'HTTPS 已关闭，服务重启中；请改用 http:// 地址重新打开';
  } catch (e) {
    el.checked = !on;
    el.disabled = false;
    state.textContent = '设置失败：' + e.message;
    toast('HTTPS 设置失败：' + e.message);
  }
}

async function doForceChange() {
  const o = document.getElementById('fop').value;
  const n1 = document.getElementById('fnp1').value;
  const n2 = document.getElementById('fnp2').value;
  const err = document.getElementById('ferr');
  if (n1.length < 8) { err.textContent = '新密码至少 8 位'; return; }
  if (n1 !== n2) { err.textContent = '两次输入的新密码不一致'; return; }
  try {
    const d = await api('/api/auth/password', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({old_password: o, new_password: n1}),
    });
    if (d.token) { TK = d.token; localStorage.setItem('pv_token', TK); }
    location.reload();
  } catch (e) { err.textContent = e.message; }
}

async function changePw() {
  const msg = document.getElementById('pwmsg');
  const n1 = document.getElementById('npw1').value;
  const n2 = document.getElementById('npw2').value;
  if (n1.length < 8) { msg.textContent = '新密码至少 8 位'; return; }
  if (n1 !== n2) { msg.textContent = '两次输入不一致'; return; }
  try {
    const d = await api('/api/auth/password', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({old_password: document.getElementById('opw').value, new_password: n1}),
    });
    if (d.token) { TK = d.token; localStorage.setItem('pv_token', TK); }
    msg.textContent = '已修改，其他设备需要重新登录。';
    document.getElementById('opw').value = '';
    document.getElementById('npw1').value = '';
    document.getElementById('npw2').value = '';
  } catch (e) { msg.textContent = e.message; }
}

async function runCheck() {
  const el = document.getElementById('chk');
  el.textContent = '巡检中…';
  try {
    const d = await api('/api/admin/integrity');
    el.innerHTML = d.healthy
      ? '一切正常：' + d.total + ' 个文件都在，大小一致，没有孤儿文件。'
      : ('索引 ' + d.total + ' 条<br>缺失文件 ' + d.missing_count
         + '<br>大小不符 ' + d.mismatch_count
         + '<br>孤儿文件 ' + d.orphan_count);
  } catch (e) { el.textContent = '巡检失败：' + e.message; }
}

async function purgeTrash() {
  let n = 0;
  try { n = (await api('/api/trash?limit=1')).total; } catch (e) {}
  if (!n) { toast('回收站是空的'); return; }
  const who = IS_ADMIN ? '所有人的回收站' : '回收站';
  const ok = await ask(
    '清空' + who + '？\n\n'
    + '会把这 ' + n + ' 个原始文件从磁盘上永久删除，找不回来。\n\n'
    + '唯一能恢复的办法：手机相册里如果还留着原图，重新备份一次就能回来。',
    '永久删除');
  if (!ok) return;
  try {
    const d = await api('/api/trash', {method: 'DELETE'});
    toast('已彻底清除 ' + d.removed + ' 项');
    if (inTrash) loadPhotos(true);
    loadStats();
  } catch (e) { toast(e.message); }
}

async function restore(id) {
  try { await api('/api/photos/' + encodeURIComponent(id) + '/restore', {method: 'POST'}); loadPhotos(true); }
  catch (e) { toast(e.message); }
}

async function loadOwners() {
  try {
    const d = await api('/api/admin/users');
    const sel = document.getElementById('ownerSel');
    while (sel.options.length > 1) sel.remove(sel.options.length - 1);   // 别越点越多
    d.items.forEach(function (u) {
      const o = document.createElement('option');
      o.value = u.id;
      o.textContent = u.display_name + (u.disabled ? '（已禁用）' : '');
      sel.appendChild(o);
    });
  } catch (e) {}
}

function humanSize(n) {
  if (n == null) return '-';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (i === 0 ? v : v.toFixed(v >= 100 ? 0 : 1)) + ' ' + u[i];
}

async function loadStats() {
  try {
    const s = await api('/api/stats');
    document.getElementById('stats').innerHTML =
      '<b>' + s.images + '</b> 张照片　<b>' + s.videos + '</b> 个视频　共 <b>'
      + s.count + '</b> 项　' + humanSize(s.bytes)
      + (s.with_gps ? '　含位置 ' + s.with_gps + ' 项' : '');
  } catch (e) { /* 统计失败不影响看照片 */ }
}

/** 文件夹（手机上的 rel_dir，如 DCIM/Camera、Pictures/WeiXin） */
async function loadFolders() {
  try {
    const d = await api('/api/folders');
    FOLDERS = d.items || [];
    if (FOLDER && !FOLDERS.some(function (f) { return f.folder === FOLDER; })) FOLDER = '';
    renderFolderBar();
  } catch (e) { /* 文件夹加载失败不影响照片列表 */ }
}

function renderFolderBar() {
  const bar = document.getElementById('folderBar');
  if (!bar) return;
  bar.innerHTML = '';
  const add = function (name, label, count) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip' + (FOLDER === name ? ' on' : '');
    b.textContent = label + (count != null ? '（' + count + '）' : '');
    b.onclick = function () { FOLDER = name; renderFolderBar(); loadPhotos(true); };
    bar.appendChild(b);
  };
  const total = FOLDERS.reduce(function (n, f) { return n + (f.c || 0); }, 0);
  add('', '全部', total);
  FOLDERS.forEach(function (f) { add(f.folder, f.folder, f.c); });
  bar.classList.toggle('hide', inTrash);
}

function scheduleLoad() {
  clearTimeout(loadTimer);
  loadTimer = setTimeout(function () { loadPhotos(true); }, 280);
}

async function loadPhotos(reset = true) {
  if (inTrash) return loadTrash(reset);
  if (loading && !reset) return;
  const seq = ++loadSeq;
  loading = true;
  if (reset) { OFFSET = 0; ALL = []; }
  const q = document.getElementById('q').value.trim();
  const owner = document.getElementById('ownerSel').value;
  const kind = document.getElementById('kindSel').value;
  const qs = new URLSearchParams({limit: String(PAGE), offset: String(OFFSET)});
  if (q) qs.set('q', q);
  if (owner) qs.set('owner', owner);
  if (kind) qs.set('kind', kind);
  if (FOLDER) qs.set('folder', FOLDER);
  try {
    const d = await api('/api/photos?' + qs);
    if (seq !== loadSeq) return;
    ALL = reset ? d.items : ALL.concat(d.items);
    if (reset) TOTAL = d.total || 0;
    OFFSET = ALL.length;
    document.getElementById('st').textContent = (FOLDER ? FOLDER + ' · ' : '')
      + (q ? '搜索到 ' + TOTAL + ' 项' : '共 ' + TOTAL + ' 项');
    document.getElementById('g').innerHTML = renderByDay(ALL, false);
    document.getElementById('more').textContent = ALL.length ? ('已显示 ' + ALL.length + ' / ' + TOTAL) : '';
    document.getElementById('moreBtn').classList.toggle('hide', ALL.length >= TOTAL);
  } catch (e) {
    document.getElementById('st').textContent = '加载失败：' + e.message;
  } finally {
    if (seq === loadSeq) loading = false;
  }
}

async function loadTrash(reset = true) {
  if (loading && !reset) return;
  const seq = ++loadSeq;
  loading = true;
  if (reset) { OFFSET = 0; ALL = []; }
  try {
    const d = await api('/api/trash?limit=' + PAGE + '&offset=' + OFFSET);
    if (seq !== loadSeq) return;
    ALL = reset ? d.items : ALL.concat(d.items);
    if (reset) TOTAL = d.total || 0;
    OFFSET = ALL.length;
    document.getElementById('st').textContent = '回收站 ' + TOTAL + ' 项';
    document.getElementById('g').innerHTML = renderByDay(ALL, true);
    document.getElementById('more').textContent = ALL.length ? ('已显示 ' + ALL.length + ' / ' + TOTAL) : '';
    document.getElementById('moreBtn').classList.toggle('hide', ALL.length >= TOTAL);
  } catch (e) {
    document.getElementById('st').textContent = '加载失败：' + e.message;
  } finally {
    if (seq === loadSeq) loading = false;
  }
}

function loadMore() {
  if (inTrash) loadTrash(false); else loadPhotos(false);
}

// 按拍摄日期分组，每组一个日期条 + 一片网格
function dayKey(p) {
  const t = p.taken_at || p.uploaded_at || Date.now();
  const d = new Date(t);
  const pad = n => (n < 10 ? '0' + n : '' + n);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function dayLabel(k) {
  const now = new Date();
  const pad = n => (n < 10 ? '0' + n : '' + n);
  const today = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
  const y = new Date(now.getTime() - 86400000);
  const yest = y.getFullYear() + '-' + pad(y.getMonth() + 1) + '-' + pad(y.getDate());
  if (k === today) return '今天';
  if (k === yest) return '昨天';
  const parts = k.split('-');
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][new Date(k + 'T00:00:00').getDay()];
  return parts[0] + '年' + parseInt(parts[1], 10) + '月' + parseInt(parts[2], 10) + '日 ' + week;
}

function renderByDay(items, trashed) {
  if (!items.length) {
    return '<div class="empty"><b>' + (trashed ? '回收站是空的' : '还没有照片') + '</b>'
      + (trashed
          ? '删除的照片会先放到这里，可以随时恢复。'
          : '在手机 App 里登录 NAS、打开「自动备份」，照片就会同步上来。')
      + '</div>';
  }
  const groups = new Map();
  items.forEach(p => {
    const k = dayKey(p);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(p);
  });
  return Array.from(groups.entries()).map(([k, list]) =>
    '<div class="daybar">' + dayLabel(k) + '<span>' + list.length + ' 张</span></div>'
    + '<div class="grid">' + list.map(p => cellHtml(p, trashed)).join('') + '</div>'
  ).join('');
}

function cellHtml(p, trashed) {
  const isVid = p.kind === 'video' || String(p.mime || '').startsWith('video');
  const id = esc(p.id);
  const tags = [];
  if (p.taken_at) tags.push(new Date(p.taken_at).toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit'}));
  if (p.gps_lat != null) tags.push('位置');
  const extra = trashed
    ? '<button class="ghost" style="position:absolute;top:6px;left:6px;padding:3px 10px;font-size:12px" '
      + 'onclick="restore(this.dataset.id)" data-id="' + id + '">恢复</button>'
    : '';
  // 缩略图不带 token：登录时下发的 HttpOnly cookie 会随 <img> 请求自动带上
  return '<div class="cell' + (trashed ? ' trashed' : '') + '" data-id="' + id + '">'
    + '<img loading="lazy" decoding="async" src="' + API_BASE + '/api/photos/' + encodeURIComponent(p.id)
    + '/thumb" onclick="view(this.dataset.id,' + isVid + ')" data-id="' + id + '"'
    + ' onerror="this.style.display=\'none\';this.parentElement.classList.add(\'noimg\')">'
    + '<input type="checkbox" class="pick' + (selecting ? '' : ' hide') + '"'
    + (picked.has(p.id) ? ' checked' : '') + ' onchange="pick(this)">'
    + (isVid && !trashed ? '<span class="badge">▶</span>' : '')
    + (tags.length ? '<div class="tag">' + tags.join(' · ') + '</div>' : '')
    + extra + '</div>';
}

// 这层遮罩用 style.display 控制，不能用 class="hide" —— 内联 display:flex 优先级更高
function view(id, isVideo) {
  const img = document.getElementById('vimg');
  const vid = document.getElementById('vvid');
  img.classList.toggle('hide', !!isVideo);
  vid.classList.toggle('hide', !isVideo);
  img.removeAttribute('src');
  vid.pause();
  vid.removeAttribute('src');
  if (isVideo) {
    vid.src = API_BASE + '/api/photos/' + encodeURIComponent(id) + '/file';
    vid.play().catch(function () {});
  } else {
    img.src = API_BASE + '/api/photos/' + encodeURIComponent(id) + '/file';
  }
  document.getElementById('viewer').style.display = 'flex';
}

function closeViewer() {
  document.getElementById('viewer').style.display = 'none';
  document.getElementById('vimg').removeAttribute('src');
  const vid = document.getElementById('vvid');
  vid.pause();
  vid.removeAttribute('src');
}

document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeViewer(); });

function toggleSel() {
  selecting = !selecting;
  document.querySelectorAll('.pick').forEach(e => {
    e.classList.toggle('hide', !selecting);
    if (!selecting) e.checked = false;
  });
  document.getElementById('delBtn').classList.toggle('hide', !selecting);
  document.getElementById('allBtn').classList.toggle('hide', !selecting);
  document.getElementById('restoreSelBtn').classList.toggle('hide', !(selecting && inTrash));
  document.getElementById('allBtn').textContent = '全选';
  if (!selecting) picked.clear();
  updateDelBtn();
}

function pick(el) {
  const id = el.parentElement.dataset.id;
  if (el.checked) picked.add(id); else picked.delete(id);
  updateDelBtn();
}

function updateDelBtn() {
  const b = document.getElementById('delBtn');
  const verb = inTrash ? '永久删除所选' : '删除所选';
  b.textContent = picked.size ? verb + ' (' + picked.size + ')' : verb;
  const r = document.getElementById('restoreSelBtn');
  r.textContent = picked.size ? '恢复所选 (' + picked.size + ')' : '恢复所选';
  r.classList.toggle('hide', !(selecting && inTrash));
}

function selectAll() {
  const boxes = Array.from(document.querySelectorAll('.pick'));
  if (!boxes.length) return;
  const allOn = boxes.every(b => b.checked);
  boxes.forEach(b => { b.checked = !allOn; pick(b); });
  document.getElementById('allBtn').textContent = allOn ? '全选' : '取消全选';
  updateDelBtn();
}

async function deleteSelected() {
  if (!picked.size) {
    toast('还没有勾选照片。先点每张图右上角的方框，或点右边的「全选」。');
    return;
  }
  const ids = Array.from(picked);
  if (inTrash) {
    if (!await ask('永久删除选中的 ' + ids.length + ' 张？\n\n'
        + '会把这些原始文件从 NAS 磁盘上抹掉，无法恢复。', '永久删除')) return;
    document.getElementById('st').textContent = '正在永久删除 ' + ids.length + ' 项…';
    try {
      const d = await api('/api/trash/delete', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ids: ids}),
      });
      toast('已永久删除 ' + d.deleted + ' 项');
    } catch (e) { toast('删除失败：' + e.message); }
  } else {
    if (!await ask('删除选中的 ' + ids.length + ' 张？\n文件会先进回收站，可以随时恢复。', '删除')) return;
    document.getElementById('st').textContent = '正在删除 ' + ids.length + ' 项…';
    try {
      const d = await api('/api/photos/delete', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ids: ids}),
      });
      document.getElementById('st').textContent = '已移入回收站 ' + d.deleted + ' 项';
      if (d.deleted === 0) toast('没有删掉任何东西（可能这些照片本来就在回收站里）。');
    } catch (e) { toast('删除失败：' + e.message); }
  }
  picked.clear();
  toggleSel();
  loadPhotos(true);
  loadStats();
}

async function restoreSelected() {
  if (!picked.size) {
    toast('还没有勾选照片。');
    return;
  }
  const ids = Array.from(picked);
  if (!await ask('恢复选中的 ' + ids.length + ' 张到照片列表？', '恢复')) return;
  try {
    const d = await api('/api/trash/restore', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ids: ids}),
    });
    toast('已恢复 ' + d.restored + ' 项');
  } catch (e) { toast('恢复失败：' + e.message); }
  picked.clear();
  toggleSel();
  loadPhotos(true);
  loadStats();
}

async function loadDevices() {
  if (!IS_ADMIN) return;
  const body = document.getElementById('dt');
  if (!body) return;
  try {
    const d = await api('/api/admin/devices');
    const rows = (d.items || []).map(function (x) {
      const id = x.device_id || '';
      const who = esc(x.display_name || x.username || '—');
      return '<tr><td>' + who + '</td>'
        + '<td>' + (id ? '<code>' + esc(id) + '</code>' : '<span class="muted">无设备 ID</span>') + '</td>'
        + '<td>' + (x.live || 0) + '</td>'
        + '<td>' + (x.trashed || 0) + '</td>'
        + '<td class="muted">' + (x.last_upload_at ? new Date(x.last_upload_at).toLocaleString() : '—') + '</td>'
        + '<td>' + (id ? '<button class="ghost" data-id="' + esc(id) + '" onclick="copyDevice(this)">复制</button>' : '') + '</td></tr>';
    }).join('');
    body.innerHTML = rows || '<tr><td colspan="6" class="muted">还没有设备记录</td></tr>';
  } catch (e) {
    body.innerHTML = '<tr><td colspan="6" class="muted">加载失败：' + esc(e.message) + '</td></tr>';
  }
}

function copyDevice(btn) {
  const text = btn.dataset.id || '';
  const fallback = function () {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); toast('已复制设备 ID'); }
    catch (e) { toast('复制失败，请手动选择文本'); }
    document.body.removeChild(ta);
  };
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(function () { toast('已复制设备 ID'); }).catch(fallback);
  } else {
    fallback();
  }
}

async function loadUsers() {  try {
    const d = await api('/api/admin/users');
    document.getElementById('ut').innerHTML = d.items.map(function (u) {
      const uname = esc(u.username);
      const uid = esc(u.id);
      const self = ME && u.username === ME.username;
      const actions = self ? '<span class="muted">当前账号</span>' :
        '<button class="ghost" data-id="' + uid + '" data-name="' + uname
          + '" onclick="resetPw(this)">重置密码</button> '
        + '<button class="ghost" data-id="' + uid + '" data-name="' + uname
          + '" data-disabled="' + (u.disabled ? '1' : '0')
          + '" onclick="toggleUser(this)">' + (u.disabled ? '启用' : '禁用') + '</button> '
        + '<button class="danger" data-id="' + uid + '" data-name="' + uname
          + '" onclick="delUser(this)">删除</button>';
      return '<tr><td>' + esc(u.display_name) + '<div class="muted">' + uname + '</div></td>'
        + '<td><span class="pill ' + (u.role === 'admin' ? 'admin' : '') + '">' + esc(u.role) + '</span></td>'
        + '<td>' + (u.disabled ? '<span class="pill off">已禁用</span>' : '正常') + '</td>'
        + '<td class="muted">' + (u.last_login_at ? new Date(u.last_login_at).toLocaleString() : '从未登录') + '</td>'
        + '<td>' + actions + '</td></tr>';
    }).join('');
  } catch (e) { toast('用户列表加载失败：' + e.message); }
}

async function createUser() {
  const body = {
    username: document.getElementById('nu').value,
    password: document.getElementById('np').value,
    display_name: document.getElementById('nd').value,
    role: document.getElementById('nr').value,
  };
  if (!body.username || !body.password) { toast('用户名和密码都要填'); return; }
  if (body.password.length < 8) { toast('密码至少 8 位'); return; }
  try {
    await api('/api/admin/users', {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body),
    });
    document.getElementById('nu').value = '';
    document.getElementById('np').value = '';
    document.getElementById('nd').value = '';
    loadUsers();
    loadOwners();
  } catch (e) { toast(e.message); }
}

async function resetPw(btn) {
  const name = btn.dataset.name || '该用户';
  const p = await askText('给「' + name + '」设置新密码（至少 8 位）：', '新密码', '重置');
  if (!p) return;
  if (p.length < 8) { toast('密码至少 8 位'); return; }
  try {
    await api('/api/admin/users/' + encodeURIComponent(btn.dataset.id) + '/password', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({new_password: p}),
    });
    toast('已重置。该用户已登录的手机需要重新登录。');
  } catch (e) { toast(e.message); }
}

async function toggleUser(btn) {
  const disabled = btn.dataset.disabled === '1';
  const name = btn.dataset.name || '该用户';
  const next = !disabled;
  if (next && !await ask('禁用「' + name + '」？该账号所有已登录设备会立即掉线。', '禁用')) return;
  try {
    await api('/api/admin/users/' + encodeURIComponent(btn.dataset.id), {
      method: 'PATCH', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({disabled: next}),
    });
    loadUsers();
  } catch (e) { toast(e.message); }
}

async function delUser(btn) {
  const name = btn.dataset.name || '该用户';
  if (!await ask('删除账号「' + name + '」？\n其照片会转到你名下，不会丢失。', '删除')) return;
  try {
    await api('/api/admin/users/' + encodeURIComponent(btn.dataset.id), {method: 'DELETE'});
    loadUsers();
    loadOwners();
  } catch (e) { toast(e.message); }
}

boot();
