// Héros : la tâche en retard escalade du niveau 1 au niveau 4, puis l'alerte prend le contrôle de la page.
import { showAlert, stopFeedback } from '../ui/alerts.js';
import { loadPunchlines } from './common.js';
import { catalog, candidates } from '../core/punchlines.js';

const hero = document.getElementById('hero');
const screen = document.getElementById('hero-screen');
const status = document.getElementById('hero-status');
const item = document.querySelector('#hero-list [data-id="poubelles"]');
const TASK = 'Sortir les poubelles';
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
let timers = []; let alert = null;

const data = await loadPunchlines();
const pool = catalog(data);
const line = (level) => {
  const l = candidates(pool, { level: ['NORMAL', 'INSISTANT', 'HARCELEMENT', 'BRR'][level - 1] });
  return l[Math.floor(Math.random() * l.length)].text;
};
const MESSAGES = {
  1: "Les poubelles t'attendent. Le camion, lui, n'attendra pas.",
  2: 'Cela fait maintenant 47 minutes que tu négocies avec un sac-poubelle.',
  3: 'Le sac-poubelle a commencé à développer une conscience.',
  4: "À ce stade, le sac-poubelle a plus de projets d'avenir que toi.",
};
const STEPS = [
  { level: 1, at: 1800, late: 0, say: 'Niveau 1 · Normal' },
  { level: 2, at: 5200, late: 15, say: 'Niveau 2 · Insistant — ignoré depuis 15 minutes' },
  { level: 3, at: 9200, late: 32, say: 'Niveau 3 · Bully Mode' },
  { level: 4, at: 14500, late: 47, say: 'Niveau 4 · Bully Mode: Nuclear' },
];

function reset(message = '') {
  timers.forEach(clearTimeout); timers = [];
  alert?.close(); alert = null;
  stopFeedback();
  hero.classList.remove('takeover');
  status.textContent = message;
}

function show(step) {
  alert?.close();
  alert = showAlert(screen, {
    level: step.level, task: TASK, message: MESSAGES[step.level], messages: [MESSAGES[step.level], line(step.level), line(step.level)],
    minutesLate: step.late, snoozeCount: 2, nextInSec: 300, seed: 428,
    onAction: (a) => {
      timers.forEach(clearTimeout); timers = [];
      hero.classList.remove('takeover');
      if (a === 'done') { item.classList.add('done'); item.classList.remove('hot'); status.textContent = 'Mission accomplie. Les poubelles sont sorties.'; }
      else if (a.startsWith('snooze')) status.textContent = 'Reporté de 5 minutes. Bullylist reviendra.';
      else if (a === 'tomorrow') status.textContent = 'Reporté à demain. Le sac-poubelle reste sur place.';
      else status.textContent = 'Tâche ouverte.';
      alert = null;
    },
  });
  status.textContent = step.say;
  if (step.level === 4 && !reduced) hero.classList.add('takeover');
}

function play() {
  reset();
  item.classList.remove('done'); item.classList.add('hot');
  for (const s of STEPS) timers.push(setTimeout(() => show(s), s.at));
}

document.getElementById('hero-play').addEventListener('click', play);
document.getElementById('hero-reset').addEventListener('click', () => reset('Téléphone au calme.'));
// L'escalade automatique ne démarre que si l'utilisateur n'a pas demandé à réduire les animations.
if (!reduced) play();
else status.textContent = "Animations réduites : lance l'escalade quand tu veux.";
