// Simulateur BULLYLIST : application + console de test, entièrement dans le navigateur.
// Le cœur (dictée, planning, ICS, pause, punchlines) est le même code que le site, porté à l'identique en Kotlin.
import { parseDictation, norm } from '../js/core/nlu.js';
import { plan } from '../js/core/planner.js';
import { parseICS, expand, toLocalEvents } from '../js/core/ics.js';
import { PROFILES, pauseIntervals, deferForPause } from '../js/core/availability.js';
import { learn, family } from '../js/core/estimator.js';
import { catalog, pick, pushRecent, LEVELS } from '../js/core/punchlines.js';
import { addDays, daysBetween, isoDay, fmtTime, fmtDay, localDate, localMinute, toDateObj } from '../js/core/dates.js';
import { showAlert, feedback, stopFeedback } from '../js/ui/alerts.js';
import { renderTasks, renderPlan, applyEdit, fmtMin } from '../js/ui/plan-view.js';
import { createDictation, speechSupported } from '../js/ui/voice.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const MIN = 60000;
const LEVEL_LABEL = { NORMAL: 'Normal', INSISTANT: 'Insistant', HARCELEMENT: 'Bully Mode', BRR: 'Nuclear' };

const [data, week] = await Promise.all([
  fetch('../assets/data/punchlines.json').then((r) => r.json()),
  fetch('../assets/data/student-week.json').then((r) => r.json()),
]);
const pool = catalog(data);

// ---------- État (stockage local du navigateur, facultatif) ----------
const KEY = 'bullylist-sim-v2';
const defaults = () => ({
  tasks: [], events: [], sources: [], recent: [], nextId: 1, clockOffset: 0,
  settings: {
    phone: { mode: 'sound', dnd: false, locked: true }, pauseEnabled: true,
    profiles: structuredClone(PROFILES), humorMax: 4, disabled: [], contextual: true,
    progressive: true, intervals: [5, 5, 5, 5, 10], maxRelances: 5, theme: 'dark', maxFocus: 300, learned: {},
  },
});
let S;
try { S = { ...defaults(), ...JSON.parse(localStorage.getItem(KEY) ?? 'null') }; } catch { S = defaults(); }
S.settings = { ...defaults().settings, ...S.settings };
const save = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch { /* navigation privée : état en mémoire seulement */ } };

const now = () => Date.now() + S.clockOffset;
const nowLocal = () => { const d = new Date(now()); return { date: localDate(d), minute: localMinute(d) }; };
const at = (date, minute) => toDateObj(date, minute).getTime();
const hhmm = (ms) => { const d = new Date(ms); return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`; };
const dayLabel = (ms) => fmtDay(localDate(new Date(ms)), nowLocal().date);

function log(text, kind = '') {
  const li = document.createElement('li'); li.className = kind;
  li.innerHTML = `<time>${hhmm(now())}</time>${esc(text)}`;
  $('log').prepend(li);
}
function toast(html, ms = 6000) {
  const d = document.createElement('div'); d.className = 'toast'; d.innerHTML = html;
  $('shade').append(d); setTimeout(() => d.remove(), ms);
}

// ---------- Rappels (mêmes règles que ReminderPlanner.kt) ----------
const levelIdx = (l) => LEVELS.indexOf(l);
function effectiveLevel(task, stepAfter) {
  if (stepAfter <= 0) return 'NORMAL';
  if (!S.settings.progressive) return task.level;
  return LEVELS[Math.min(stepAfter - 1, levelIdx(task.level))];
}
function rawNext(t) {
  if (t.done || t.due == null || t.remindersOff) return null;
  const r = t.rem;
  if (r.snoozedUntil) return r.snoozedUntil;
  if (r.step === 0) return t.due;
  if (r.step - 1 >= S.settings.maxRelances) return null;
  const iv = S.settings.intervals;
  return (r.lastFired ?? t.due) + iv[Math.min(r.step - 1, iv.length - 1)] * MIN;
}
const absEvents = () => S.events.map((e) => ({ ...e, start: at(e.date, e.start), end: at(e.date, e.end) }));
function nextTrigger(t) {
  const raw = rawNext(t);
  if (raw == null) return null;
  const stepAfter = t.rem.snoozedUntil ? Math.max(1, t.rem.step + 1) : t.rem.step + 1;
  return deferForPause(raw, pauseIntervals(absEvents(), S.settings.profiles), {
    level: effectiveLevel(t, stepAfter), bypassPause: t.bypassPause, enabled: S.settings.pauseEnabled,
  });
}

let currentAlert = null;
function tick() {
  for (const t of S.tasks) {
    const n = nextTrigger(t);
    if (!n) continue;
    if (n.pausedBy && t.rem.loggedPause !== n.at && rawNext(t) <= now()) {
      t.rem.loggedPause = n.at;
      log(`« ${t.title} » : rappel retenu par le mode pause (${n.pausedBy.title}). Reprise à ${hhmm(n.at)}.`, 'warn');
      toast(`<b>Mode pause</b> · ${esc(n.pausedBy.title)}<br>Rappels reportés à ${hhmm(n.at)}.`);
      save();
    }
    if (n.at <= now()) { fire(t); break; }
  }
  renderClock();
}

function fire(t, forcedLevel = null) {
  const r = t.rem; const tNow = now();
  let kind;
  if (forcedLevel) kind = 'TEST';
  else if (r.snoozedUntil) { kind = 'REPORT'; r.snoozedUntil = null; r.step += 1; }
  else { kind = r.step === 0 ? 'ÉCHÉANCE' : 'RELANCE'; r.step += 1; }
  if (!forcedLevel) r.lastFired = tNow;
  const level = forcedLevel ?? effectiveLevel(t, r.step);
  const isFinal = !forcedLevel && r.step - 1 >= S.settings.maxRelances;
  const late = t.due ? Math.max(0, Math.round((tNow - t.due) / MIN)) : 0;
  const req = { level, isFinal, taskTitle: t.title, minutesLate: late, snoozeCount: r.snoozeCount, recentIds: S.recent,
    disabledCategories: S.settings.disabled, maxIntensity: S.settings.humorMax, contextualEnabled: S.settings.contextual };
  const msgs = [0, 1, 2].map(() => { const p = pick(data, pool, req); S.recent = pushRecent(S.recent, p.id); return p.text; });
  save();
  deliver(t, level, msgs, late, kind, isFinal);
  renderView();
}

function deliver(t, level, msgs, late, kind, isFinal) {
  const n = levelIdx(level) + 1; const phone = S.settings.phone;
  log(`${kind} · « ${t.title} » → niveau ${n} (${LEVEL_LABEL[level]})${isFinal ? ', dernier avertissement' : ''}.`, 'alert');
  for (const f of feedback(n, phone, '../')) log(f);
  const open = () => {
    currentAlert?.close();
    currentAlert = showAlert($('screen'), {
      level: n, task: t.title, message: msgs[0], messages: msgs, minutesLate: late, snoozeCount: t.rem.snoozeCount,
      nextInSec: (() => { const nx = nextTrigger(t); return nx ? Math.max(0, Math.round((nx.at - now()) / 1000)) : 0; })(), seed: t.id,
      onAction: (a) => { stopFeedback(); act(t, a); },
    });
  };
  if (phone.dnd) {
    // Android filtre lui-même : notification silencieuse, pas d'écran plein écran.
    toast(`<b>Notification silencieuse</b> (Ne pas déranger)<br>${esc(t.title)} · niveau ${n}`, 9000);
    log('Ne pas déranger : Android masque le plein écran et coupe le son. Bullylist ne contourne pas ce réglage.', 'warn');
    return;
  }
  if (n >= 3 && !phone.locked) {
    // Téléphone en cours d'utilisation : Android affiche une notification prioritaire plutôt que le plein écran.
    const id = `ho${Date.now()}`;
    toast(`<b>${esc(t.title.toUpperCase())}</b><br>${esc(msgs[0])}<br><button class="mini-btn primary" id="${id}" style="margin-top:6px">Ouvrir l'alerte</button>`, 12000);
    setTimeout(() => $(id)?.addEventListener('click', open));
    log('Téléphone déverrouillé : notification prioritaire (Android n\'ouvre le plein écran que téléphone verrouillé ou écran éteint).');
    return;
  }
  open();
}

function act(t, a) {
  const r = t.rem;
  if (a === 'done') return complete(t);
  if (a.startsWith('snooze')) {
    const m = Number(a.slice(6));
    r.snoozedUntil = now() + m * MIN; r.step = r.step > 0 ? 1 : 0; r.snoozeCount += 1;
    log(`« ${t.title} » reporté de ${m} min (report n° ${r.snoozeCount}).`);
  } else if (a === 'tomorrow') {
    t.due = (t.due ?? now()) + 1440 * MIN; t.rem = freshRem();
    log(`« ${t.title} » reporté à demain ${hhmm(t.due)}.`);
  } else if (a === 'open') { switchTab('today'); }
  save(); renderView();
}

function complete(t) {
  t.done = true; t.completedAt = now(); t.rem = freshRem();
  // Apprentissage : durée constatée si la tâche est terminée pendant (ou peu après) son créneau.
  if (t.plannedStart && t.completedAt > t.plannedStart && t.completedAt - t.plannedStart < (t.plannedMinutes ?? 60) * 3 * MIN) {
    const real = Math.round((t.completedAt - t.plannedStart) / MIN);
    S.settings.learned = learn(S.settings.learned, norm(t.title), real);
    log(`Durée constatée pour « ${t.title} » : ${real} min (estimation ${t.plannedMinutes} min). Bullylist s'en souviendra.`, 'ok');
  }
  log(`« ${t.title} » terminée. Toutes ses alarmes sont annulées.`, 'ok');
  save(); renderView();
}

const freshRem = () => ({ step: 0, lastFired: null, snoozedUntil: null, snoozeCount: 0, loggedPause: null });
function addTask(p) {
  const t = { id: `s${S.nextId++}`, title: p.title, due: p.due ?? null, level: p.level ?? 'INSISTANT', priority: p.priority ?? 'NORMAL',
    deadline: p.deadline ?? null, plannedStart: p.plannedStart ?? null, plannedMinutes: p.plannedMinutes ?? null, bypassPause: false,
    done: false, createdAt: now(), rem: freshRem() };
  S.tasks.push(t);
  return t;
}

// ---------- Vues ----------
let tab = 'today';
function switchTab(t) { tab = t; document.querySelectorAll('#tabs button').forEach((b) => b.toggleAttribute('aria-current', b.dataset.tab === t)); renderView(); }

function renderView() {
  const v = $('view');
  ({ today: viewToday, plan: viewPlan, cal: viewCal, punch: viewPunch, settings: viewSettings })[tab](v);
  renderConsoleTasks();
}

function taskMeta(t) {
  const out = [];
  if (t.due) {
    const late = Math.round((now() - t.due) / MIN);
    out.push(late > 0 && !t.done ? `<span class="late">En retard de ${fmtMin(late)}</span>` : `${esc(dayLabel(t.due))} ${hhmm(t.due)}`);
  }
  out.push(`<span class="lvl" data-l="${levelIdx(t.level) + 1}">${LEVEL_LABEL[t.level]}</span>`);
  if (t.deadline) out.push(`Échéance ${esc(dayLabel(t.deadline))} ${hhmm(t.deadline)}`);
  const n = !t.done && nextTrigger(t);
  if (n?.pausedBy) out.push('En pause');
  if (t.rem.snoozedUntil) out.push(`Reporté à ${hhmm(t.rem.snoozedUntil)}`);
  return out.join('');
}

function viewToday(v) {
  const open = S.tasks.filter((t) => !t.done).sort((a, b) => (a.due ?? Infinity) - (b.due ?? Infinity));
  const done = S.tasks.filter((t) => t.done).slice(-5).reverse();
  const late = open.filter((t) => t.due && t.due < now()).length;
  v.innerHTML = `<h1>Aujourd'hui</h1><div class="sub">${open.length} mission${open.length > 1 ? 's' : ''}${late ? ` · ${late} en retard` : ''}</div>
    <form class="quick" id="qa"><input id="qa-in" placeholder="Sortir les poubelles demain à 20h" aria-label="Ajout rapide"><button>Ajouter</button></form>
    <div class="qhint">Écris comme tu parles : « appeler le dentiste demain à 10h, c'est urgent ».</div>
    ${open.length ? `<ul class="tasks">${open.map(row).join('')}</ul>` : '<p class="empty">Aucune mission en cours. Ajoute une tâche ou appuie sur le micro.</p>'}
    ${done.length ? `<h2>Terminées récemment</h2><ul class="tasks">${done.map(row).join('')}</ul>` : ''}`;
  $('qa').onsubmit = (e) => {
    e.preventDefault();
    const text = $('qa-in').value.trim(); if (!text) return;
    const r = parseDictation(text, nowLocal(), { learned: S.settings.learned });
    if (!r.tasks.length) { addTask({ title: text }); log(`Tâche ajoutée telle quelle : « ${text} ».`); }
    for (const t of r.tasks) {
      const due = t.window?.fixed ? at(t.window.date, t.window.start) : t.window ? at(t.window.date, t.window.start) : t.deadline ? at(t.deadline.date, Math.min(t.deadline.minute, 1439)) : null;
      addTask({ title: t.title, due, level: t.level, priority: t.priority, deadline: t.deadline ? at(t.deadline.date, t.deadline.minute) : null });
      log(`Tâche ajoutée : « ${t.title} »${due ? `, rappel ${dayLabel(due).toLowerCase()} à ${hhmm(due)}` : ''}${t.notes.length ? ` (${t.notes[0]})` : ''}.`, 'ok');
    }
    save(); renderView();
  };
  v.querySelectorAll('[data-done]').forEach((b) => (b.onclick = () => {
    const t = S.tasks.find((x) => x.id === b.dataset.done);
    if (t.done) { t.done = false; t.rem = freshRem(); log(`« ${t.title} » rouverte.`); save(); renderView(); } else complete(t);
  }));
  v.querySelectorAll('[data-del]').forEach((b) => (b.onclick = () => {
    const i = S.tasks.findIndex((x) => x.id === b.dataset.del); const [t] = S.tasks.splice(i, 1);
    log(`« ${t.title} » supprimée, alarmes annulées.`); save(); renderView();
  }));
}
const row = (t) => `<li class="${t.done ? 'done' : ''}" data-level="${t.level}"><button class="ck" data-done="${t.id}" aria-label="${t.done ? 'Rouvrir' : 'Terminer'} ${esc(t.title)}"></button>
  <div><div class="tt">${esc(t.title)}</div><div class="meta">${taskMeta(t)}</div></div><button class="del" data-del="${t.id}" aria-label="Supprimer ${esc(t.title)}">×</button></li>`;

function weekResult() {
  const today = nowLocal().date; const days = [];
  for (let i = 0; i < 7; i++) {
    const d = addDays(today, i); const blocks = [];
    for (const e of S.events.filter((x) => x.date === d)) {
      blocks.push({ kind: 'event', start: e.start, end: e.end, title: e.title, location: e.location });
      const p = S.settings.profiles[e.profile] ?? {};
      const tb = e.travelBefore ?? p.travelBefore ?? 0; const ta = e.travelAfter ?? p.travelAfter ?? 0;
      if (tb) blocks.push({ kind: 'travel', start: e.start - tb, end: e.start, title: 'Trajet' });
      if (ta) blocks.push({ kind: 'travel', start: e.end, end: e.end + ta, title: 'Trajet' });
    }
    let focus = 0;
    for (const t of S.tasks.filter((x) => !x.done && x.plannedStart && localDate(new Date(x.plannedStart)) === d)) {
      const s = localMinute(new Date(t.plannedStart)); blocks.push({ kind: 'task', start: s, end: s + (t.plannedMinutes ?? 30), title: t.title, level: t.level }); focus += t.plannedMinutes ?? 30;
    }
    blocks.sort((a, b) => a.start - b.start);
    days.push({ date: d, label: fmtDay(d, today), focus, free: null, blocks });
  }
  return { days, unplaced: [], warnings: [], feasible: true };
}

function viewPlan(v) {
  const unscheduled = S.tasks.filter((t) => !t.done && !t.plannedStart);
  v.innerHTML = `<h1>Planning</h1><div class="sub">7 prochains jours · cours, trajets et créneaux validés</div>
    ${unscheduled.length ? `<p><button class="mini-btn primary" id="replan">Planifier ${unscheduled.length} tâche${unscheduled.length > 1 ? 's' : ''} sans créneau</button></p>` : ''}
    <div class="plan" id="wk"></div>`;
  renderPlan($('wk'), weekResult());
  if (!$('wk').innerHTML) $('wk').innerHTML = '<p class="empty">Rien de prévu. Charge la semaine étudiante depuis la console ou dicte tes tâches.</p>';
  $('replan')?.addEventListener('click', () => {
    const n = nowLocal();
    const tasks = unscheduled.map((t) => {
      const f = parseDictation(`Je dois ${t.title.charAt(0).toLowerCase()}${t.title.slice(1)}.`, n, { learned: S.settings.learned }).tasks[0];
      const d = t.deadline ? new Date(t.deadline) : null;
      return { ...(f ?? { notes: [], dependsOn: [], estimate: { min: 30, max: 60 }, minutes: 45, recurrence: null }), id: t.id, title: t.title, level: t.level, priority: t.priority,
        deadline: d ? { date: localDate(d), minute: localMinute(d) } : null, window: null, horizon: { from: n.date, until: addDays(n.date, 6) } };
    });
    openVoice({ tasks, replaceIds: unscheduled.map((t) => t.id) });
  });
}

function viewCal(v) {
  const n = nowLocal();
  const pauseNow = deferForPause(now(), pauseIntervals(absEvents(), S.settings.profiles), { enabled: S.settings.pauseEnabled });
  const upcoming = S.events.filter((e) => e.date >= n.date).sort((a, b) => (a.date + a.start).localeCompare(b.date + b.start)).slice(0, 14);
  v.innerHTML = `<h1>Agenda</h1><div class="sub">${S.sources.length} source${S.sources.length > 1 ? 's' : ''} · lecture seule</div>
    <div class="toggle-row"><div>Mode pause automatique<small>${pauseNow.pausedBy ? `Actif : ${esc(pauseNow.pausedBy.title)} jusqu'à ${hhmm(pauseNow.at)}` : 'Aucun engagement en cours'}</small></div><input type="checkbox" id="pz" ${S.settings.pauseEnabled ? 'checked' : ''} aria-label="Mode pause automatique"></div>
    <h2>Sources</h2><ul class="list-plain">${S.sources.map((s) => `<li><b>${esc(s.name)}</b><div class="meta">${esc(s.kind)} · profil ${esc(PROFILES[s.profile]?.label ?? s.profile)} · ${s.count} événements</div>
      <div style="display:flex;gap:6px;margin-top:6px"><select data-prof="${s.id}" class="mini-btn">${Object.entries(PROFILES).map(([k, p]) => `<option value="${k}" ${k === s.profile ? 'selected' : ''}>${p.label}</option>`).join('')}</select><button class="mini-btn" data-rm="${s.id}">Retirer</button></div></li>`).join('') || '<li class="muted">Aucun calendrier.</li>'}</ul>
    <h2>Ajouter</h2>
    <div class="form-row"><label for="ics-file">Fichier .ics (export CELCAT, Apple, Google…)</label><input type="file" id="ics-file" accept=".ics,text/calendar"></div>
    <div class="form-row"><label for="ics-url">Lien d'abonnement iCal (https:// ou webcal://)</label><input id="ics-url" placeholder="https://…/calendar.ics"><button class="mini-btn" id="ics-go">Ajouter le lien</button></div>
    <h2>Prochains événements</h2><ul class="list-plain">${upcoming.map((e) => `<li>${esc(e.title)}<div class="meta">${esc(fmtDay(e.date, n.date))} · ${fmtTime(e.start)}–${fmtTime(e.end)}${e.location ? ` · ${esc(e.location)}` : ''} · ${esc(PROFILES[e.profile]?.label ?? '')}</div></li>`).join('') || '<li class="muted">Aucun événement à venir.</li>'}</ul>`;
  $('pz').onchange = (e) => { S.settings.pauseEnabled = e.target.checked; log(`Mode pause ${e.target.checked ? 'activé' : 'désactivé manuellement'}.`); save(); renderView(); };
  v.querySelectorAll('[data-rm]').forEach((b) => (b.onclick = () => { S.sources = S.sources.filter((s) => s.id !== b.dataset.rm); S.events = S.events.filter((e) => e.source !== b.dataset.rm); save(); renderView(); }));
  v.querySelectorAll('[data-prof]').forEach((sel) => (sel.onchange = () => {
    const s = S.sources.find((x) => x.id === sel.dataset.prof); s.profile = sel.value;
    S.events.forEach((e) => { if (e.source === s.id) { e.profile = sel.value; delete e.travelBefore; delete e.travelAfter; } }); save(); renderView();
  }));
  $('ics-file').onchange = async (e) => { const f = e.target.files[0]; if (f) importIcs(await f.text(), f.name.replace(/\.ics$/i, ''), 'Fichier .ics'); };
  $('ics-go').onclick = async () => {
    const url = $('ics-url').value.trim().replace(/^webcal:/i, 'https:');
    if (!/^https:\/\//i.test(url)) { toast('Le lien doit commencer par https:// ou webcal://'); return; }
    try { const r = await fetch(url); if (!r.ok) throw new Error(`HTTP ${r.status}`); importIcs(await r.text(), new URL(url).hostname, 'Lien iCal'); }
    catch (err) { log(`Le navigateur n'a pas pu lire ce lien (${err.message}). La plupart des serveurs bloquent la lecture depuis une page web (CORS). L'application Android, elle, le télécharge directement. Ici, utilise l'import de fichier.`, 'warn'); }
  };
}

function importIcs(text, name, kind) {
  const parsed = parseICS(text);
  const from = Date.now() - 86400000; const to = Date.now() + 60 * 86400000;
  const occ = expand(parsed, from, to);
  const id = `src${S.nextId++}`;
  const evs = toLocalEvents(occ, { profile: 'COURS' }).map((e) => ({ ...e, travelBefore: undefined, travelAfter: undefined, source: id }));
  S.sources.push({ id, name: parsed.calName ?? name, kind, profile: 'COURS', count: evs.length });
  S.events.push(...evs);
  log(`Calendrier importé : ${parsed.calName ?? name} — ${parsed.events.length} définitions, ${evs.length} occurrences sur 60 jours (répétitions, exceptions et cours annulés pris en compte).`, 'ok');
  save(); renderView();
}

function viewPunch(v) {
  const st = S.settings; const s = pick(data, pool, { level: 'HARCELEMENT', recentIds: [], disabledCategories: st.disabled, maxIntensity: st.humorMax, contextualEnabled: false });
  v.innerHTML = `<h1>Punchlines</h1><div class="sub">${pool.length} phrases · ${data.categories.length} catégories</div>
    <div class="form-row"><label>Intensité de l'humour</label><select id="hm">${['Douce', 'Piquante', 'Sévère', 'Nucléaire'].map((l, i) => `<option value="${i + 1}" ${st.humorMax === i + 1 ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
    <div class="toggle-row"><div>Messages avec le nom de la tâche<small>« Ton diplôme ne va pas apparaître dans une boîte surprise. »</small></div><input type="checkbox" id="ctx" ${st.contextual ? 'checked' : ''} aria-label="Messages contextuels"></div>
    <p style="margin:14px 0;font-weight:600">« ${esc(s.text)} »</p>
    <h2>Catégories</h2>${data.categories.map((c) => `<div class="toggle-row"><div>${esc(c.label)}<small>${esc(c.description)} · ${data.lines[c.id].length}</small></div><input type="checkbox" data-cat="${c.id}" ${st.disabled.includes(c.id) ? '' : 'checked'} aria-label="${esc(c.label)}"></div>`).join('')}`;
  $('hm').onchange = (e) => { st.humorMax = Number(e.target.value); save(); renderView(); };
  $('ctx').onchange = (e) => { st.contextual = e.target.checked; save(); };
  v.querySelectorAll('[data-cat]').forEach((c) => (c.onchange = () => {
    st.disabled = c.checked ? st.disabled.filter((x) => x !== c.dataset.cat) : [...st.disabled, c.dataset.cat]; save();
  }));
}

function viewSettings(v) {
  const st = S.settings;
  v.innerHTML = `<h1>Réglages</h1><div class="sub">Comportement des rappels et du planning</div>
    <div class="toggle-row"><div>Thème clair</div><input type="checkbox" id="th" ${st.theme === 'light' ? 'checked' : ''} aria-label="Thème clair"></div>
    <div class="toggle-row"><div>Escalade progressive<small>Le niveau monte à chaque relance, jusqu'au plafond de la tâche.</small></div><input type="checkbox" id="pg" ${st.progressive ? 'checked' : ''} aria-label="Escalade progressive"></div>
    <div class="form-row"><label for="mf">Travail maximum par jour dans le planning</label><select id="mf">${[180, 240, 300, 360, 420].map((m) => `<option value="${m}" ${st.maxFocus === m ? 'selected' : ''}>${fmtMin(m)}</option>`).join('')}</select></div>
    <h2>Profils de pause</h2>${Object.entries(st.profiles).map(([k, p]) => `<div class="toggle-row"><div>${esc(p.label)}<small>Trajet après : <input type="number" min="0" max="120" step="5" value="${p.travelAfter}" data-tr="${k}" style="width:56px" aria-label="Trajet après ${esc(p.label)}"> min</small></div><input type="checkbox" data-pz="${k}" ${p.pause ? 'checked' : ''} aria-label="Pause pendant ${esc(p.label)}"></div>`).join('')}
    <p class="muted" style="font-size:12px;margin-top:12px">Sommeil : sur Android, il correspond à la plage horaire autorisée des rappels (Réglages → Alarmes).</p>`;
  $('th').onchange = (e) => { st.theme = e.target.checked ? 'light' : 'dark'; applyTheme(); save(); };
  $('pg').onchange = (e) => { st.progressive = e.target.checked; save(); };
  $('mf').onchange = (e) => { st.maxFocus = Number(e.target.value); save(); };
  v.querySelectorAll('[data-pz]').forEach((c) => (c.onchange = () => { st.profiles[c.dataset.pz].pause = c.checked; save(); }));
  v.querySelectorAll('[data-tr]').forEach((c) => (c.onchange = () => { st.profiles[c.dataset.tr].travelAfter = Math.max(0, Number(c.value) || 0); save(); }));
}
const applyTheme = () => { $('screen').dataset.theme = S.settings.theme; $('screen').style.colorScheme = S.settings.theme; document.querySelector('#screen').setAttribute('data-theme', S.settings.theme); };

// ---------- Dictée et planning ----------
function openVoice(preset = null) {
  const n = nowLocal();
  const el = document.createElement('div'); el.className = 'voice'; el.setAttribute('role', 'dialog'); el.setAttribute('aria-label', 'Dicter des tâches');
  el.innerHTML = `<div class="top"><span class="lvl" data-l="1">Assistant</span><button class="close" aria-label="Fermer">×</button></div>
    <h3>${preset ? 'Planifier mes tâches' : 'Raconte-moi ta semaine.'}</h3>
    ${preset ? '' : `<div class="micwrap"><button class="mic" aria-pressed="false" aria-label="Démarrer la dictée"><svg viewBox="0 0 24 24"><path d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-2.08A7 7 0 0 0 19 12Z" fill="currentColor"/></svg></button></div>
    <div class="bars" aria-hidden="true">${'<i></i>'.repeat(18)}</div>
    <textarea aria-label="Transcription (modifiable)" placeholder="Parle, ou écris ici. Le texte reste modifiable."></textarea>
    <div class="note">${speechSupported() ? 'Micro : reconnaissance vocale de Chrome (service en ligne Google). Rien n\'est enregistré. Le mode texte fonctionne sans réseau.' : 'Ce navigateur ne propose pas la dictée : écris ton texte, l\'analyse est identique.'}</div>
    <div class="acts"><button class="mini-btn primary" data-a="analyse">Analyser</button><button class="mini-btn" data-a="restart">Recommencer</button><button class="mini-btn" data-a="sample">Exemple</button></div>`}
    <div class="result"></div>`;
  $('screen').append(el);
  const ta = el.querySelector('textarea'); const res = el.querySelector('.result'); const mic = el.querySelector('.mic');
  let tasks = preset?.tasks ?? []; let result = null; let intent = 'plan';
  const close = () => { dict?.pause(); el.remove(); };
  el.querySelector('.close').onclick = close;

  const bars = [...el.querySelectorAll('.bars i')];
  const dict = mic && createDictation({
    onText: (fin, interim) => { ta.value = (fin + interim).trim(); },
    onLevel: (lv) => { mic.style.setProperty('--lv', lv.toFixed(2)); bars.forEach((b, i) => (b.style.height = `${4 + lv * 24 * (0.5 + Math.abs(Math.sin(i * 1.7 + performance.now() / 180)) / 2)}px`)); },
    onState: (s) => { mic.classList.toggle('on', s === 'listening'); mic.setAttribute('aria-pressed', String(s === 'listening')); if (s === 'denied') log('Micro refusé par le navigateur : utilise le mode texte.', 'warn'); if (s === 'paused' && ta.value.trim()) analyse(); },
  });
  mic?.addEventListener('click', () => {
    if (mic.getAttribute('aria-pressed') === 'true') return dict.pause();
    if (!speechSupported()) return ta.focus();
    if (!confirm('La dictée du navigateur envoie le son au service de reconnaissance vocale de Google. Rien n\'est conservé par Bullylist. Continuer ?')) return;
    dict.start(ta.value);
  });
  el.querySelector('.acts')?.addEventListener('click', (e) => {
    const a = e.target.closest('[data-a]')?.dataset.a;
    if (a === 'analyse') analyse();
    if (a === 'restart') { ta.value = ''; res.innerHTML = ''; tasks = []; }
    if (a === 'sample') { ta.value = week.dictation; analyse(); }
  });

  function analyse() {
    const r = parseDictation(ta.value, nowLocal(), { learned: S.settings.learned });
    tasks = r.tasks; intent = r.intent;
    if (r.questions.length) log(`Question de l'assistant : ${r.questions.join(' ')}`, 'warn');
    replan(r.questions);
  }
  function replan(questions = []) {
    if (!tasks.length) { res.innerHTML = '<p class="empty">Je n\'ai reconnu aucune tâche. Essaie « Je dois faire les courses demain après-midi ».</p>'; return; }
    result = plan({ tasks, events: S.events.map((e) => ({ ...e, travelBefore: e.travelBefore ?? S.settings.profiles[e.profile]?.travelBefore, travelAfter: e.travelAfter ?? S.settings.profiles[e.profile]?.travelAfter })),
      now: nowLocal(), settings: { maxFocusPerDay: S.settings.maxFocus } });
    const single = intent === 'add' && tasks.length === 1 && !questions.length;
    res.innerHTML = `${questions.length ? `<div class="plan-warn">${questions.map(esc).join('<br>')}</div>` : ''}
      <p style="font-weight:700;margin:6px 0 8px">${single ? 'Je l\'ajoute comme ceci :' : 'Voici comment je te propose d\'organiser ta semaine.'}</p>
      <ul class="xtasks" id="vx"></ul><div class="plan" id="vp" style="margin-top:12px"></div>
      <div class="sticky"><button class="mini-btn primary" data-v="accept">${single ? 'Ajouter' : 'Tout accepter'}</button><button class="mini-btn" data-v="replan">Recommencer la planification</button></div>`;
    renderTasks(res.querySelector('#vx'), tasks, nowLocal(), (act, id, value) => { tasks = applyEdit(tasks, act, id, value, nowLocal()); replan(); });
    renderPlan(res.querySelector('#vp'), result);
    res.querySelector('[data-v="accept"]').onclick = accept;
    res.querySelector('[data-v="replan"]').onclick = () => replan();
  }
  function accept() {
    if (preset?.replaceIds) S.tasks = S.tasks.filter((t) => !preset.replaceIds.includes(t.id));
    let created = 0;
    for (const t of tasks) {
      const blocks = result.days.flatMap((d) => d.blocks.filter((b) => b.kind === 'task' && b.taskId === t.id).map((b) => ({ ...b, date: d.date })));
      const deadline = t.deadline ? at(t.deadline.date, Math.min(1439, t.deadline.minute)) : null;
      blocks.forEach((b, i) => {
        addTask({ title: blocks.length > 1 ? `${t.title} (${i + 1}/${blocks.length})` : t.title, due: at(b.date, b.start), plannedStart: at(b.date, b.start),
          plannedMinutes: b.end - b.start, level: t.level, priority: t.priority, deadline }); created++;
      });
      if (!blocks.length) { addTask({ title: t.title, level: t.level, priority: t.priority, deadline }); created++; }
    }
    log(`${created} créneau${created > 1 ? 'x' : ''} enregistré${created > 1 ? 's' : ''} dans Bullylist, rappels programmés au début de chaque bloc.`, 'ok');
    save(); close(); switchTab('plan');
  }
  if (preset) replan();
}

// ---------- Console ----------
function renderConsoleTasks() {
  const sel = $('c-task'); const cur = sel.value;
  sel.innerHTML = `<option value="">Exemple : Sortir les poubelles</option>${S.tasks.filter((t) => !t.done).map((t) => `<option value="${t.id}">${esc(t.title)}</option>`).join('')}`;
  sel.value = [...sel.options].some((o) => o.value === cur) ? cur : '';
}
function renderClock() {
  const d = new Date(now());
  $('clock').textContent = hhmm(now());
  $('c-clock').textContent = `${fmtDay(localDate(d), localDate(new Date()))} ${hhmm(now())}`;
  $('c-clock-mode').textContent = S.clockOffset ? `(avance de ${fmtMin(Math.round(S.clockOffset / MIN))})` : '(heure réelle)';
  const ph = S.settings.phone;
  $('status-icons').textContent = `${ph.dnd ? 'NPD · ' : ''}${{ sound: 'Son', vibrate: 'Vibreur', silent: 'Silence' }[ph.mode]} · 5G`;
}

document.querySelector('.levelbtns').addEventListener('click', (e) => {
  const b = e.target.closest('[data-test]'); if (!b) return;
  const n = Number(b.dataset.test);
  let t = S.tasks.find((x) => x.id === $('c-task').value);
  const temp = !t;
  if (!t) t = { id: 'demo', title: 'Sortir les poubelles', level: 'BRR', due: now() - 47 * MIN, rem: { ...freshRem(), snoozeCount: 2 } };
  const late = t.due ? Math.max(0, Math.round((now() - t.due) / MIN)) : 0;
  const req = { level: LEVELS[n - 1], taskTitle: t.title, minutesLate: late, snoozeCount: t.rem.snoozeCount, recentIds: S.recent, disabledCategories: S.settings.disabled, maxIntensity: S.settings.humorMax, contextualEnabled: S.settings.contextual };
  const msgs = [0, 1, 2].map(() => { const p = pick(data, pool, req); S.recent = pushRecent(S.recent, p.id); return p.text; });
  deliver(temp ? { ...t, title: t.title } : t, LEVELS[n - 1], msgs, late, 'TEST', false);
  if (temp) log('Tâche d\'exemple : les boutons de l\'alerte n\'affectent aucune vraie tâche.');
});
$('c-late').onclick = () => {
  const t = addTask({ title: 'Sortir les poubelles', due: now() - 47 * MIN, level: 'BRR', priority: 'HIGH' });
  t.rem = { ...freshRem(), step: 3, lastFired: now() - 15 * MIN, snoozeCount: 2 };
  log('Tâche en retard créée : échéance il y a 47 min, 3 rappels déjà ignorés. La relance suivante passe en Nuclear.');
  save(); renderView(); tick();
};
$('c-pause').onclick = () => {
  const n = nowLocal();
  const start = Math.max(0, n.minute - 10); const end = Math.min(1439, n.minute + 50);
  const id = 'sim-pause';
  S.sources = S.sources.filter((s) => s.id !== id); S.events = S.events.filter((e) => e.source !== id);
  S.sources.push({ id, name: 'Cours simulé (console)', kind: 'Simulation', profile: 'COURS', count: 1 });
  S.events.push({ date: n.date, start, end, title: 'Cours simulé', location: 'Amphi B', profile: 'COURS', source: id });
  addTask({ title: 'Réviser le chapitre 4', due: now(), level: 'HARCELEMENT' });
  log(`Cours simulé de ${fmtTime(start)} à ${fmtTime(end)} (+${S.settings.profiles.COURS.travelAfter} min de trajet) et rappel « Réviser le chapitre 4 » dû maintenant.`);
  save(); renderView(); tick();
};
$('c-mode').onclick = (e) => {
  const b = e.target.closest('button'); if (!b) return;
  S.settings.phone.mode = b.dataset.v;
  document.querySelectorAll('#c-mode button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
  if (b.dataset.v !== 'sound') stopFeedback();
  log(`Téléphone : ${b.textContent.toLowerCase()}.`); save(); renderClock();
};
$('c-dnd').onchange = (e) => { S.settings.phone.dnd = e.target.checked; if (e.target.checked) stopFeedback(); log(`Ne pas déranger ${e.target.checked ? 'activé' : 'désactivé'}.`); save(); renderClock(); };
$('c-locked').onchange = (e) => { S.settings.phone.locked = e.target.checked; save(); };
document.querySelectorAll('[data-adv]').forEach((b) => (b.onclick = () => { S.clockOffset += Number(b.dataset.adv) * MIN; log(`Horloge avancée de ${fmtMin(Number(b.dataset.adv))}.`); save(); tick(); renderView(); }));
$('c-now').onclick = () => { S.clockOffset = 0; save(); tick(); renderView(); };
$('c-demo').onclick = () => {
  // Les dates de la semaine type sont recalées sur la semaine en cours.
  const monday = addDays(localDate(new Date()), 1 - isoDay(localDate(new Date())));
  const shift = daysBetween(week.now.date, monday);
  S.sources = S.sources.filter((s) => s.id !== 'demo'); S.events = S.events.filter((e) => e.source !== 'demo');
  const evs = week.events.map((e) => ({ ...e, date: addDays(e.date, shift), source: 'demo' }));
  S.events.push(...evs);
  S.sources.push({ id: 'demo', name: 'L2 Gestion — Groupe 3 (démo CELCAT)', kind: 'Lien iCal (démonstration)', profile: 'COURS', count: evs.length });
  log(`Semaine étudiante chargée : ${evs.length} cours et rendez-vous. Dicte maintenant tes tâches avec le micro.`, 'ok');
  save(); switchTab('cal');
};
$('c-reset').onclick = () => { if (!confirm('Effacer toutes les données du simulateur ?')) return; S = defaults(); save(); $('log').innerHTML = ''; syncConsole(); switchTab('today'); };
$('fab-voice').onclick = () => openVoice();
$('tabs').onclick = (e) => { const b = e.target.closest('[data-tab]'); if (b) switchTab(b.dataset.tab); };

function syncConsole() {
  const ph = S.settings.phone;
  document.querySelectorAll('#c-mode button').forEach((x) => x.setAttribute('aria-pressed', String(x.dataset.v === ph.mode)));
  $('c-dnd').checked = ph.dnd; $('c-locked').checked = ph.locked;
  applyTheme();
}

syncConsole(); renderView(); renderClock();
if (!S.tasks.length && !S.events.length) log('Bienvenue. Charge la semaine étudiante, dicte des tâches avec le micro, ou déclenche directement un niveau d\'alerte.');
setInterval(tick, 1000);
// Exposé pour les tests automatisés (scripts/test-simulator.mjs).
window.__sim = { get state() { return S; }, tick, now, nextTrigger };
