const state = {
  profiles: [],
  runtime: new Map(),
  selectedId: null,
  filter: "ALL",
  activeView: "devices",
  busy: false
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const profileDialog = $("#profileDialog");
const credentialDialog = $("#credentialDialog");
const form = $("#profileForm");

const templates = {
  MQTT: `{
  "productKey": "${"${productKey}"}",
  "deviceCode": "${"${deviceCode}"}",
  "sequence": ${"${sequence}"},
  "occurredAt": "${"${timestamp}"}",
  "metrics": { "speed": 32.6, "load": 68.4, "engineTemp": 86.2 }
}`,
  TCP_CLIENT: `{
  "deviceCode": "${"${deviceCode}"}",
  "sequence": ${"${sequence}"},
  "timestamp": ${"${epochMillis}"},
  "status": "RUNNING"
}`,
  TCP_SERVER: "68 01 00 08 10 20 30 40 16"
};

const transportPresets = {
  MQTT: {
    hostLabel: "Broker 地址", host: "127.0.0.1", hostPlaceholder: "例如：127.0.0.1 或 mqtt.example.com",
    hostHelp: "平台 MQTT Broker 的主机名或 IP，例如 127.0.0.1。",
    portLabel: "Broker 端口", port: 1883,
    portHelp: "由平台 Broker 提供，常用端口为 1883；启用 MQTTS 时通常使用 8883。",
    payloadFormat: "JSON"
  },
  TCP_CLIENT: {
    hostLabel: "平台地址", host: "127.0.0.1", hostPlaceholder: "例如：127.0.0.1 或 platform.example.com",
    hostHelp: "平台开放 TCP 接入服务的主机名或 IP，模拟器会主动连接该地址。",
    portLabel: "平台 TCP 端口", port: 9000,
    portHelp: "平台提供的设备接入端口，必须与平台 TCP 服务监听端口一致。",
    payloadFormat: "JSON"
  },
  TCP_SERVER: {
    hostLabel: "监听地址", host: "127.0.0.1", hostPlaceholder: "127.0.0.1 或 0.0.0.0",
    hostHelp: "模拟器等待平台接入的本地地址。127.0.0.1 仅本机可访问，0.0.0.0 可接受外部连接。",
    portLabel: "监听端口", port: 9000,
    portHelp: "模拟器在本机监听的端口，平台需要主动连接到该端口。",
    payloadFormat: "HEX"
  }
};

const statusText = {
  CONNECTED: "已连接",
  CONNECTING: "连接中",
  WAITING: "等待接入",
  FAILED: "连接异常",
  DISCONNECTED: "未连接"
};

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options
  });
  if (!response.ok) {
    let message = `请求失败（${response.status}）`;
    try {
      const problem = await response.json();
      message = problem.detail || problem.title || message;
    } catch (_) { /* 非 JSON 错误使用默认消息。 */ }
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}

async function loadAll({ quiet = false } = {}) {
  if (state.busy) return;
  state.busy = true;
  try {
    const [profiles, runtime, system] = await Promise.all([
      api("/api/profiles"), api("/api/runtime"), api("/api/system")
    ]);
    state.profiles = profiles;
    state.runtime = new Map(runtime.map(item => [item.profileId, item]));
    if (!state.selectedId && profiles.length) state.selectedId = profiles[0].id;
    if (state.selectedId && !profiles.some(profile => profile.id === state.selectedId)) {
      state.selectedId = profiles[0]?.id || null;
    }
    $("#versionText").textContent = `v${system.version} · LOCAL`;
    $("#profilePath").textContent = `配置文件：${system.profileFile}`;
    render();
  } catch (error) {
    if (!quiet) toast(error.message, true);
  } finally {
    state.busy = false;
  }
}

function runtimeFor(profile) {
  return state.runtime.get(profile.id) || {
    profileId: profile.id, running: false, state: "DISCONNECTED",
    sentCount: 0, failedCount: 0, logs: [], lastError: ""
  };
}

function render() {
  const runtimes = state.profiles.map(runtimeFor);
  $("#deviceCount").textContent = state.profiles.length;
  $("#onlineCount").textContent = runtimes.filter(item => item.state === "CONNECTED").length;
  $("#sentCount").textContent = formatNumber(runtimes.reduce((sum, item) => sum + item.sentCount, 0));
  $("#failedCount").textContent = formatNumber(runtimes.reduce((sum, item) => sum + item.failedCount, 0));
  renderDevices();
  renderLogDeviceOptions();
  renderActivity();
}

function renderLogDeviceOptions() {
  const select = $("#logDeviceSelect");
  select.innerHTML = state.profiles.length
    ? state.profiles.map(profile => `<option value="${escapeHtml(profile.id)}">${escapeHtml(profile.name)}</option>`).join("")
    : '<option value="">暂无设备</option>';
  select.value = state.selectedId || "";
}

function renderDevices() {
  const profiles = state.profiles.filter(profile => {
    if (state.filter === "ALL") return true;
    const current = runtimeFor(profile).state;
    return state.filter === "CONNECTED" ? current === "CONNECTED" : current !== "CONNECTED";
  });
  const grid = $("#deviceGrid");
  if (!profiles.length) {
    grid.innerHTML = `<div class="empty-state" style="grid-column:1/-1">
      <div><svg aria-hidden="true" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M8 10h8M8 14h5"/></svg>
      <strong>${state.profiles.length ? "没有符合筛选条件的设备" : "还没有模拟设备"}</strong>
      <p>${state.profiles.length ? "切换筛选条件查看其他连接状态。" : "创建第一台虚拟矿卡、PLC 或车载终端，开始验证平台接入链路。"}</p>
      ${state.profiles.length ? "" : '<button class="button primary" type="button" data-empty-add>添加第一台设备</button>'}</div></div>`;
    return;
  }
  grid.innerHTML = profiles.map(profile => deviceCard(profile, runtimeFor(profile))).join("");
}

function deviceCard(profile, runtime) {
  const running = runtime.running;
  const sendBlockedReason = runtime.state === "CONNECTED"
    ? ""
    : "请先启动设备并等待连接成功，再发送报文";
  const connectionBlockedReason = running
    ? ""
    : "请先启动设备任务，再执行断连操作";
  const protocol = profile.transportMode === "MQTT" ? (profile.tlsEnabled ? "MQTTS" : "MQTT")
    : profile.transportMode === "TCP_CLIENT" ? "TCP CLIENT" : "TCP SERVER";
  const endpoint = `${profile.host}:${profile.port}`;
  const modeIcon = profile.transportMode === "MQTT"
    ? `<path d="M5 16a7 7 0 0 1 7-7M5 11a12 12 0 0 1 12-6M5 20h.01M9 20a4 4 0 0 1 4-4"/>`
    : `<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 9h4M7 13h2M15 10h2M15 14h2"/>`;
  return `<article class="device-card ${state.selectedId === profile.id ? "selected" : ""}" data-profile-id="${escapeHtml(profile.id)}">
    <div class="card-top">
      <span class="device-type"><svg aria-hidden="true" viewBox="0 0 24 24">${modeIcon}</svg></span>
      <div class="device-title"><h3>${escapeHtml(profile.name)}</h3><p>${escapeHtml(profile.deviceCode || profile.clientId || profile.id)}</p></div>
      <span class="status-badge status-${runtime.state}">${statusText[runtime.state] || runtime.state}</span>
    </div>
    <div class="device-meta">
      <div class="meta-item"><span>协议角色</span><strong>${protocol}</strong></div>
      <div class="meta-item"><span>连接端点</span><strong title="${escapeHtml(endpoint)}">${escapeHtml(endpoint)}</strong></div>
      <div class="meta-item"><span>发送间隔</span><strong>${formatInterval(profile.intervalMillis)}</strong></div>
      <div class="meta-item"><span>成功 / 失败</span><strong>${runtime.sentCount} / ${runtime.failedCount}</strong></div>
    </div>
    <div class="card-actions">
      <button class="action-button ${running ? "stop" : "start"}" type="button" data-action="${running ? "stop" : "start"}">${running ? "停止任务" : "启动任务"}</button>
      <button class="action-button" type="button" data-action="send" ${sendBlockedReason ? `aria-disabled="true" data-blocked-reason="${sendBlockedReason}"` : ""}>单发</button>
      <button class="action-button" type="button" data-action="${runtime.state === "DISCONNECTED" && running ? "reconnect" : "disconnect"}" ${connectionBlockedReason ? `aria-disabled="true" data-blocked-reason="${connectionBlockedReason}"` : ""}>${runtime.state === "DISCONNECTED" && running ? "重连" : "断连"}</button>
      <button class="card-menu" type="button" data-action="edit" aria-label="编辑 ${escapeHtml(profile.name)}">编辑</button>
      <button class="card-menu" type="button" data-action="delete" aria-label="删除 ${escapeHtml(profile.name)}">删除</button>
    </div>
  </article>`;
}

function renderActivity() {
  const list = $("#activityList");
  const profile = state.profiles.find(item => item.id === state.selectedId);
  if (!profile) {
    $("#activityHint").textContent = "选择一台设备查看日志";
    list.innerHTML = emptyActivity("暂无运行日志", "设备启动后，连接和报文发送记录会出现在这里。");
    return;
  }
  const runtime = runtimeFor(profile);
  $("#activityHint").textContent = profile.name;
  if (!runtime.logs?.length) {
    list.innerHTML = emptyActivity("尚未产生运行记录", "点击设备卡片中的“启动任务”，开始连接平台并周期发送报文。");
    return;
  }
  list.innerHTML = runtime.logs.map(log => `<div class="activity-item ${log.level === "ERROR" ? "error" : ""}">
    <p>${escapeHtml(log.message)}</p><time datetime="${log.timestamp}">${formatTime(log.timestamp)}</time>
  </div>`).join("");
}

function emptyActivity(title, text) {
  return `<div class="empty-state"><div><svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 19V9M10 19V5M16 19v-7M22 19H2"/></svg><strong>${title}</strong><p>${text}</p></div></div>`;
}

function viewFromHash() {
  return location.hash === "#logs" || location.hash === "#activity" ? "logs" : "devices";
}

function setActiveView(view) {
  const activeView = view === "logs" ? "logs" : "devices";
  state.activeView = activeView;
  $("#workspace").classList.toggle("log-view", activeView === "logs");
  $$(".nav-item[data-view]").forEach(item => {
    const active = item.dataset.view === activeView;
    item.classList.toggle("active", active);
    if (active) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  });
  $("#activityTitle").textContent = activeView === "logs" ? "运行日志" : "实时活动";
}

function navigateToView(view) {
  const targetHash = `#${view === "logs" ? "logs" : "devices"}`;
  if (location.hash === targetHash) setActiveView(view);
  else location.hash = targetHash;
}

function openProfile(profile = null) {
  form.reset();
  $("#formTitle").textContent = profile ? "编辑模拟设备" : "添加模拟设备";
  $("#profileId").value = profile?.id || crypto.randomUUID();
  if (profile) {
    for (const [key, value] of Object.entries(profile)) {
      const field = form.elements.namedItem(key);
      if (!field) continue;
      if (field.type === "checkbox") field.checked = Boolean(value);
      else field.value = value ?? "";
    }
  } else {
    form.elements.transportMode.value = "MQTT";
    form.elements.host.value = "127.0.0.1";
    form.elements.port.value = "1883";
    form.elements.intervalMillis.value = "5000";
    form.elements.qos.value = "1";
    form.elements.payloadFormat.value = "JSON";
    form.elements.payloadTemplate.value = templates.MQTT;
  }
  updateTransportFields(false);
  profileDialog.showModal();
  setTimeout(() => form.elements.name.focus(), 50);
}

function updateTransportFields(resetDefaults = true) {
  const mode = form.elements.transportMode.value;
  const preset = transportPresets[mode];
  $$(".mqtt-field", form).forEach(field => field.hidden = mode !== "MQTT");
  const helps = {
    MQTT: "模拟器作为 MQTT 客户端，主动连接平台 Broker；密码在启动时临时输入。",
    TCP_CLIENT: "模拟器主动连接平台开放的 TCP 端口，适合模拟车载终端或网关。",
    TCP_SERVER: "模拟器在本机监听端口，等待平台主动连接，适合模拟 PLC 或厂商服务端。"
  };
  $("#transportHelp").textContent = helps[mode];
  $("#endpointHostLabel").textContent = preset.hostLabel;
  $("#endpointPortLabel").textContent = preset.portLabel;
  form.elements.host.placeholder = preset.hostPlaceholder;
  $("#endpointHostHelp").dataset.tooltip = preset.hostHelp;
  $("#endpointHostHelp").setAttribute("aria-label", `帮助：${preset.hostHelp}`);
  $("#endpointPortHelp").dataset.tooltip = preset.portHelp;
  $("#endpointPortHelp").setAttribute("aria-label", `帮助：${preset.portHelp}`);
  if (resetDefaults) {
    form.elements.host.value = preset.host;
    form.elements.port.value = String(preset.port);
    form.elements.payloadFormat.value = preset.payloadFormat;
    form.elements.payloadTemplate.value = templates[mode];
  }
}

async function saveProfile(event) {
  event.preventDefault();
  const data = new FormData(form);
  const profile = {
    id: data.get("id"), name: data.get("name"), transportMode: data.get("transportMode"),
    host: data.get("host"), port: Number(data.get("port")), tlsEnabled: data.get("tlsEnabled") === "on",
    productKey: data.get("productKey") || "", deviceCode: data.get("deviceCode") || "",
    clientId: data.get("clientId") || "", username: data.get("username") || "",
    publishTopic: data.get("publishTopic") || "", payloadFormat: data.get("payloadFormat"),
    payloadTemplate: data.get("payloadTemplate"), intervalMillis: Number(data.get("intervalMillis")),
    qos: Number(data.get("qos") || 0)
  };
  try {
    await api("/api/profiles", { method: "POST", body: JSON.stringify(profile) });
    profileDialog.close();
    state.selectedId = profile.id;
    toast("设备配置已保存");
    await loadAll({ quiet: true });
  } catch (error) { toast(error.message, true); }
}

async function runAction(id, action, body = null) {
  try {
    await api(`/api/profiles/${encodeURIComponent(id)}/actions/${action}`, {
      method: "POST", body: body ? JSON.stringify(body) : null
    });
    toast({ start: "设备任务已启动", stop: "设备任务已停止", send: "报文发送成功", disconnect: "连接已断开", reconnect: "设备已重新连接" }[action]);
    await loadAll({ quiet: true });
  } catch (error) { toast(error.message, true); }
}

async function deleteProfile(profile) {
  if (!confirm(`确定删除“${profile.name}”吗？其运行任务也会停止。`)) return;
  try {
    await api(`/api/profiles/${encodeURIComponent(profile.id)}`, { method: "DELETE" });
    toast("设备配置已删除");
    await loadAll({ quiet: true });
  } catch (error) { toast(error.message, true); }
}

$("#addDeviceButton").addEventListener("click", () => openProfile());
$("#refreshButton").addEventListener("click", async event => {
  event.currentTarget.classList.add("refreshing");
  await loadAll();
  event.currentTarget.classList.remove("refreshing");
  toast("状态已刷新");
});
$("#transportMode").addEventListener("change", () => updateTransportFields(true));
$("#logDeviceSelect").addEventListener("change", event => {
  state.selectedId = event.currentTarget.value || null;
  renderDevices();
  renderActivity();
});
form.addEventListener("submit", saveProfile);
$("#credentialForm").addEventListener("submit", async event => {
  event.preventDefault();
  const id = $("#credentialProfileId").value;
  const password = $("#devicePassword").value;
  credentialDialog.close();
  $("#devicePassword").value = "";
  await runAction(id, "start", { password });
});

document.addEventListener("click", event => {
  const closeButton = event.target.closest("[data-close]");
  if (closeButton) $("#" + closeButton.dataset.close).close();
  if (event.target.closest("[data-empty-add]")) openProfile();
  const filter = event.target.closest("[data-filter]");
  if (filter) {
    state.filter = filter.dataset.filter;
    $$("[data-filter]").forEach(item => item.classList.toggle("active", item === filter));
    renderDevices();
    if (!state.profiles.length) toast("暂无设备，请先添加一台模拟设备");
  }
  const metric = event.target.closest("[data-metric-action]");
  if (metric) handleMetricAction(metric.dataset.metricAction);
  const navigation = event.target.closest(".nav-item[data-view]");
  if (navigation) {
    event.preventDefault();
    navigateToView(navigation.dataset.view);
    return;
  }
  const card = event.target.closest("[data-profile-id]");
  if (!card) return;
  const profile = state.profiles.find(item => item.id === card.dataset.profileId);
  if (!profile) return;
  state.selectedId = profile.id;
  const actionButton = event.target.closest("[data-action]");
  if (!actionButton) { render(); return; }
  event.stopPropagation();
  const blockedReason = actionButton.dataset.blockedReason;
  if (blockedReason) {
    toast(blockedReason);
    return;
  }
  const action = actionButton.dataset.action;
  if (action === "edit") {
    openProfile(profile);
  } else if (action === "delete") {
    deleteProfile(profile);
  } else if (action === "start" && profile.transportMode === "MQTT") {
    $("#credentialProfileId").value = profile.id;
    credentialDialog.showModal();
    setTimeout(() => $("#devicePassword").focus(), 50);
  } else {
    runAction(profile.id, action, action === "start" ? { password: "" } : null);
  }
  render();
});

function handleMetricAction(action) {
  if (action === "ALL" || action === "CONNECTED") {
    navigateToView("devices");
    state.filter = action;
    $$('[data-filter]').forEach(item => item.classList.toggle("active", item.dataset.filter === action));
    renderDevices();
    $("#devices").scrollIntoView({ behavior: "smooth", block: "start" });
    if (action === "CONNECTED" && !state.profiles.some(profile => runtimeFor(profile).state === "CONNECTED")) {
      toast("当前没有已连接设备");
    }
    return;
  }
  if (action === "FAILED") {
    const failed = state.profiles.find(profile => runtimeFor(profile).failedCount > 0);
    if (failed) {
      state.selectedId = failed.id;
      render();
    } else {
      toast("当前没有发送失败记录");
    }
  }
  navigateToView("logs");
  $("#logs").scrollIntoView({ behavior: "smooth", block: "start" });
}

window.addEventListener("hashchange", () => setActiveView(viewFromHash()));

profileDialog.addEventListener("click", event => {
  if (event.target === profileDialog) profileDialog.close();
});
credentialDialog.addEventListener("click", event => {
  if (event.target === credentialDialog) credentialDialog.close();
});

let toastTimer;
function toast(message, error = false) {
  const element = $("#toast");
  element.textContent = message || "操作完成";
  element.classList.toggle("error", error);
  element.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => element.classList.remove("show"), 3000);
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>'"]/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);
}

function formatTime(value) {
  return new Intl.DateTimeFormat("zh-CN", { hour12: false, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value));
}
function formatNumber(value) { return new Intl.NumberFormat("zh-CN", { notation: value > 9999 ? "compact" : "standard" }).format(value); }
function formatInterval(value) { return value >= 1000 && value % 1000 === 0 ? `${value / 1000} 秒` : `${value} ms`; }

setActiveView(viewFromHash());
loadAll();
setInterval(() => loadAll({ quiet: true }), 2000);
