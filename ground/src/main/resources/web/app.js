'use strict';

// ---------------------------------------------------------------------------
// Static configuration

const S1_MODES = ['Pad', 'Ascent', 'Coast', 'Entry Burn', 'Descent', 'Landing Burn', 'Landed', 'Lost'];
const S2_MODES = ['Stacked', 'Burn', 'Orbit', 'Payload Deployed'];
const STATUS_TEXT = ['No Data', 'Nominal', 'Caution', 'Warning', 'Stale', 'Suspect'];
const MINUS = '−';
const DASH = '—';

const S1_READOUTS = [
  ['S1_ALTITUDE', 'Altitude'], ['S1_VELOCITY', 'Velocity'],
  ['S1_DOWNRANGE', 'Downrange'], ['S1_ACCEL', 'Acceleration'],
  ['S1_DYN_PRESSURE', 'Dynamic Pressure'], ['S1_MACH', 'Mach'],
  ['S1_THROTTLE', 'Throttle'], ['S1_PROPELLANT', 'Propellant'],
];
const S1_SYSTEMS = [
  ['S1_LOX_TANK_P', 'LOX Tank'], ['S1_RP1_TANK_P', 'RP-1 Tank'], ['S1_COPV_P', 'Helium Bottle'],
  ['S1_N2_P', 'Nitrogen'], ['S1_BUS_VOLTAGE', 'Avionics Bus'], ['S1_AVIONICS_TEMP', 'Flight Computer'],
  ['S1_GRID_FINS', 'Grid Fins'], ['S1_LEGS', 'Landing Legs'],
];
const S2_READOUTS = [
  ['S2_ALTITUDE', 'Altitude'], ['S2_VELOCITY', 'Velocity'],
  ['S2_APOGEE', 'Apogee'], ['S2_PERIGEE', 'Perigee'],
  ['S2_ACCEL', 'Acceleration'], ['S2_THROTTLE', 'Throttle'],
  ['S2_ENG_PC', 'Chamber Pressure'], ['S2_NOZZLE_TEMP', 'Nozzle'],
];
const S2_SYSTEMS = [
  ['S2_PROPELLANT', 'Propellant'], ['S2_LOX_TANK_P', 'LOX Tank'], ['S2_RP1_TANK_P', 'RP-1 Tank'],
  ['S2_COPV_P', 'Helium Bottle'], ['S2_BUS_VOLTAGE', 'Avionics Bus'], ['S2_AVIONICS_TEMP', 'Flight Computer'],
  ['S2_FAIRING', 'Fairing'],
];
const DISCRETE = {
  S1_MODE: (v) => S1_MODES[v] ?? String(v),
  S2_MODE: (v) => S2_MODES[v] ?? String(v),
  S1_GRID_FINS: (v) => (v ? 'Deployed' : 'Stowed'),
  S1_LEGS: (v) => (v ? 'Deployed' : 'Stowed'),
  S2_FAIRING: (v) => (v ? 'Attached' : 'Separated'),
};
const CHART_MARKERS = {
  MAX_Q: 'Max-Q', MECO: 'MECO', SECO: 'SECO', TOUCHDOWN: 'Landing', BOOSTER_LOST: 'Booster Lost',
  ENGINE_SHUTDOWN: 'Engine Out',
};
const BAD_EVENTS = new Set(['BOOSTER_LOST', 'ENGINE_SHUTDOWN']);

const app = {
  dict: null,
  byName: new Map(),
  plot: [],
  flight: -1,
  snap: null,
  hist: { met: [], cols: {} },
  resetOnNext: true,
  connected: false,
  inspector: null,
  alarmSignature: '',
  eventSignature: '',
  frame: 0,
  colors: {},
  ui: {},
};

// ---------------------------------------------------------------------------
// Formatting

function channel(name) {
  return app.byName.get(name);
}

function decimals(units, value) {
  const a = Math.abs(value);
  switch (units) {
    case 'km': return a < 10 ? 2 : 1;
    case 'm/s': return a < 100 ? 1 : 0;
    case 'g': return 2;
    case 'kPa': return a < 10 ? 2 : 1;
    case 'bar': return a < 10 ? 2 : a < 100 ? 1 : 0;
    case '%': return a < 10 ? 1 : 0;
    case 'degC': return a < 100 ? 1 : 0;
    case 'V': return 2;
    case 'deg': return 1;
    default: return a < 10 ? 2 : 1;
  }
}

function formatValue(ch, value) {
  if (value === null || value === undefined || Number.isNaN(value)) return DASH;
  if (DISCRETE[ch.name]) return DISCRETE[ch.name](value);
  if (/_MAIN$/.test(ch.name)) return value ? 'Mainstage' : 'Off';
  if (ch.name === 'S2_PERIGEE' && value < 0) return 'Suborbital';
  if (ch.type !== 'f32' && ch.type !== 'f64') return String(Math.round(value));
  return fixed(value, decimals(ch.units, value));
}

/** toFixed without the minus sign on a value that rounds to zero. */
function fixed(value, digits) {
  const text = value.toFixed(digits);
  return /^-0(\.0+)?$/.test(text) ? text.slice(1) : text;
}

function unitLabel(units) {
  if (units === 'degC') return '°C';
  return units || '';
}

function pad(n, width = 2) {
  return String(n).padStart(width, '0');
}

function formatMet(met) {
  if (met === null || met === undefined || Number.isNaN(met)) return `T ${DASH}`;
  const sign = met < 0 ? MINUS : '+';
  const a = Math.abs(met);
  const minutes = Math.floor(a / 60);
  const seconds = a - minutes * 60;
  return `T${sign}${pad(minutes)}:${seconds.toFixed(1).padStart(4, '0')}`;
}

function formatAxisTime(t) {
  const sign = t < 0 ? MINUS : '';
  const a = Math.abs(Math.round(t));
  return `${sign}${Math.floor(a / 60)}:${pad(a % 60)}`;
}

function formatMicros(us) {
  if (us === null || us === undefined) return DASH;
  return us < 1000 ? `${us.toFixed(0)} µs` : `${(us / 1000).toFixed(1)} ms`;
}

function formatCount(n) {
  return Number(n).toLocaleString('en-US');
}

function eventDetail(e) {
  const a = e.arg;
  switch (e.name) {
    case 'IGNITION':
    case 'ENTRY_BURN_START': return `${a.toFixed(0)} engines`;
    case 'THROTTLE_DOWN':
    case 'THROTTLE_UP': return `${a.toFixed(0)}%`;
    case 'MAX_Q': return `${a.toFixed(1)} kPa`;
    case 'MECO': return `${(a / 1000).toFixed(1)} t left`;
    case 'FAIRING_SEPARATION': return `${a.toFixed(0)} km`;
    case 'ENTRY_BURN_END': return `${a.toFixed(0)} m/s`;
    case 'LANDING_BURN_START': return `${(a / 1000).toFixed(1)} km`;
    case 'LANDING_LEGS': return `${a.toFixed(0)} m`;
    case 'TOUCHDOWN':
    case 'BOOSTER_LOST': return `${a.toFixed(1)} m/s`;
    case 'SECO':
    case 'ORBIT': return `perigee ${a.toFixed(0)} km`;
    case 'PAYLOAD_SEPARATION': return `${(a / 1000).toFixed(1)} t`;
    case 'ENGINE_SHUTDOWN': return `engine ${a.toFixed(0)}`;
    default: return '';
  }
}

function escapeHtml(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

// ---------------------------------------------------------------------------
// Building the page

function buildReadouts(container, list) {
  return list.map(([name, label]) => {
    const ch = channel(name);
    const el = document.createElement('div');
    el.className = 'readout';
    el.dataset.status = '0';
    el.innerHTML = `<div class="readout-label">${label}</div>`
      + `<div class="readout-value"><span class="v">${DASH}</span>`
      + `<span class="readout-unit">${unitLabel(ch.units)}</span></div>`;
    container.append(el);
    return { ch, el, value: el.querySelector('.v'), unit: el.querySelector('.readout-unit') };
  });
}

function buildSystems(container, list) {
  return list.map(([name, label]) => {
    const ch = channel(name);
    const dt = document.createElement('dt');
    dt.textContent = label;
    const dd = document.createElement('dd');
    dd.className = 'value';
    dd.dataset.status = '0';
    dd.textContent = DASH;
    container.append(dt, dd);
    return { ch, el: dd, value: dd, unit: unitLabel(ch.units) };
  });
}

function buildEngines() {
  const svg = document.getElementById('octaweb');
  const ns = 'http://www.w3.org/2000/svg';
  const positions = [[66, 66]];
  for (let k = 0; k < 8; k++) {
    const angle = -Math.PI / 2 + (k * Math.PI) / 4;
    positions.push([66 + 44 * Math.cos(angle), 66 + 44 * Math.sin(angle)]);
  }
  return positions.map(([x, y], i) => {
    const g = document.createElementNS(ns, 'g');
    g.setAttribute('class', 'engine');
    const circle = document.createElementNS(ns, 'circle');
    circle.setAttribute('cx', x);
    circle.setAttribute('cy', y);
    circle.setAttribute('r', 15);
    const text = document.createElementNS(ns, 'text');
    text.setAttribute('x', x);
    text.setAttribute('y', y - 1.5);
    const number = document.createElementNS(ns, 'text');
    number.setAttribute('class', 'number');
    number.setAttribute('x', x);
    number.setAttribute('y', y + 7);
    number.textContent = `E${i + 1}`;
    const title = document.createElementNS(ns, 'title');
    g.append(circle, text, number, title);
    svg.append(g);
    return {
      n: i + 1, circle, text, title,
      main: channel(`S1_ENG${i + 1}_MAIN`), pc: channel(`S1_ENG${i + 1}_PC`),
    };
  });
}

function buildChannelTable() {
  const body = document.getElementById('channels');
  const rows = [];
  const groups = [];
  for (const packet of app.dict.packets) {
    const group = document.createElement('tr');
    group.className = 'group';
    const rate = packet.rate > 0 ? `${packet.rate} Hz` : 'on event';
    group.innerHTML = `<td colspan="8">${escapeHtml(packet.name)} · APID 0x${packet.apid.toString(16).toUpperCase().padStart(3, '0')} · ${rate} · ${escapeHtml(packet.description)}</td>`;
    body.append(group);
    const members = [];
    for (const ch of app.dict.channels.filter((c) => c.packet === packet.name)) {
      const tr = document.createElement('tr');
      tr.className = 'channel';
      tr.tabIndex = 0;
      tr.dataset.name = ch.name;
      const [rl, yl, yh, rh] = ch.limits;
      const range = (lo, hi) => {
        if (lo === null && hi === null) return '';
        if (lo === null) return `< ${hi}`;
        if (hi === null) return `> ${lo}`;
        return `${lo} to ${hi}`;
      };
      tr.innerHTML = `<td class="mono">${ch.name}</td><td class="num value" data-status="0">${DASH}</td>`
        + `<td>${unitLabel(ch.units)}</td><td><span class="status-chip" data-status="0">No Data</span></td>`
        + `<td class="num">${range(yl, yh)}</td><td class="num">${range(rl, rh)}</td>`
        + `<td class="when">${escapeHtml(ch.when ?? '')}</td><td class="description">${escapeHtml(ch.description)}</td>`;
      body.append(tr);
      const entry = { ch, tr, value: tr.children[1], status: tr.children[3].firstChild, text: `${ch.name} ${ch.description}`.toLowerCase() };
      rows.push(entry);
      members.push(entry);
    }
    groups.push({ group, members });
  }
  body.addEventListener('click', (e) => {
    const tr = e.target.closest('tr.channel');
    if (tr) openInspector(tr.dataset.name);
  });
  body.addEventListener('keydown', (e) => {
    const tr = e.target.closest('tr.channel');
    if (tr && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      openInspector(tr.dataset.name);
    }
  });
  document.getElementById('filter').addEventListener('input', (e) => {
    const q = e.target.value.trim().toLowerCase();
    for (const { group, members } of groups) {
      let shown = 0;
      for (const m of members) {
        const match = !q || m.text.includes(q);
        m.tr.hidden = !match;
        if (match) shown++;
      }
      group.hidden = shown === 0;
    }
  });
  return rows;
}

function buildLink() {
  const stats = [
    ['rate', 'Packets/s'], ['byteRate', 'Data Rate'], ['received', 'Received'], ['lost', 'Lost'],
    ['crcErrors', 'CRC Errors'], ['malformed', 'Malformed'], ['duplicates', 'Duplicates'], ['late', 'Late'],
    ['queue', 'Queue Depth'], ['dropped', 'Dropped'], ['latencyP50', 'Latency p50'], ['latencyP99', 'Latency p99'],
  ];
  const dl = document.getElementById('link-stats');
  const cells = {};
  for (const [key, label] of stats) {
    const div = document.createElement('div');
    div.innerHTML = `<dt>${label}</dt><dd>${DASH}</dd>`;
    dl.append(div);
    cells[key] = div.querySelector('dd');
  }
  const body = document.getElementById('packets');
  const packets = app.dict.packets.map((p) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td class="mono">0x${p.apid.toString(16).toUpperCase().padStart(3, '0')}</td><td class="mono">${p.name}</td>`
      + `<td class="num">${p.rate > 0 ? `${p.rate} Hz` : 'event'}</td><td class="num">0</td><td class="num">0</td>`
      + `<td class="num">${DASH}</td><td><span class="status-chip" data-status="0">No Data</span></td>`;
    body.append(tr);
    return { apid: p.apid, cells: tr.children };
  });
  return { cells, packets };
}

function buildLaunch() {
  const scenarios = app.dict.scenarios;
  if (!scenarios) return;
  const form = document.getElementById('launch');
  const select = document.getElementById('scenario');
  const note = document.getElementById('scenario-note');
  for (const s of scenarios) {
    const option = document.createElement('option');
    option.value = s.id;
    option.textContent = s.label;
    select.append(option);
  }
  const describe = () => {
    const chosen = scenarios.find((s) => s.id === select.value);
    note.textContent = chosen ? chosen.description : '';
    note.hidden = !chosen;
  };
  const speed = document.getElementById('speed');
  const picked = () => {
    app.scenarioPicked = true;
    describe();
  };
  select.addEventListener('change', picked);
  speed.addEventListener('change', picked);
  // Until someone changes the pickers, they follow the flight on screen.
  app.followScenario = (label) => {
    if (app.scenarioPicked || !label) return;
    const running = scenarios.find((s) => label === s.label || label.startsWith(`${s.label} at `));
    if (!running) return;
    if (select.value !== running.id) {
      select.value = running.id;
      describe();
    }
    const warp = label.slice(running.label.length).match(/^ at (\d+)x$/);
    const value = warp ? warp[1] : '1';
    if ([...speed.options].some((o) => o.value === value)) speed.value = value;
  };
  describe();
  form.hidden = false;
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const button = document.getElementById('launch-button');
    button.disabled = true;
    button.textContent = 'Launching';
    try {
      const response = await fetch(`/api/flights?scenario=${encodeURIComponent(select.value)}&speed=${encodeURIComponent(speed.value)}`, { method: 'POST' });
      if (!response.ok) note.textContent = await response.text();
    } finally {
      button.disabled = false;
      button.textContent = 'Launch';
    }
  });
}

// ---------------------------------------------------------------------------
// Live data

function connect() {
  const source = new EventSource('/api/stream');
  source.onopen = () => {
    app.connected = true;
    app.resetOnNext = true;
  };
  source.onerror = () => {
    app.connected = false;
    schedule();
  };
  source.onmessage = (e) => onSnapshot(JSON.parse(e.data));
}

function clearHistory() {
  app.hist = { met: [], cols: {} };
  for (const name of app.plot) app.hist.cols[name] = [];
}

function onSnapshot(snap) {
  const newFlight = snap.flight !== app.flight;
  if (app.resetOnNext || newFlight) {
    clearHistory();
    app.resetOnNext = false;
    app.flight = snap.flight;
    if (newFlight && app.inspector) openInspector(app.inspector.name);
  }
  for (const row of snap.history) {
    app.hist.met.push(row[0]);
    app.plot.forEach((name, j) => app.hist.cols[name].push(row[j + 1]));
  }
  if (app.inspector && app.inspector.loaded && snap.met !== null) {
    const ins = app.inspector;
    const last = ins.met.length ? ins.met[ins.met.length - 1] : -Infinity;
    if (snap.met > last + 0.05) {
      ins.met.push(snap.met);
      ins.values.push(snap.values[ins.ch.index]);
    }
  }
  app.snap = snap;
  schedule();
}

function schedule() {
  if (app.frame) return;
  app.frame = requestAnimationFrame(() => {
    app.frame = 0;
    render();
  });
}

// ---------------------------------------------------------------------------
// Rendering

function render() {
  const s = app.snap;
  renderHeader(s);
  if (!s) return;
  for (const r of app.ui.s1Readouts) updateReadout(r, s);
  for (const r of app.ui.s2Readouts) updateReadout(r, s);
  for (const r of app.ui.s1Systems) updateSystem(r, s);
  for (const r of app.ui.s2Systems) updateSystem(r, s);
  renderModes(s);
  renderEngines(s);
  renderAlarms(s);
  renderEvents(s);
  renderLink(s);
  renderChannels(s);
  drawCharts(s);
}

function valueOf(s, name) {
  return s.values[channel(name).index];
}

function updateReadout(r, s) {
  const i = r.ch.index;
  const text = formatValue(r.ch, s.values[i]);
  r.value.textContent = text;
  r.unit.hidden = !/^-?[\d.]+$/.test(text);
  r.el.dataset.status = s.status[i];
}

function updateSystem(r, s) {
  const i = r.ch.index;
  const text = formatValue(r.ch, s.values[i]);
  const numeric = !DISCRETE[r.ch.name] && text !== DASH;
  r.el.textContent = numeric && r.unit ? `${text} ${r.unit}` : text;
  r.el.dataset.status = s.status[i];
}

function phaseText(s) {
  if (!app.connected) return 'Reconnecting to the Ground Station';
  switch (s.state) {
    case 'IDLE': return 'Waiting for Telemetry';
    case 'COMPLETE': return 'Mission Complete';
    case 'LOS': return 'Loss of Signal';
    default: break;
  }
  const s1 = valueOf(s, 'S1_MODE');
  const s2 = valueOf(s, 'S2_MODE');
  if (s2 === 1) return 'Second Stage Burn';
  if (s2 === 2) return 'Orbit Coast';
  if (s2 === 3) return 'Payload Deployed';
  if (s1 === 0 || s1 === null) return s.met !== null && s.met < 0 ? 'Terminal Count' : 'On the Pad';
  if (s1 === 1) return 'First Stage Ascent';
  return 'Staging';
}

function renderHeader(s) {
  const pill = document.getElementById('state-pill');
  const state = !app.connected ? 'OFFLINE' : s ? s.state : 'IDLE';
  pill.dataset.state = state;
  pill.textContent = { LIVE: 'Live', LOS: 'LOS', COMPLETE: 'Complete', IDLE: 'Idle', OFFLINE: 'Offline' }[state];
  document.getElementById('phase').textContent = s ? phaseText(s) : app.connected ? 'Waiting for Telemetry' : 'Reconnecting to the Ground Station';
  if (!s) return;

  const clock = document.getElementById('met');
  if (s.met === null) {
    clock.textContent = `T${MINUS}--:--:--`;
  } else {
    const a = Math.abs(s.met);
    const sign = s.met < 0 ? MINUS : '+';
    const h = Math.floor(a / 3600);
    const m = Math.floor((a % 3600) / 60);
    const sec = Math.floor(a % 60);
    const tenths = Math.floor((a * 10) % 10);
    clock.innerHTML = `T${sign}${pad(h)}:${pad(m)}:${pad(sec)}<span class="tenths">.${tenths}</span>`;
  }
  document.getElementById('flight-label').textContent = `Flight ${s.flight}${s.label ? ` · ${s.label}` : ''}`;
  if (app.followScenario) app.followScenario(s.label);

  const active = s.alarms.filter((a) => a.state === 'ACTIVE');
  const warnings = active.filter((a) => a.severity === 'WARNING').length;
  const cautions = active.filter((a) => a.severity === 'CAUTION').length;
  document.getElementById('warning-count').textContent = warnings;
  document.getElementById('caution-count').textContent = cautions;
  document.getElementById('warning-stat').dataset.active = warnings > 0;
  document.getElementById('caution-stat').dataset.active = cautions > 0;
  document.getElementById('top-rate').textContent = Math.round(s.link.rate);
  document.getElementById('top-loss').textContent = `${s.link.lossPercent.toFixed(2)}%`;
}

function renderModes(s) {
  const s1 = valueOf(s, 'S1_MODE');
  const s2 = valueOf(s, 'S2_MODE');
  const chip1 = document.getElementById('s1-mode');
  chip1.textContent = s1 === null ? DASH : S1_MODES[s1] ?? DASH;
  chip1.dataset.tone = s1 === 7 ? 'bad' : [1, 3, 5].includes(s1) ? 'active' : '';
  const chip2 = document.getElementById('s2-mode');
  chip2.textContent = s2 === null ? DASH : S2_MODES[s2] ?? DASH;
  chip2.dataset.tone = s2 === 1 ? 'active' : '';
}

function renderEngines(s) {
  const failed = new Set();
  const degraded = new Set();
  for (const a of s.alarms) {
    if (a.state !== 'ACTIVE') continue;
    const match = /^ENGINE:(\d)(:DEGRADED)?$/.exec(a.key);
    if (match) (match[2] ? degraded : failed).add(Number(match[1]));
  }
  const c = app.colors;
  for (const e of app.ui.engines) {
    const main = s.values[e.main.index] === 1;
    const pc = s.values[e.pc.index];
    const limit = s.status[e.pc.index];
    let fill = 'transparent';
    let stroke = c.lineStrong;
    let textColor = c.textFaint;
    let state = 'Off';
    if (failed.has(e.n) || limit === 3) {
      fill = withAlpha(c.warning, 0.32);
      stroke = c.warning;
      textColor = c.text;
      state = 'Failed';
    } else if (degraded.has(e.n)) {
      fill = withAlpha(c.caution, 0.3);
      stroke = c.caution;
      textColor = c.text;
      state = 'Degraded';
    } else if (main) {
      const level = Math.max(0, Math.min(1, (pc ?? 0) / 97));
      fill = withAlpha(c.ok, 0.12 + 0.45 * level);
      stroke = c.ok;
      textColor = c.text;
      state = 'Mainstage';
    } else if (pc !== null && pc > 5) {
      fill = withAlpha(c.ok, 0.1);
      stroke = c.ok;
      textColor = c.textDim;
      state = 'Spooling';
    }
    e.circle.setAttribute('fill', fill);
    e.circle.setAttribute('stroke', stroke);
    e.text.setAttribute('fill', textColor);
    e.text.textContent = pc !== null && Math.abs(pc) >= 2 ? pc.toFixed(0) : '';
    e.title.textContent = `Engine ${e.n}: ${state}${pc !== null ? `, ${pc.toFixed(1)} bar` : ''}`;
  }
  const running = valueOf(s, 'S1_ENGINES');
  document.getElementById('engine-count').textContent = `${running ?? 0} of 9`;
}

function renderAlarms(s) {
  const signature = s.alarms.map((a) => `${a.id}:${a.state}:${a.acknowledged}:${a.severity}:${a.message}`).join('|');
  const list = document.getElementById('alarms');
  document.getElementById('ack-all').disabled = !s.alarms.some((a) => !a.acknowledged);
  if (signature === app.alarmSignature) return;
  app.alarmSignature = signature;
  if (s.alarms.length === 0) {
    list.innerHTML = '<li class="empty">No alarms</li>';
    return;
  }
  list.innerHTML = s.alarms.map((a) => {
    const tags = [];
    if (a.state === 'RETURNED') tags.push('<span class="alarm-tag">Cleared</span>');
    if (a.acknowledged) tags.push('<span class="alarm-tag">Acknowledged</span>');
    const ack = a.acknowledged ? '' : `<button class="button small" type="button" data-id="${a.id}">Acknowledge</button>`;
    return `<li class="alarm" data-severity="${a.severity}" data-state="${a.state}" data-acknowledged="${a.acknowledged}">`
      + `<div class="alarm-top"><span class="severity">${a.severity === 'WARNING' ? 'Warning' : 'Caution'}</span>`
      + `<span class="alarm-time">${formatMet(a.met)}</span>${tags.join('')}${ack}</div>`
      + `<div class="alarm-message">${escapeHtml(a.message)}</div></li>`;
  }).join('');
}

function renderEvents(s) {
  const last = s.events[s.events.length - 1];
  const signature = `${s.flight}:${s.events.length}:${last ? last.met : ''}`;
  if (signature === app.eventSignature) return;
  app.eventSignature = signature;
  const list = document.getElementById('events');
  document.getElementById('event-count').textContent = s.events.length ? `${s.events.length}` : '';
  if (s.events.length === 0) {
    list.innerHTML = '<li class="empty">No events yet</li>';
    return;
  }
  list.innerHTML = s.events.map((e) => (
    `<li class="event" data-kind="${BAD_EVENTS.has(e.name) ? 'bad' : ''}">`
    + `<span class="event-time">${formatMet(e.met)}</span>`
    + `<span class="event-label">${escapeHtml(e.label)}${e.ground ? '<span class="event-ground">Ground</span>' : ''}</span>`
    + `<span class="event-detail">${escapeHtml(eventDetail(e))}</span></li>`
  )).join('');
  list.scrollTop = list.scrollHeight;
}

function renderLink(s) {
  const l = s.link;
  const cells = app.ui.link.cells;
  cells.rate.textContent = Math.round(l.rate);
  cells.byteRate.textContent = `${(l.byteRate / 1024).toFixed(1)} kB/s`;
  cells.received.textContent = formatCount(l.received);
  cells.lost.textContent = `${formatCount(l.lost)} (${l.lossPercent.toFixed(2)}%)`;
  cells.crcErrors.textContent = formatCount(l.crcErrors);
  cells.malformed.textContent = formatCount(l.malformed);
  cells.duplicates.textContent = formatCount(l.duplicates);
  cells.late.textContent = formatCount(l.late);
  cells.queue.textContent = formatCount(l.queue);
  cells.dropped.textContent = formatCount(l.dropped);
  cells.latencyP50.textContent = formatMicros(l.latencyP50);
  cells.latencyP99.textContent = formatMicros(l.latencyP99);
  cells.lost.dataset.bad = l.lost > 0;
  cells.crcErrors.dataset.bad = l.crcErrors > 0;
  cells.dropped.dataset.bad = l.dropped > 0;
  document.getElementById('link-state').textContent = `${formatCount(l.frames)} frames`;

  const byApid = new Map(l.packets.map((p) => [p.apid, p]));
  for (const row of app.ui.link.packets) {
    const p = byApid.get(row.apid);
    if (!p) continue;
    row.cells[3].textContent = formatCount(p.received);
    row.cells[4].textContent = formatCount(p.lost);
    row.cells[5].textContent = p.age === null ? DASH : p.age < 10 ? `${(p.age * 1000).toFixed(0)} ms` : `${p.age.toFixed(0)} s`;
    const status = p.received === 0 ? 0 : p.rate > 0 && p.stale && s.state !== 'COMPLETE' ? 4 : 1;
    const chip = row.cells[6].firstChild;
    chip.dataset.status = status;
    chip.textContent = p.rate === 0 && p.received > 0 ? 'Receiving' : STATUS_TEXT[status];
  }
}

function renderChannels(s) {
  for (const row of app.ui.channels) {
    if (row.tr.hidden) continue;
    const i = row.ch.index;
    const status = s.status[i];
    row.value.textContent = formatValue(row.ch, s.values[i]);
    row.value.dataset.status = status;
    if (row.status.dataset.status !== String(status)) {
      row.status.dataset.status = status;
      row.status.textContent = STATUS_TEXT[status];
    }
  }
}

// ---------------------------------------------------------------------------
// Charts

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function withAlpha(hex, alpha) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

function setupCanvas(canvas) {
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  const width = Math.max(1, Math.round(rect.width * dpr));
  const height = Math.max(1, Math.round(rect.height * dpr));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return { ctx, width: rect.width, height: rect.height };
}

function niceStep(span, count) {
  const raw = span / Math.max(1, count);
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const n = raw / magnitude;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * magnitude;
}

function timeStep(span, count) {
  const raw = span / Math.max(1, count);
  return [1, 2, 5, 10, 15, 30, 60, 120, 180, 300, 600, 900, 1800].find((s) => s >= raw) ?? 3600;
}

function ticksFor(min, max, step) {
  const out = [];
  for (let v = Math.ceil(min / step - 1e-9) * step; v <= max + step * 1e-9; v += step) out.push(Number(v.toFixed(10)));
  return out;
}

function extent(arrays, floor) {
  let min = Infinity;
  let max = -Infinity;
  for (const arr of arrays) {
    for (const v of arr) {
      if (v === null || Number.isNaN(v)) continue;
      if (v < min) min = v;
      if (v > max) max = v;
    }
  }
  if (min === Infinity) return null;
  if (floor !== undefined) {
    // Sensor noise dips a hair below a physical floor such as zero altitude; do not let
    // that stretch the axis.
    min = min < floor && floor - min < 0.03 * Math.max(max - floor, 1e-9) ? floor : Math.min(min, floor);
  }
  if (max - min < 1e-9) {
    max += 1;
    min -= floor === undefined ? 1 : 0;
  }
  return [min, max];
}

/**
 * Draws one chart. series: [{x, y, color}] with matching arrays; null breaks a line.
 * options: xMin/xMax/yFloor, time (format x as m:ss), markers [{x, label, color}],
 * lines [{y, color}], points [{x, y, color, label}].
 */
function drawPlot(canvas, series, options = {}) {
  const { ctx, width, height } = setupCanvas(canvas);
  const c = app.colors;
  ctx.clearRect(0, 0, width, height);
  const padding = { left: 44, right: 10, top: 12, bottom: 20 };
  const plotW = width - padding.left - padding.right;
  const plotH = height - padding.top - padding.bottom;

  const xr = options.xMin !== undefined ? [options.xMin, options.xMax] : extent(series.map((s) => s.x), options.xFloor);
  const yValues = series.map((s) => s.y);
  if (options.lines) yValues.push(options.lines.map((l) => l.y));
  const yr = extent(yValues, options.yFloor);
  if (!xr || !yr || series.every((s) => s.y.length === 0)) {
    ctx.fillStyle = c.textFaint;
    ctx.font = `12px ${cssVar('--sans')}`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(options.empty ?? 'Waiting for Telemetry', width / 2, height / 2);
    return;
  }

  let [yMin, yMax] = yr;
  const yStep = niceStep(yMax - yMin, Math.max(2, Math.floor(plotH / 34)));
  yMin = Math.floor(yMin / yStep) * yStep;
  yMax = Math.ceil(yMax / yStep) * yStep;
  const [xMin, xMax] = xr;
  const xStep = options.time ? timeStep(xMax - xMin, Math.max(2, Math.floor(plotW / 70))) : niceStep(xMax - xMin, Math.max(2, Math.floor(plotW / 70)));
  const px = (x) => padding.left + ((x - xMin) / (xMax - xMin)) * plotW;
  const py = (y) => padding.top + (1 - (y - yMin) / (yMax - yMin)) * plotH;

  ctx.font = `10px ${cssVar('--mono')}`;
  ctx.lineWidth = 1;
  ctx.strokeStyle = c.line;
  ctx.fillStyle = c.textFaint;
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  const yDecimals = yStep >= 1 ? 0 : Math.min(3, Math.ceil(-Math.log10(yStep)));
  for (const t of ticksFor(yMin, yMax, yStep)) {
    const y = Math.round(py(t)) + 0.5;
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(width - padding.right, y);
    ctx.stroke();
    ctx.fillText(t.toFixed(yDecimals), padding.left - 6, y);
  }
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (const t of ticksFor(xMin, xMax, xStep)) {
    const x = Math.round(px(t)) + 0.5;
    ctx.beginPath();
    ctx.moveTo(x, padding.top);
    ctx.lineTo(x, padding.top + plotH);
    ctx.stroke();
    ctx.fillText(options.time ? formatAxisTime(t) : String(Math.round(t * 100) / 100), x, padding.top + plotH + 5);
  }

  ctx.save();
  ctx.beginPath();
  ctx.rect(padding.left, padding.top - 2, plotW, plotH + 4);
  ctx.clip();

  for (const m of options.markers ?? []) {
    if (m.x < xMin || m.x > xMax) continue;
    const x = Math.round(px(m.x)) + 0.5;
    ctx.strokeStyle = m.color ?? c.lineStrong;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(x, padding.top);
    ctx.lineTo(x, padding.top + plotH);
    ctx.stroke();
    ctx.setLineDash([]);
  }
  for (const l of options.lines ?? []) {
    const y = Math.round(py(l.y)) + 0.5;
    ctx.strokeStyle = l.color;
    ctx.setLineDash([5, 4]);
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(width - padding.right, y);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  for (const s of series) {
    const n = s.y.length;
    if (n === 0) continue;
    const stride = Math.max(1, Math.floor(n / (plotW * 1.5)));
    ctx.strokeStyle = s.color;
    ctx.lineWidth = 1.6;
    ctx.lineJoin = 'round';
    ctx.beginPath();
    let pen = false;
    let lastX = null;
    let lastY = null;
    for (let i = 0; i < n; i += stride) {
      const xv = s.x[i];
      const yv = s.y[i];
      if (xv === null || yv === null || Number.isNaN(xv) || Number.isNaN(yv)) {
        pen = false;
        continue;
      }
      const X = px(xv);
      const Y = py(yv);
      if (pen) ctx.lineTo(X, Y); else ctx.moveTo(X, Y);
      pen = true;
      lastX = X;
      lastY = Y;
    }
    const xEnd = s.x[n - 1];
    const yEnd = s.y[n - 1];
    if (xEnd !== null && yEnd !== null && !Number.isNaN(xEnd) && !Number.isNaN(yEnd)) {
      lastX = px(xEnd);
      lastY = py(yEnd);
      if (pen) ctx.lineTo(lastX, lastY);
    }
    ctx.stroke();
    if (lastX !== null && s.head !== false) {
      ctx.fillStyle = s.color;
      ctx.beginPath();
      ctx.arc(lastX, lastY, 2.6, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  for (const p of options.points ?? []) {
    const X = px(p.x);
    const Y = py(p.y);
    ctx.fillStyle = p.color;
    ctx.strokeStyle = c.bg;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(X, Y, 4, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fill();
  }
  ctx.restore();

  ctx.font = `10px ${cssVar('--sans')}`;
  ctx.textBaseline = 'top';
  ctx.textAlign = 'left';
  // Labels that would collide drop to the next row; past three rows they are left off,
  // and the dashed line alone marks the event.
  const rowEnds = [-Infinity, -Infinity, -Infinity];
  for (const m of [...(options.markers ?? [])].sort((a, b) => a.x - b.x)) {
    if (m.x < xMin || m.x > xMax) continue;
    const x = Math.round(px(m.x)) + 4;
    const w = ctx.measureText(m.label).width;
    const left = Math.min(x, width - padding.right - w);
    const row = rowEnds.findIndex((end) => left > end + 4);
    if (row < 0) continue;
    rowEnds[row] = left + w;
    ctx.fillStyle = m.textColor ?? c.textDim;
    ctx.fillText(m.label, left, padding.top + row * 12);
  }
  for (const p of options.points ?? []) {
    ctx.fillStyle = c.textDim;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'bottom';
    ctx.fillText(p.label, px(p.x) + 7, py(p.y) - 3);
  }
  ctx.fillStyle = c.textFaint;
  if (options.xLabel) {
    ctx.textAlign = 'right';
    ctx.textBaseline = 'bottom';
    ctx.fillText(options.xLabel, width - padding.right - 2, padding.top + plotH - 3);
  }
  if (options.yLabel) {
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    ctx.fillText(options.yLabel, padding.left + 4, padding.top + 2);
  }
}

function timeMarkers(s) {
  const c = app.colors;
  return s.events
    .filter((e) => CHART_MARKERS[e.name] && e.met !== null)
    .map((e) => ({
      x: e.met,
      label: CHART_MARKERS[e.name],
      color: BAD_EVENTS.has(e.name) ? withAlpha(c.warning, 0.6) : c.lineStrong,
      textColor: BAD_EVENTS.has(e.name) ? c.warning : c.textDim,
    }));
}

function indexAt(met) {
  const arr = app.hist.met;
  let lo = 0;
  let hi = arr.length - 1;
  if (hi < 0) return -1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (arr[mid] < met) lo = mid + 1; else hi = mid;
  }
  return lo;
}

function drawCharts(s) {
  const h = app.hist;
  const col = (name) => h.cols[name] ?? [];
  const c = app.colors;
  const met = h.met;
  const xMin = met.length ? Math.max(met[0], -10) : -10;
  const xMax = met.length ? Math.max(met[met.length - 1], xMin + 60) : 60;
  const markers = timeMarkers(s);
  const time = { time: true, xMin, xMax, markers };

  const points = [];
  const at = (eventName, stage, label, color) => {
    const e = s.events.find((x) => x.name === eventName);
    if (!e) return;
    const i = indexAt(e.met);
    if (i < 0) return;
    const x = col(`${stage}_DOWNRANGE`)[i];
    const y = col(`${stage}_ALTITUDE`)[i];
    if (x !== null && y !== null && x !== undefined) points.push({ x, y, color, label });
  };
  at('MECO', 'S1', 'MECO', c.s1);
  at('TOUCHDOWN', 'S1', 'Landing', c.s1);
  at('BOOSTER_LOST', 'S1', 'Booster Lost', c.warning);
  at('SECO', 'S2', 'SECO', c.s2);

  drawPlot(document.getElementById('chart-trajectory'), [
    { x: col('S2_DOWNRANGE'), y: col('S2_ALTITUDE'), color: c.s2 },
    { x: col('S1_DOWNRANGE'), y: col('S1_ALTITUDE'), color: c.s1 },
  ], { yFloor: 0, xFloor: 0, points, xLabel: 'Downrange km', yLabel: 'Altitude km' });
  drawPlot(document.getElementById('chart-velocity'), [
    { x: met, y: col('S2_VELOCITY'), color: c.s2 },
    { x: met, y: col('S1_VELOCITY'), color: c.s1 },
  ], { ...time, yFloor: 0 });
  drawPlot(document.getElementById('chart-altitude'), [
    { x: met, y: col('S2_ALTITUDE'), color: c.s2 },
    { x: met, y: col('S1_ALTITUDE'), color: c.s1 },
  ], { ...time, yFloor: 0 });
  drawPlot(document.getElementById('chart-q'), [
    { x: met, y: col('S1_DYN_PRESSURE'), color: c.s1 },
  ], { ...time, yFloor: 0 });
  drawPlot(document.getElementById('chart-accel'), [
    { x: met, y: col('S2_ACCEL'), color: c.s2 },
    { x: met, y: col('S1_ACCEL'), color: c.s1 },
  ], { ...time, yFloor: 0 });

  if (app.inspector && app.inspector.loaded) {
    const ins = app.inspector;
    const [rl, yl, yh, rh] = ins.ch.limits;
    const lines = [];
    for (const v of [yl, yh]) if (v !== null) lines.push({ y: v, color: withAlpha(c.caution, 0.8) });
    for (const v of [rl, rh]) if (v !== null) lines.push({ y: v, color: withAlpha(c.warning, 0.8) });
    const imin = ins.met.length ? Math.max(ins.met[0], -10) : -10;
    const imax = ins.met.length ? Math.max(ins.met[ins.met.length - 1], imin + 60) : 60;
    drawPlot(document.getElementById('chart-inspector'), [
      { x: ins.met, y: ins.values, color: ins.ch.packet.startsWith('S2') ? c.s2 : c.s1 },
    ], { time: true, xMin: imin, xMax: imax, markers, lines, empty: 'No samples yet' });
  }
}

// ---------------------------------------------------------------------------
// Inspector and alarms

async function openInspector(name) {
  const ch = channel(name);
  if (!ch) return;
  app.inspector = { name, ch, met: [], values: [], loaded: false };
  for (const row of app.ui.channels) row.tr.setAttribute('aria-selected', row.ch.name === name);
  document.getElementById('inspector').hidden = false;
  document.getElementById('inspector-name').textContent = `${ch.name}${ch.units ? ` (${unitLabel(ch.units)})` : ''}`;
  document.getElementById('inspector-description').textContent = ch.description;
  const response = await fetch(`/api/history?channel=${encodeURIComponent(name)}`);
  if (!response.ok || !app.inspector || app.inspector.name !== name) return;
  const data = await response.json();
  app.inspector.met = data.met;
  app.inspector.values = data.values;
  app.inspector.loaded = true;
  schedule();
}

function closeInspector() {
  app.inspector = null;
  document.getElementById('inspector').hidden = true;
  for (const row of app.ui.channels) row.tr.setAttribute('aria-selected', 'false');
}

function wireAlarms() {
  document.getElementById('alarms').addEventListener('click', (e) => {
    const button = e.target.closest('button[data-id]');
    if (!button) return;
    button.disabled = true;
    fetch(`/api/alarms/ack?id=${encodeURIComponent(button.dataset.id)}`, { method: 'POST' });
  });
  document.getElementById('ack-all').addEventListener('click', () => {
    fetch('/api/alarms/ack-all', { method: 'POST' });
  });
  document.getElementById('inspector-close').addEventListener('click', closeInspector);
}

// ---------------------------------------------------------------------------

async function init() {
  app.colors = {
    bg: cssVar('--bg'), line: cssVar('--line'), lineStrong: cssVar('--line-strong'),
    text: cssVar('--text'), textDim: cssVar('--text-dim'), textFaint: cssVar('--text-faint'),
    s1: cssVar('--s1'), s2: cssVar('--s2'), ok: cssVar('--ok'), caution: cssVar('--caution'), warning: cssVar('--warning'),
  };
  app.dict = await (await fetch('/api/dictionary')).json();
  app.dict.channels.forEach((ch, index) => {
    ch.index = index;
    app.byName.set(ch.name, ch);
  });
  app.plot = app.dict.plot;
  clearHistory();
  app.ui.s1Readouts = buildReadouts(document.getElementById('s1-readouts'), S1_READOUTS);
  app.ui.s2Readouts = buildReadouts(document.getElementById('s2-readouts'), S2_READOUTS);
  app.ui.s1Systems = buildSystems(document.getElementById('s1-systems'), S1_SYSTEMS);
  app.ui.s2Systems = buildSystems(document.getElementById('s2-systems'), S2_SYSTEMS);
  app.ui.engines = buildEngines();
  app.ui.channels = buildChannelTable();
  app.ui.link = buildLink();
  buildLaunch();
  wireAlarms();
  window.addEventListener('resize', schedule);
  connect();
  render();
}

init();
