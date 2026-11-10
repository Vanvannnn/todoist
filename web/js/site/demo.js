// Démo des quatre niveaux : tâche, retard et variante réglables ; punchlines tirées du vrai catalogue.
import { showAlert, feedback, stopFeedback, VARIANTS } from '../ui/alerts.js';
import { loadPunchlines } from './common.js';
import { catalog, pick, pushRecent, LEVELS } from '../core/punchlines.js';

const data = await loadPunchlines();
const pool = catalog(data);
const $ = (id) => document.getElementById(id);
const screen = $('d-screen');
let level = Number(location.hash.slice(1)) || 4;
let variant = null; let sound = false; let recent = []; let message = ''; let messages = [];

const VAR_NAMES = { mission: 'Mission', radar: 'Radar', controle: 'Contrôle', nationale: 'Alerte nationale', defcon: 'DEFCON', flash: 'Flash spécial' };

function draw() {
  const task = $('d-task').value.trim() || 'Sortir les poubelles';
  const late = Number($('d-late').value);
  const req = { level: LEVELS[level - 1], taskTitle: task, minutesLate: late, snoozeCount: 2, recentIds: recent, isFinal: false };
  messages = [0, 1, 2].map(() => { const p = pick(data, pool, req); recent = pushRecent(recent, p.id); return p.text; });
  message = messages[0];
  fire();
}

function fire() {
  const task = $('d-task').value.trim() || 'Sortir les poubelles';
  showAlert(screen, {
    level, variant, task, message, messages, minutesLate: Number($('d-late').value), snoozeCount: 2, nextInSec: 300, seed: task,
    onAction: (a) => { stopFeedback(); $('d-status').textContent = { done: 'Tâche terminée : toutes ses alarmes sont annulées.', snooze5: 'Reporté de 5 minutes.', snooze15: 'Reporté de 15 minutes.', snooze60: 'Reporté d\'une heure.', tomorrow: 'Reporté à demain.', open: 'La tâche s\'ouvrirait dans l\'application.' }[a] ?? ''; },
  });
  $('d-status').textContent = sound ? feedback(level, { mode: 'sound', dnd: false }).join(' · ') : '';
}

function syncTabs() {
  document.querySelectorAll('.lvl-tabs button').forEach((b) => b.setAttribute('aria-pressed', String(Number(b.dataset.level) === level)));
  const vs = VARIANTS[level];
  $('d-var-field').hidden = !vs;
  $('d-var').innerHTML = vs ? [null, ...vs].map((v) => `<button data-v="${v ?? ''}" aria-pressed="${v === variant}">${v ? VAR_NAMES[v] : 'Au hasard'}</button>`).join('') : '';
}

document.querySelector('.lvl-tabs').addEventListener('click', (e) => {
  const b = e.target.closest('button'); if (!b) return;
  level = Number(b.dataset.level); variant = null; history.replaceState(null, '', `#${level}`); syncTabs(); draw();
});
$('d-var').addEventListener('click', (e) => {
  const b = e.target.closest('button'); if (!b) return;
  variant = b.dataset.v || null; syncTabs(); fire();
});
$('d-sound').addEventListener('click', (e) => {
  const b = e.target.closest('button'); if (!b) return;
  sound = b.dataset.v === 'on';
  document.querySelectorAll('#d-sound button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
  if (!sound) stopFeedback();
});
$('d-late').addEventListener('input', () => { $('d-late-v').textContent = $('d-late').value; });
$('d-late').addEventListener('change', fire);
$('d-task').addEventListener('change', draw);
$('d-fire').addEventListener('click', fire);
$('d-new').addEventListener('click', draw);
syncTabs(); draw();
