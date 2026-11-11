// Fige les sorties du moteur local sur la banque de scénarios : design/fixtures/nlu-conversation.json.
// Le test JS et le test Kotlin (VoiceParityTest) comparent leurs sorties à ce fichier.
//   node tools/gen-nlu-parity.mjs   (à relancer après une évolution volontaire de nlu.js, puis porter en Kotlin)
import fs from 'node:fs';
import { parseDictation } from '../js/core/nlu.js';

/** Forme comparée entre JS et Kotlin. */
export function snapshot(r) {
  return {
    tasks: r.tasks.map((t) => ({
      title: t.title, window: t.window && { date: t.window.date, start: t.window.start, end: t.window.end, fixed: t.window.fixed },
      deadline: t.deadline && { date: t.deadline.date, minute: t.deadline.minute }, minutes: t.minutes,
      recurrence: t.recurrence && { type: t.recurrence.type, n: t.recurrence.n ?? 0, dates: t.recurrence.dates ?? [] },
      dependsOn: t.dependsOn, priority: t.priority,
    })),
    constraints: r.constraints, questions: r.questions.length,
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const fx = JSON.parse(fs.readFileSync(new URL('../../design/fixtures/assistant-scenarios.json', import.meta.url)));
  const cases = fx.scenarios.map((s) => {
    const text = s.turns.join(' ');
    return { text, expect: snapshot(parseDictation(text, fx.now)) };
  });
  const out = { $comment: 'Généré par web/tools/gen-nlu-parity.mjs — sorties du moteur local (JS = Kotlin).', now: fx.now, cases };
  fs.writeFileSync(new URL('../../design/fixtures/nlu-conversation.json', import.meta.url), JSON.stringify(out, null, 1) + '\n');
  console.log(`${cases.length} cas écrits.`);
}

// ---------- Contrat IA : conversion compréhension → séances (understanding.js = Understanding.kt) ----------
import { validateUnderstanding, toPlanInput } from '../js/core/understanding.js';

const T = (o) => ({ description: '', planned_day: null, part_of_day: null, start_time: null, deadline: null, duration_minutes: null,
  duration_source: 'unknown', priority: 'normal', session_days: [], session_minutes: null, subtasks: [], depends_on: [], project: null,
  source_quote: '', confidence: 'high', ...o });
const S = (o) => ({ planned_day: null, duration_minutes: null, duration_source: 'unknown', depends_on: [], confidence: 'high', ...o });
export const UNDERSTANDING_CASES = [
  { tasks: [
    T({ id: 't1', title: 'Préparer le dossier marketing', priority: 'high', deadline: { date: '2026-11-13', time: '18:00', said: 'avant vendredi soir' },
      subtasks: [S({ id: 't1.1', title: 'Faire les recherches', planned_day: { date: '2026-11-10', said: 'mardi' }, duration_minutes: 60, duration_source: 'said' }),
        S({ id: 't1.2', title: 'Rédiger le dossier', planned_day: { date: '2026-11-12', said: 'jeudi' }, duration_minutes: 180, duration_source: 'estimated', depends_on: ['t1.1'] }),
        S({ id: 't1.3', title: 'Envoyer le mail au professeur' })] }),
    T({ id: 't2', title: 'Réviser les maths', session_days: ['2026-11-10', '2026-11-12', '2026-11-13'], session_minutes: 45, part_of_day: 'soir' }),
    T({ id: 't3', title: 'Appeler la banque', planned_day: { date: '2026-11-10', said: 'demain' }, start_time: '09:00', priority: 'urgent', depends_on: ['t1'] }),
    T({ id: 't4', title: 'Lire un livre', priority: 'low', duration_minutes: 40, duration_source: 'said' }),
  ], constraints: [{ date: '2026-11-11', start_time: '18:00', end_time: '24:00', reason: 'Pas après 18 h', said: 'rien après 18 heures' }], questions: ['Jeudi prochain ?'], summary: 'ok' },
  { tasks: [
    T({ id: 't1', title: 'Préparer l’exposé', planned_day: { date: '2026-11-10', said: 'mardi' }, project: 'Exposé',
      subtasks: [S({ id: 't1.1', title: 'Faire les recherches', duration_minutes: 60, duration_source: 'said' }), S({ id: 't1.2', title: 'Trouver des images' }),
        S({ id: 't1.3', title: 'Préparer les diapositives', duration_minutes: 120, duration_source: 'said', depends_on: ['t1.1'] })] }),
  ], constraints: [], questions: [], summary: '' },
];

export function planSnapshot(p) {
  return {
    tasks: p.tasks.map((t) => ({
      id: t.id, title: t.title, group: t.group, subtasks: t.subtasks, minutes: t.minutes, priority: t.priority, level: t.level, dependsOn: t.dependsOn,
      window: t.window && { date: t.window.date, start: t.window.start, end: t.window.end, fixed: t.window.fixed },
      deadline: t.deadline && { date: t.deadline.date, minute: t.deadline.minute },
      recurrence: t.recurrence && { type: t.recurrence.type, dates: t.recurrence.dates }, sessionMinutes: t.sessionMinutes, horizonUntil: t.horizon.until,
    })),
    constraints: p.constraints,
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const now = { date: '2026-11-09', minute: 600 };
  const cases = UNDERSTANDING_CASES.map((u) => ({ understanding: u, expect: planSnapshot(toPlanInput(validateUnderstanding(u, now).value, now)) }));
  fs.writeFileSync(new URL('../../design/fixtures/understanding-parity.json', import.meta.url),
    JSON.stringify({ $comment: 'Généré par web/tools/gen-nlu-parity.mjs — conversion compréhension IA → séances (JS = Kotlin).', now, cases }, null, 1) + '\n');
  console.log(`${cases.length} cas de compréhension écrits.`);
}
