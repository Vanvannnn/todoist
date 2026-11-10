// Tests du cœur partagé. Lancer : npm test (fuseau Europe/Paris imposé par le script).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseDictation } from '../js/core/nlu.js';
import { plan, validatePlan, chunks } from '../js/core/planner.js';
import { parseICS, expand, toLocalEvents } from '../js/core/ics.js';
import { pauseIntervals, deferForPause } from '../js/core/availability.js';
import { estimate, learn } from '../js/core/estimator.js';
import { catalog, candidates, pick, stableId, contextual } from '../js/core/punchlines.js';
import { addDays } from '../js/core/dates.js';

const fx = (name) => JSON.parse(fs.readFileSync(new URL(`../../design/fixtures/${name}`, import.meta.url)));
const week = fx('student-week.json');

test('fuseau de test', () => assert.equal(Intl.DateTimeFormat().resolvedOptions().timeZone, 'Europe/Paris'));

for (const c of fx('nlu.json').cases) {
  test(`dictée : ${c.text.slice(0, 60)}…`, () => {
    const r = parseDictation(c.text, c.now);
    assert.equal(r.intent, c.expect.intent);
    assert.equal(r.questions.length, c.expect.questions);
    assert.deepEqual(r.tasks.map((t) => ({
      title: t.title, deadline: t.deadline && { date: t.deadline.date, minute: t.deadline.minute },
      window: t.window && { date: t.window.date, start: t.window.start, end: t.window.end, fixed: t.window.fixed },
      recurrence: t.recurrence, priority: t.priority, level: t.level, minutes: t.minutes, sessionMinutes: t.sessionMinutes, dependsOn: t.dependsOn,
    })), c.expect.tasks);
  });
}

test('dictée de référence : interprétation attendue', () => {
  const r = parseDictation(week.dictation, week.now);
  const by = (w) => r.tasks.find((t) => t.title.toLowerCase().includes(w));
  assert.equal(r.tasks.length, 5);
  assert.deepEqual(by('marketing').deadline, { date: '2026-11-12', minute: 1080, label: 'jeudi soir', assumed: true });
  assert.equal(by('math').recurrence.type, 'daily');
  assert.equal(by('courses').window.date, '2026-11-10');
  assert.equal(by('expos').level, 'BRR');
  assert.equal(by('expos').window.date, '2026-11-09');
  assert.equal(by('appartement').deadline, null, "pas d'échéance inventée");
  assert.ok(by('expos').notes.some((n) => n.startsWith('Hypothèse')), 'hypothèse signalée');
});

test('planning de la semaine type : tout est placé, aucune règle violée', () => {
  const p = parseDictation(week.dictation, week.now);
  const r = plan({ tasks: p.tasks, events: week.events, now: week.now });
  assert.deepEqual(validatePlan(r, { tasks: p.tasks, now: week.now }), []);
  assert.equal(r.unplaced.length, 0);
  const today = r.days[0].blocks.filter((b) => b.kind === 'task' && b.title.includes('exposé'));
  assert.ok(today.length >= 1 && today.every((b) => b.start >= 18 * 60), "l'exposé est fait ce soir");
  for (const d of r.days) assert.ok(d.free >= 60, `${d.date} garde du temps libre`);
});

test('planning impossible : signalé avec des alternatives', () => {
  const p = parseDictation('Je dois rédiger mon mémoire pour demain matin, ça va me prendre 10 heures.', week.now);
  const r = plan({ tasks: p.tasks, events: week.events, now: week.now });
  assert.equal(r.feasible, false);
  assert.ok(r.unplaced[0].alternatives.length >= 2);
  assert.deepEqual(validatePlan(r, { tasks: p.tasks, now: week.now }), []);
});

test('dépendance : la présentation après les recherches', () => {
  const p = parseDictation('Il faudrait que je prépare la présentation pour jeudi après-midi, après avoir fini les recherches. Je dois aussi faire les recherches.', week.now);
  const r = plan({ tasks: p.tasks, events: week.events, now: week.now });
  const flat = r.days.flatMap((d) => d.blocks.filter((b) => b.kind === 'task').map((b) => ({ ...b, key: d.date + String(b.start).padStart(4, '0') })));
  const lastResearch = flat.filter((b) => b.title.includes('recherches')).map((b) => b.key).sort().pop();
  const firstPres = flat.filter((b) => b.title.includes('présentation')).map((b) => b.key).sort()[0];
  assert.ok(lastResearch < firstPres);
});

test('horaire imposé respecté', () => {
  const p = parseDictation('Je dois appeler le dentiste demain à 10h30.', { date: '2026-11-11', minute: 600 });
  const r = plan({ tasks: p.tasks, events: [], now: { date: '2026-11-11', minute: 600 } });
  const b = r.days.find((d) => d.date === '2026-11-12').blocks.find((x) => x.kind === 'task');
  assert.equal(b.start, 630);
});

test('propriété : 300 semaines aléatoires respectent toutes les règles', () => {
  let seed = 42; const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
  const now = { date: '2026-11-09', minute: 540 };
  for (let k = 0; k < 300; k++) {
    const events = [];
    for (let d = 0; d < 7; d++) for (let i = 0; i < Math.floor(rnd() * 4); i++) {
      const start = 480 + Math.floor(rnd() * 24) * 30; events.push({ date: addDays(now.date, d), start, end: Math.min(1430, start + 60 + Math.floor(rnd() * 4) * 30), title: 'Cours', location: rnd() < 0.5 ? 'Salle' : null });
    }
    const tasks = Array.from({ length: 1 + Math.floor(rnd() * 8) }, (_, i) => ({
      id: `t${i}`, title: `Tâche ${i}`, minutes: 15 + Math.floor(rnd() * 30) * 10, priority: ['LOW', 'NORMAL', 'HIGH', 'URGENT'][Math.floor(rnd() * 4)],
      deadline: rnd() < 0.5 ? { date: addDays(now.date, Math.floor(rnd() * 7)), minute: 480 + Math.floor(rnd() * 28) * 30 } : null,
      window: null, recurrence: rnd() < 0.15 ? { type: 'daily', part: null } : null, sessionMinutes: 30, dependsOn: rnd() < 0.2 && i ? [`t${i - 1}`] : [],
      horizon: { from: now.date, until: addDays(now.date, 6) },
    }));
    const r = plan({ tasks, events, now });
    assert.deepEqual(validatePlan(r, { tasks, now }), [], `semaine ${k}`);
  }
});

test('découpage en séances', () => {
  assert.deepEqual(chunks(110, 90, 25), [55, 55]);
  assert.deepEqual(chunks(60, 90, 25), [60]);
  assert.ok(chunks(300, 90, 25).every((c) => c <= 90 && c >= 25));
});

for (const c of fx('ics.json').cases) {
  test(`ICS : ${c.name}`, () => {
    const parsed = parseICS(c.ics);
    assert.equal(parsed.calName, c.calName);
    const occ = expand(parsed, Date.parse(c.from), Date.parse(c.to));
    assert.deepEqual(occ.map((o) => ({ title: o.title, start: new Date(o.start).toISOString().replace('.000', ''), end: new Date(o.end).toISOString().replace('.000', ''), location: o.location, allDay: o.allDay })), c.expect);
  });
}

test('ICS → événements locaux (journées entières ignorées)', () => {
  const c = fx('ics.json').cases[0];
  const ev = toLocalEvents(expand(parseICS(c.ics), Date.parse(c.from), Date.parse(c.to)), { profile: 'COURS' });
  assert.equal(ev.length, 7);
  assert.deepEqual(ev[0], { date: '2026-11-09', start: 480, end: 600, title: 'Analyse, CM', location: 'Amphi A', profile: 'COURS', travelBefore: undefined, travelAfter: undefined });
});

test('mode pause : reports attendus', () => {
  const p = fx('pause.json'); const M = 60000;
  const iv = pauseIntervals(p.events.map((e) => ({ ...e, start: e.start * M, end: e.end * M })));
  for (const c of p.cases) assert.equal(deferForPause(c.at * M, iv, { level: c.level, bypassPause: c.bypass }).at / M, c.expect, c.why);
});

test('estimation : défaut, verbe, apprentissage', () => {
  assert.deepEqual([estimate('envoyer un mail').min, estimate('envoyer un mail').max], [5, 15]);
  assert.ok(estimate('terminer mon expose').minutes < estimate('preparer mon expose').minutes);
  let l = {};
  for (let i = 0; i < 5; i++) l = learn(l, 'faire les courses', 30);
  const e = estimate('faire les courses', l);
  assert.equal(e.source, 'appris');
  assert.ok(e.minutes < estimate('faire les courses').minutes);
});

const data = JSON.parse(fs.readFileSync(new URL('../assets/data/punchlines.json', import.meta.url)));
const pool = catalog(data);

test('punchlines : volume, unicité, catégories', () => {
  assert.ok(pool.length >= 450, `${pool.length}`);
  assert.equal(new Set(pool.map((p) => p.id)).size, pool.length);
  assert.equal(new Set(pool.map((p) => p.text)).size, pool.length);
  for (const c of data.categories) assert.ok(data.lines[c.id].length >= 10, c.id);
  assert.ok(!JSON.stringify(data).includes('B.R.R'));
});

test('punchlines : catégories désactivées et plafond d\'humour respectés', () => {
  const off = data.categories.map((c) => c.id).filter((id) => id !== 'SAV');
  const c = candidates(pool, { level: 'BRR', disabledCategories: off, maxIntensity: 2 });
  assert.ok(c.every((p) => p.category === 'SAV' && p.intensity <= 2));
});

test('punchlines : identifiants identiques à Kotlin (hashCode Java)', () => {
  for (const { text, id } of fx('punchline-ids.json')) assert.equal(stableId(text), id);
});

test('punchlines contextuelles : citent la tâche', () => {
  let i = 0; const seq = [0.1, 0.5, 0.9];
  const txt = contextual(data, 'Sortir les poubelles', 47, 0, () => seq[i++ % 3]);
  assert.ok(txt.length > 10);
  const p = pick(data, pool, { level: 'BRR', taskTitle: 'Réviser les maths', recentIds: [] }, () => 0.01);
  assert.equal(p.id, null);
});
