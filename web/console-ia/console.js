// Console vocale : dictée (ou texte) → moteur IA (serveur) ou local → tâches, dates, dépendances, planning.
// Même cœur que l'application : nlu.js (local), understanding.js (contrat IA), planner.js.
import { parseDictation } from '../js/core/nlu.js';
import { plan } from '../js/core/planner.js';
import { toPlanInput, applyItemEdit } from '../js/core/understanding.js';
import { addDays, daysBetween, isoDay, localDate, localMinute, fmtTime, fmtDay } from '../js/core/dates.js';
import { renderTasks, renderPlan, applyEdit, fmtMin } from '../js/ui/plan-view.js';
import { createDictation, speechSupported } from '../js/ui/voice.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = { get: (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } }, set: (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* navigation privée */ } } };

const EXAMPLES = [
  "Alors, mardi j'ai cours le matin. Après, j'aimerais bien avancer sur mon dossier de marketing. Il faut que je fasse aussi les recherches pour la deuxième partie, et surtout que je n'oublie pas d'envoyer le mail à mon professeur. Mercredi, je voudrais réviser les maths, mais pas forcément toute la journée. Je pense qu'une heure et demie devrait suffire. Jeudi, j'ai envie de terminer le dossier marketing. Et dimanche, je veux préparer mes affaires pour la semaine, faire les courses et ranger un peu l'appartement. Ah, et il faudrait absolument que j'aie terminé le dossier avant vendredi soir.",
  "Mardi, je voudrais travailler sur mon exposé. Il faudrait que je fasse les recherches, que je trouve des images, que je prépare les diapositives et que je regarde aussi si les sources sont bonnes. Je pense que les recherches vont prendre une heure, les diapositives peut-être deux heures.",
  'Mercredi, je vais réviser les maths pendant deux heures. Enfin non, plutôt une heure et demie.',
  "Ah, j'ai oublié quelque chose. Mardi, il faut aussi que je passe à la pharmacie. Et mercredi, ne me mets rien après 18 heures.",
];

const cfg = store.get('bullylist-console', { engine: 'ia', url: 'http://localhost:8787', consent: false });
const S = { turns: [], understanding: null, ia: null, localTasks: [], local: null, overrides: {}, mode: null, result: null, items: [] };
let sample = null;

// ---------- Réglages ----------
for (const r of document.querySelectorAll('[name=engine]')) { r.checked = r.value === cfg.engine; r.onchange = () => { cfg.engine = r.value; saveCfg(); syncCfg(); }; }
$('url').value = cfg.url; $('consent').checked = cfg.consent;
$('url').onchange = () => { cfg.url = $('url').value.trim(); saveCfg(); health(); };
$('consent').onchange = () => { cfg.consent = $('consent').checked; saveCfg(); };
const d0 = new Date();
$('ref-date').value = localDate(d0); $('ref-time').value = `${String(d0.getHours()).padStart(2, '0')}:${String(d0.getMinutes()).padStart(2, '0')}`;
$('sample-cal').onchange = () => S.mode && render();
function saveCfg() { store.set('bullylist-console', cfg); }
function syncCfg() { $('ia-cfg').hidden = cfg.engine !== 'ia'; if (cfg.engine === 'ia') health(); }
syncCfg();

function now() {
  const [h, m] = ($('ref-time').value || '10:00').split(':').map(Number);
  return { date: $('ref-date').value || localDate(new Date()), minute: h * 60 + m };
}

async function health() {
  $('health').textContent = 'Vérification du serveur…';
  try {
    const r = await fetch(`${cfg.url.replace(/\/$/, '')}/api/health`);
    const b = await r.json();
    $('health').textContent = `Serveur joignable · modèle ${b.model}${b.auth ? ' · jeton exigé' : ''}.`;
  } catch {
    $('health').textContent = "Serveur injoignable : l'analyse locale sera utilisée à la place (voir docs/ASSISTANT-IA.md pour le lancer).";
  }
}

// ---------- Agenda d'exemple, décalé sur la semaine de la date de référence ----------
async function calendarEvents() {
  if (!$('sample-cal').checked) return [];
  sample ??= await fetch('../assets/data/student-week.json').then((r) => r.json());
  const n = now();
  const delta = daysBetween('2026-11-09', addDays(n.date, 1 - isoDay(n.date)));
  return sample.events.map((e) => ({ ...e, date: addDays(e.date, delta) }));
}

// ---------- Dictée ----------
let listening = false;
const dict = createDictation({
  onText: (final, interim) => { $('text').value = (final + interim).trim(); },
  onState: (s) => {
    listening = s === 'listening';
    $('mic').setAttribute('aria-pressed', String(listening));
    $('mic').textContent = listening ? 'Arrêter' : 'Dicter';
    if (s === 'denied') $('mic-hint').textContent = 'Micro refusé par le navigateur : utilise le texte, le moteur est le même.';
  },
});
if (!speechSupported()) { $('mic').disabled = true; $('mic-hint').textContent = "Dictée non disponible dans ce navigateur : saisis le texte, l'analyse est identique."; }
else $('mic-hint').textContent = 'Dans Chrome, la dictée passe par le service en ligne de Google.';
$('mic').onclick = () => {
  if (listening) return dict.pause();
  if (!store.get('bullylist-console-mic-ok', false)) {
    if (!confirm('La dictée du navigateur envoie ta voix au service de reconnaissance du navigateur (Google pour Chrome). Continuer ?')) return;
    store.set('bullylist-console-mic-ok', true);
  }
  dict.start($('text').value);
};

$('examples').innerHTML = EXAMPLES.map((t, i) => `<li><button data-ex="${i}" type="button">${esc(t.slice(0, 90))}…</button></li>`).join('');
$('examples').onclick = (e) => { const b = e.target.closest('[data-ex]'); if (b) $('text').value = EXAMPLES[+b.dataset.ex]; };

// ---------- Analyse ----------
const status = (msg, err = false) => { $('status').textContent = msg; $('status').className = `status${err ? ' err' : ''}`; };

$('go').onclick = async () => {
  if (listening) dict.pause();
  const text = $('text').value.trim();
  if (!text) return status('Dicte ou saisis quelque chose.', true);
  const n = now();
  $('go').disabled = true;
  const turns = [...S.turns, text];
  S.local = parseDictation(turns.join(' '), n);
  try {
    if (cfg.engine === 'ia') {
      if (!cfg.consent) throw Object.assign(new Error("Coche l'accord d'envoi au service IA, ou choisis le moteur local."), { noFallback: true });
      status('Analyse par le moteur IA…');
      const t0 = performance.now();
      const res = await fetch(`${cfg.url.replace(/\/$/, '')}/api/understand`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...($('token').value ? { Authorization: `Bearer ${$('token').value}` } : {}) },
        body: JSON.stringify({ text, now: n, tz: Intl.DateTimeFormat().resolvedOptions().timeZone, previous: S.understanding, consent: true }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.message ?? `Erreur ${res.status}`);
      S.understanding = body.understanding; S.ia = body; S.mode = 'ia'; S.overrides = {};
      status(`Compris par ${body.model} en ${((performance.now() - t0) / 1000).toFixed(1)} s · ${body.usage?.input ?? '?'} jetons lus, ${body.usage?.output ?? '?'} écrits · ${body.remaining} analyses restantes aujourd'hui.`);
    } else {
      S.mode = 'local'; S.localTasks = S.local.tasks;
      status('Analyse locale (sans IA).');
    }
  } catch (e) {
    if (e.noFallback) { $('go').disabled = false; return status(e.message, true); }
    // Mode de secours : le moteur local prend le relais, et on le dit.
    S.mode = 'local'; S.localTasks = S.local.tasks;
    status(`Moteur IA indisponible (${e.message}). Analyse locale utilisée à la place.`, true);
  }
  S.turns = turns;
  $('text').value = '';
  $('text-label').textContent = 'Ajouter, préciser ou corriger';
  $('go').disabled = false;
  render();
};

$('reset').onclick = () => {
  Object.assign(S, { turns: [], understanding: null, ia: null, localTasks: [], local: null, overrides: {}, mode: null, result: null, items: [] });
  $('text-label').textContent = 'Ce que tu veux organiser';
  for (const id of ['summary', 'questions', 'issues', 'constraints', 'tasks', 'plan', 'json', 'cmp-text', 'cmp-ia', 'cmp-local']) $(id).innerHTML = '';
  $('turns-card').hidden = true; $('validate').hidden = true; $('tasks-h').hidden = true; $('constraints-h').hidden = true;
  status('');
};

// ---------- Rendu ----------
async function render() {
  const n = now();
  let items; let constraints; let questions; let summary; let issues = [];
  if (S.mode === 'ia') {
    const p = toPlanInput(S.understanding, n);
    items = p.tasks.map((t) => applyOverride(t, S.overrides[t.id], n));
    ({ constraints, questions, summary } = p);
    issues = S.ia?.issues ?? [];
  } else {
    items = S.localTasks; constraints = S.local.constraints; questions = S.local.questions;
    summary = `Moteur local : ${items.length} tâche${items.length > 1 ? 's' : ''} comprise${items.length > 1 ? 's' : ''}.`;
  }
  S.items = items;
  const events = [...await calendarEvents(), ...constraints.map((c) => ({ date: c.date, start: c.start, end: c.end, title: `Indisponible · ${c.label}` }))];
  S.result = plan({ tasks: items, events, now: n });

  $('turns-card').hidden = false;
  $('turns').innerHTML = S.turns.map((t) => `<li>${esc(t)}</li>`).join('');
  $('summary').textContent = summary || '';
  $('questions').innerHTML = questions.length ? `<div class="box q"><b>À préciser</b><ul>${questions.map((q) => `<li>${esc(q)}</li>`).join('')}</ul></div>` : '';
  $('issues').innerHTML = issues.length ? `<div class="box"><b>Corrections apportées à la réponse de l'IA</b><ul>${issues.map((q) => `<li>${esc(q)}</li>`).join('')}</ul></div>` : '';
  $('constraints-h').hidden = !constraints.length;
  $('constraints').innerHTML = constraints.map((c) => `<li>${esc(fmtDay(c.date, n.date))} · ${fmtTime(c.start)} – ${fmtTime(Math.min(c.end, 1439))} · ${esc(c.label)}</li>`).join('');
  $('tasks-h').hidden = !items.length;
  renderTasks($('tasks'), items, n, onEdit);
  renderPlan($('plan'), S.result);
  $('json').textContent = JSON.stringify(S.mode === 'ia' ? { compréhension: S.understanding, corrections: issues, modèle: S.ia?.model, usage: S.ia?.usage } : S.local, null, 2);
  $('cmp-text').textContent = S.turns.map((t, i) => `${i + 1}. ${t}`).join('\n\n');
  const line = (t) => `<li><b>${esc(t.title)}</b><br><span class="muted">${t.window ? esc(fmtDay(t.window.date, n.date)) : 'jour libre'}${t.deadline ? ` · avant ${esc(fmtDay(t.deadline.date, n.date).toLowerCase())} ${fmtTime(t.deadline.minute)}` : ''} · ${fmtMin(t.minutes)}</span></li>`;
  $('cmp-ia').innerHTML = S.mode === 'ia' ? items.map(line).join('') : '<li class="muted">Pas de réponse IA pour cette conversation.</li>';
  $('cmp-local').innerHTML = S.local.tasks.map(line).join('') || '<li class="muted">Rien compris.</li>';
  $('validate').hidden = !items.length;
  $('accepted').textContent = '';
}

function applyOverride(t, o, n) {
  if (!o) return t;
  const c = { ...t };
  if (o.level) c.level = o.level;
  if (o.time) { const [h, m] = o.time.split(':').map(Number); c.window = { date: t.window?.date ?? n.date, start: h * 60 + m, end: h * 60 + m, fixed: true, label: o.time }; }
  return c;
}

function onEdit(act, id, value) {
  if (S.mode === 'ia') {
    // Durée, jour et suppression modifient la compréhension : la suite de la conversation les conserve.
    if (act === 'minutes' || act === 'day' || act === 'remove') S.understanding = applyItemEdit(S.understanding, id, act, act === 'minutes' ? Number(value) : value || null);
    else S.overrides[id] = { ...S.overrides[id], [act]: value };
  } else S.localTasks = applyEdit(S.localTasks, act, id, value, now());
  render();
}

// ---------- Tabs ----------
for (const b of document.querySelectorAll('[role=tab]')) b.onclick = () => {
  for (const x of document.querySelectorAll('[role=tab]')) x.setAttribute('aria-selected', String(x === b));
  for (const p of document.querySelectorAll('.panel')) p.hidden = p.dataset.panel !== b.dataset.tab;
};

// ---------- Validation ----------
$('accept').onclick = () => {
  const n = now();
  const saved = [];
  for (const t of S.items) {
    const blocks = S.result.days.flatMap((d) => d.blocks.filter((b) => b.kind === 'task' && b.taskId === t.id).map((b) => ({ ...b, date: d.date })));
    const base = { level: t.level, priority: t.priority, project: t.group ?? null, subtasks: t.subtasks ?? [], deadline: t.deadline ? `${t.deadline.date} ${fmtTime(t.deadline.minute)}` : null };
    if (!blocks.length) saved.push({ title: t.title, ...base, start: null, minutes: t.minutes });
    blocks.forEach((b, i) => saved.push({ title: blocks.length > 1 ? `${t.title} (${i + 1}/${blocks.length})` : t.title, ...base, start: `${b.date} ${fmtTime(b.start)}`, minutes: b.end - b.start }));
  }
  store.set('bullylist-console-validated', { at: new Date().toISOString(), ref: n, turns: S.turns, tasks: saved });
  $('accepted').textContent = `${saved.length} tâche${saved.length > 1 ? 's' : ''} validée${saved.length > 1 ? 's' : ''} (comme « Tout accepter » dans l'application).`;
};
