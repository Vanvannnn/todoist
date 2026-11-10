// Page Assistant : exemple animé (dictée → tâches → planning) et essai libre au clavier ou au micro.
import { parseDictation } from '../core/nlu.js';
import { plan } from '../core/planner.js';
import { renderTasks, renderPlan } from '../ui/plan-view.js';
import { createDictation, speechSupported } from '../ui/voice.js';

const $ = (id) => document.getElementById(id);
const week = await (await fetch('assets/data/student-week.json')).json();
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
let timer = 0;

function replay() {
  clearInterval(timer);
  const text = week.dictation;
  const result = parseDictation(text, week.now);
  $('a-tasks').innerHTML = ''; $('a-plan').innerHTML = '<p class="muted">Le planning apparaît une fois la dictée terminée.</p>';
  let i = 0; let shown = 0;
  const finish = () => {
    $('a-transcript').textContent = text;
    renderTasks($('a-tasks'), result.tasks, week.now);
    renderPlan($('a-plan'), plan({ tasks: result.tasks, events: week.events, now: week.now }));
  };
  if (reduced) { finish(); return; }
  timer = setInterval(() => {
    i = Math.min(text.length, i + 3);
    $('a-transcript').textContent = text.slice(0, i);
    // Les tâches apparaissent au fil de la phrase, quand leur mention a été prononcée.
    const partial = parseDictation(text.slice(0, i), week.now).tasks.length;
    if (partial > shown) { shown = partial; renderTasks($('a-tasks'), result.tasks.slice(0, shown), week.now); }
    if (i >= text.length) { clearInterval(timer); setTimeout(finish, 500); }
  }, 30);
}

function run() {
  const r = parseDictation($('a-input').value, week.now);
  renderTasks($('a-tasks2'), r.tasks, week.now);
  if (r.questions.length) $('a-tasks2').insertAdjacentHTML('beforeend', `<li class="plan-warn">${r.questions.join('<br>')}</li>`);
  if (!r.tasks.length) { $('a-plan2').innerHTML = '<p class="muted">Aucune tâche reconnue. Essaie une phrase comme « Je dois faire les courses demain après-midi ».</p>'; return; }
  renderPlan($('a-plan2'), plan({ tasks: r.tasks, events: week.events, now: week.now }));
}

const mic = $('a-mic');
if (!speechSupported()) { mic.disabled = true; mic.textContent = 'Micro non pris en charge par ce navigateur'; }
const dict = createDictation({
  onText: (fin, interim) => { $('a-input').value = (fin + interim).trim(); },
  onState: (s) => {
    mic.setAttribute('aria-pressed', String(s === 'listening'));
    mic.textContent = s === 'listening' ? 'Mettre en pause' : s === 'denied' ? 'Micro refusé' : 'Dicter au micro';
    if (s === 'paused') run();
  },
});
mic.addEventListener('click', () => {
  if (mic.getAttribute('aria-pressed') === 'true') return dict.pause();
  if (!confirm('La dictée dans le navigateur envoie le son du micro au service de reconnaissance vocale de Google (Chrome). Rien n\'est conservé par Bullylist. Continuer ?')) return;
  dict.start($('a-input').value);
});
$('a-run').addEventListener('click', run);
$('a-replay').addEventListener('click', replay);
replay(); run();
