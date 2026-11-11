// Assistant vocal V2.1 : compréhension conversationnelle locale, contrat IA (validation + conversion), planificateur.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseDictation } from '../js/core/nlu.js';
import { plan, validatePlan } from '../js/core/planner.js';
import { validateUnderstanding, toPlanInput, applyItemEdit, calendarContext, SCHEMA } from '../js/core/understanding.js';
import { runLocal, check } from './assistant-eval.mjs';
import { snapshot } from '../tools/gen-nlu-parity.mjs';

const FIX = JSON.parse(fs.readFileSync(new URL('../../design/fixtures/assistant-scenarios.json', import.meta.url)));
const now = FIX.now; // lundi 9 novembre 2026, 10h00

// Échecs connus du moteur local (relèvent du moteur IA). Si l'un d'eux se met à réussir, retirer-le de la liste.
const LOCAL_KNOWN_FAILURES = [7, 28];
for (const s of FIX.scenarios) {
  test(`scénario ${s.id} (${s.category}) — moteur local`, async () => {
    const errs = check(s.expect, await runLocal(s, now));
    if (LOCAL_KNOWN_FAILURES.includes(s.id)) assert.ok(errs.length > 0, 'réussit désormais : retirer de LOCAL_KNOWN_FAILURES');
    else assert.deepEqual(errs, []);
  });
}

test('parité Kotlin : sorties locales figées des scénarios', () => {
  const fx = JSON.parse(fs.readFileSync(new URL('../../design/fixtures/nlu-conversation.json', import.meta.url)));
  for (const c of fx.cases) assert.deepEqual(snapshot(parseDictation(c.text, fx.now)), c.expect, c.text.slice(0, 60));
});

test('dictée en plusieurs jours : rien ne tombe aujourd’hui ni demain par défaut', () => {
  const r = parseDictation("Mercredi, je voudrais réviser les maths. Je pense qu'une heure et demie devrait suffire. Jeudi, j'ai envie de ranger ma chambre.", now);
  assert.deepEqual(r.tasks.map((t) => [t.title, t.window?.date, t.minutes]), [['Réviser les maths', '2026-11-11', 90], ['Ranger ma chambre', '2026-11-12', 45]]);
});

test('jour de semaine égal à aujourd’hui : hypothèse affichée', () => {
  const r = parseDictation('Lundi, je dois appeler la banque.', now);
  assert.equal(r.tasks[0].window.date, '2026-11-09');
  assert.ok(r.tasks[0].notes.some((n) => n.includes('compris comme aujourd')));
});

// ---------- Contrat IA ----------
const T = (over = {}) => ({
  id: 't1', title: 'Faire les courses', description: '', planned_day: null, part_of_day: null, start_time: null, deadline: null,
  duration_minutes: null, duration_source: 'unknown', priority: 'normal', session_days: [], session_minutes: null, subtasks: [],
  depends_on: [], project: null, source_quote: '', confidence: 'high', ...over,
});
const U = (tasks, extra = {}) => ({ tasks, constraints: [], questions: [], summary: '', ...extra });

test('schéma : tous les objets fermés et toutes les propriétés requises (exigence des sorties structurées)', () => {
  const walk = (s) => {
    if (s.anyOf) return s.anyOf.forEach(walk);
    if (s.type === 'object') {
      assert.equal(s.additionalProperties, false);
      assert.deepEqual([...s.required].sort(), Object.keys(s.properties).sort());
      Object.values(s.properties).forEach(walk);
    }
    if (s.type === 'array') walk(s.items);
  };
  walk(SCHEMA);
});

test('calendrier fourni au modèle : jours exacts', () => {
  const c = calendarContext(now, 8).split('\n');
  assert.equal(c[0], "lundi 2026-11-09 (aujourd'hui)");
  assert.equal(c[7], 'lundi 2026-11-16');
});

test('validation : jour de semaine mal calculé par le modèle → corrigé et signalé', () => {
  const v = validateUnderstanding(U([T({ planned_day: { date: '2026-11-11', said: 'jeudi' } })]), now);
  assert.equal(v.value.tasks[0].planned_day.date, '2026-11-12');
  assert.ok(v.issues[0].startsWith('Date corrigée'));
});

test('validation : date passée → question, pas de déplacement silencieux', () => {
  const v = validateUnderstanding(U([T({ deadline: { date: '2026-11-03', time: null, said: 'le 3 novembre' } })]), now);
  assert.equal(v.value.tasks[0].deadline, null);
  assert.equal(v.value.questions.length, 1);
});

test('validation : durées absurdes, doublons, dépendances inconnues et cycles retirés', () => {
  const v = validateUnderstanding(U([
    T({ id: 'a', title: 'Réviser', duration_minutes: 5000, depends_on: ['b', 'zzz', 'a'] }),
    T({ id: 'b', title: 'Lire', depends_on: ['a'] }),
    T({ id: 'c', title: 'réviser ', depends_on: [] }),
  ]), now);
  assert.equal(v.value.tasks.length, 2, 'doublon fusionné');
  const [a, b] = v.value.tasks;
  assert.equal(a.duration_minutes, null);
  assert.ok(!(a.depends_on.includes('b') && b.depends_on.includes('a')), 'cycle rompu');
  assert.ok(!a.depends_on.includes('zzz') && !a.depends_on.includes('a'));
});

test('validation : sortie illisible refusée', () => {
  assert.equal(validateUnderstanding({ foo: 1 }, now).ok, false);
  assert.equal(validateUnderstanding(null, now).ok, false);
});

test('conversion : tâche composée → séances datées + liste à cocher, dépendances et échéance conservées', () => {
  const u = validateUnderstanding(U([T({
    id: 't1', title: 'Préparer le dossier marketing', priority: 'high',
    deadline: { date: '2026-11-13', time: '18:00', said: 'avant vendredi soir' },
    subtasks: [
      { id: 't1.1', title: 'Faire les recherches', planned_day: { date: '2026-11-10', said: 'mardi' }, duration_minutes: 60, duration_source: 'said', depends_on: [], confidence: 'high' },
      { id: 't1.2', title: 'Rédiger le dossier', planned_day: { date: '2026-11-12', said: 'jeudi' }, duration_minutes: 180, duration_source: 'estimated', depends_on: ['t1.1'], confidence: 'high' },
      { id: 't1.3', title: 'Envoyer le mail au professeur', planned_day: null, duration_minutes: null, duration_source: 'unknown', depends_on: [], confidence: 'high' },
    ],
  })]), now).value;
  const p = toPlanInput(u, now);
  assert.deepEqual(p.tasks.map((t) => [t.id, t.title, t.window?.date ?? null, t.minutes, t.group]), [
    ['t1/t1.1', 'Faire les recherches', '2026-11-10', 60, 'Préparer le dossier marketing'],
    ['t1/t1.2', 'Rédiger le dossier', '2026-11-12', 180, 'Préparer le dossier marketing'],
    ['t1', 'Préparer le dossier marketing', null, p.tasks[2].minutes, 'Préparer le dossier marketing'],
  ]);
  assert.deepEqual(p.tasks[1].dependsOn, ['t1/t1.1']);
  assert.deepEqual(p.tasks[2].subtasks, ['Envoyer le mail au professeur']);
  assert.ok(p.tasks.every((t) => t.deadline.date === '2026-11-13' && t.deadline.minute === 1080));
  // Le planning respecte les jours dits et toutes les règles.
  const r = plan({ tasks: p.tasks, events: p.constraints, now });
  assert.deepEqual(validatePlan(r, { tasks: p.tasks, now }), []);
  const dayOf = (id) => r.days.find((d) => d.blocks.some((b) => b.taskId === id))?.date;
  assert.equal(dayOf('t1/t1.1'), '2026-11-10');
  assert.equal(dayOf('t1/t1.2'), '2026-11-12');
});

test('conversion : jours choisis (« sauf mercredi ») et indisponibilité respectés par le planning', () => {
  const u = validateUnderstanding(U([T({ title: 'Réviser les maths', session_days: ['2026-11-10', '2026-11-12'], session_minutes: 45 })],
    { constraints: [{ date: '2026-11-10', start_time: '08:00', end_time: '24:00', reason: 'Cours', said: 'mardi je suis pris' }] }), now).value;
  const p = toPlanInput(u, now);
  const r = plan({ tasks: p.tasks, events: p.constraints, now });
  const days = r.days.filter((d) => d.blocks.some((b) => b.kind === 'task')).map((d) => d.date);
  assert.deepEqual(days, ['2026-11-12'], 'mardi est entièrement pris : séance non placée, mercredi exclu');
  assert.equal(r.unplaced.length, 1);
});

test('retouches : modifient la compréhension (contexte de la conversation)', () => {
  const u = validateUnderstanding(U([T({ subtasks: [{ id: 't1.1', title: 'Pain', planned_day: { date: '2026-11-10', said: 'mardi' }, duration_minutes: 10, duration_source: 'said', depends_on: [], confidence: 'high' }] })]), now).value;
  let v = applyItemEdit(u, 't1/t1.1', 'day', '2026-11-14');
  assert.equal(v.tasks[0].subtasks[0].planned_day.date, '2026-11-14');
  v = applyItemEdit(v, 't1/t1.1', 'minutes', 25);
  assert.equal(v.tasks[0].subtasks[0].duration_minutes, 25);
  v = applyItemEdit(v, 't1', 'remove');
  assert.equal(v.tasks.length, 1, 'la tâche garde sa séance datée');
  v = applyItemEdit(v, 't1/t1.1', 'remove');
  v = applyItemEdit(v, 't1', 'remove');
  assert.equal(v.tasks.length, 0);
  assert.equal(u.tasks[0].subtasks[0].planned_day.date, '2026-11-10', 'original intact');
});

test('planificateur : une tâche datée au-delà de la semaine est placée ce jour-là', () => {
  const r = parseDictation('Le 25 novembre, je dois réviser le partiel.', now);
  const p = plan({ tasks: r.tasks, now });
  assert.ok(p.days.find((d) => d.date === '2026-11-25')?.blocks.some((b) => b.kind === 'task'));
});
