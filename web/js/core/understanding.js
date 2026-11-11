// Contrat entre le moteur de compréhension IA et Bullylist.
// 1. SCHEMA : format JSON imposé au modèle (sorties structurées).
// 2. validateUnderstanding : la sortie du modèle n'est jamais crue sur parole (types, dates, jours de semaine, cycles, doublons).
// 3. toPlanInput : conversion vers les tâches du planificateur (même forme que parseDictation) + indisponibilités.
// 4. applyItemEdit : les retouches de l'utilisateur modifient la compréhension, qui reste la référence de la conversation.
// Porté en Kotlin : android/.../domain/voice/Understanding.kt.
import { addDays, isoDay, daysBetween, DAY_NAMES, fmtDay } from './dates.js';
import { norm, PARTS } from './nlu.js';
import { estimate } from './estimator.js';

const nullable = (s) => ({ anyOf: [s, { type: 'null' }] });
const DATE = { type: 'string', format: 'date', description: 'AAAA-MM-JJ, calculée avec le calendrier fourni' };
const TIME = { type: 'string', description: 'HH:MM sur 24 h' };
const DAY_REF = {
  type: 'object', additionalProperties: false, required: ['date', 'said'],
  properties: { date: DATE, said: { type: 'string', description: 'Mots exacts prononcés : « mardi », « jeudi prochain », « le 18 novembre »' } },
};
const CONFIDENCE = { type: 'string', enum: ['high', 'medium', 'low'] };
const DURATION_SOURCE = { type: 'string', enum: ['said', 'estimated', 'unknown'] };

const SUBTASK = {
  type: 'object', additionalProperties: false,
  required: ['id', 'title', 'planned_day', 'duration_minutes', 'duration_source', 'depends_on', 'confidence'],
  properties: {
    id: { type: 'string', description: 'Identifiant stable : id de la tâche + « . » + numéro (t1.1). Conserver les ids existants.' },
    title: { type: 'string', description: 'Verbe à l’infinitif + complément, court (« Faire les recherches »)' },
    planned_day: nullable(DAY_REF),
    duration_minutes: nullable({ type: 'integer' }),
    duration_source: DURATION_SOURCE,
    depends_on: { type: 'array', items: { type: 'string' }, description: 'Ids (tâches ou sous-tâches) qui doivent être faits avant' },
    confidence: CONFIDENCE,
  },
};

const TASK = {
  type: 'object', additionalProperties: false,
  required: ['id', 'title', 'description', 'planned_day', 'part_of_day', 'start_time', 'deadline', 'duration_minutes', 'duration_source',
    'priority', 'session_days', 'session_minutes', 'subtasks', 'depends_on', 'project', 'source_quote', 'confidence'],
  properties: {
    id: { type: 'string', description: 'Identifiant stable (t1, t2…). Conserver les ids de la compréhension précédente.' },
    title: { type: 'string' },
    description: { type: 'string', description: 'Précisions utiles prononcées, sinon chaîne vide' },
    planned_day: { ...nullable(DAY_REF), description: 'Jour où l’utilisateur veut TRAVAILLER sur la tâche. null si aucun jour n’a été dit.' },
    part_of_day: nullable({ type: 'string', enum: ['matin', 'apres-midi', 'soir'] }),
    start_time: { ...nullable(TIME), description: 'Heure de début imposée par l’utilisateur, sinon null' },
    deadline: nullable({
      type: 'object', additionalProperties: false, required: ['date', 'time', 'said'],
      properties: { date: DATE, time: nullable(TIME), said: { type: 'string' } },
      description: 'Échéance : moment où la tâche doit être TERMINÉE. Jamais confondue avec planned_day.',
    }),
    duration_minutes: { ...nullable({ type: 'integer' }), description: 'Durée totale de travail. Dernière valeur dite en cas de correction.' },
    duration_source: DURATION_SOURCE,
    priority: { type: 'string', enum: ['low', 'normal', 'high', 'urgent'] },
    session_days: { type: 'array', items: DATE, description: 'Tâche répétée sur plusieurs jours (« un peu chaque jour sauf mercredi ») : un élément par jour. Sinon [].' },
    session_minutes: { ...nullable({ type: 'integer' }), description: 'Durée d’une séance quand session_days est rempli' },
    subtasks: { type: 'array', items: SUBTASK },
    depends_on: { type: 'array', items: { type: 'string' } },
    project: { ...nullable({ type: 'string' }), description: 'Projet ou matière qui regroupe plusieurs tâches (« Dossier marketing »)' },
    source_quote: { type: 'string', description: 'Extrait de la transcription d’où vient la tâche' },
    confidence: CONFIDENCE,
  },
};

export const SCHEMA = {
  type: 'object', additionalProperties: false, required: ['tasks', 'constraints', 'questions', 'summary'],
  properties: {
    tasks: { type: 'array', items: TASK },
    constraints: {
      type: 'array', description: 'Indisponibilités : cours, rendez-vous, « rien après 18 heures », « pas disponible mercredi après-midi »',
      items: {
        type: 'object', additionalProperties: false, required: ['date', 'start_time', 'end_time', 'reason', 'said'],
        properties: { date: DATE, start_time: TIME, end_time: TIME, reason: { type: 'string' }, said: { type: 'string' } },
      },
    },
    questions: { type: 'array', items: { type: 'string' }, description: 'Questions courtes quand un point est vraiment ambigu' },
    summary: { type: 'string', description: 'Une ou deux phrases en français, tutoiement, ton direct : ce qui a été compris ou modifié' },
  },
};

/** Calendrier explicite remis au modèle : il ne calcule jamais un jour de semaine de tête. */
export function calendarContext(now, days = 35) {
  const lines = [];
  for (let i = 0; i < days; i++) {
    const d = addDays(now.date, i);
    const tag = i === 0 ? " (aujourd'hui)" : i === 1 ? ' (demain)' : '';
    lines.push(`${DAY_NAMES[isoDay(d) - 1]} ${d}${tag}`);
  }
  return lines.join('\n');
}

const MAX_TASKS = 40; const MAX_SUBTASKS = 20; const MAX_MINUTES = 720;
const isDate = (s) => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && addDays(s, 0) === s;
const toMinute = (s) => {
  const m = typeof s === 'string' && s.match(/^(\d{1,2}):(\d{2})$/);
  return m && +m[1] < 24 && +m[2] < 60 ? +m[1] * 60 + +m[2] : null;
};
const str = (v, max) => (typeof v === 'string' ? v.replace(/\s+/g, ' ').trim().slice(0, max) : '');
const oneOf = (v, list, dflt) => (list.includes(v) ? v : dflt);
const minutesOrNull = (v, issues, what) => {
  if (v == null) return null;
  if (!Number.isInteger(v) || v < 5 || v > MAX_MINUTES) { issues.push(`Durée ignorée pour « ${what} » : ${v} min n'est pas plausible.`); return null; }
  return v;
};

/**
 * Vérifie un jour annoncé par le modèle. Un jour de semaine prononcé doit correspondre à la date :
 * sinon on retient la date portant ce nom la plus proche de celle proposée (le modèle s'est trompé de calcul).
 */
function checkDay(ref, now, issues, questions, what) {
  if (!ref || typeof ref !== 'object' || !isDate(ref.date)) return null;
  const said = str(ref.said, 80);
  let date = ref.date;
  const named = DAY_NAMES.findIndex((d) => new RegExp(`\\b${d}\\b`).test(norm(said)));
  if (named >= 0 && isoDay(date) !== named + 1) {
    let best = null;
    for (let k = -6; k <= 6; k++) { const c = addDays(date, k); if (isoDay(c) === named + 1 && (!best || Math.abs(k) < Math.abs(best.k))) best = { c, k }; }
    if (best.c < now.date) best.c = addDays(best.c, 7);
    issues.push(`Date corrigée pour « ${what} » : « ${said} » → ${fmtDay(best.c, now.date).toLowerCase()} (l'IA avait proposé ${date}).`);
    date = best.c;
  }
  if (date < now.date) {
    questions.push(`« ${said || date} » (${what}) tombe dans le passé : quelle date voulais-tu dire ?`);
    return null;
  }
  if (daysBetween(now.date, date) > 366) { issues.push(`Date ignorée pour « ${what} » : plus d'un an d'écart.`); return null; }
  return { date, said };
}

/**
 * @param raw sortie JSON du modèle (déjà parsée)
 * @param now {date, minute}
 * @returns {ok, value, issues} — value est une compréhension nettoyée ; ok=false si la structure est inutilisable.
 */
export function validateUnderstanding(raw, now) {
  const issues = [];
  if (!raw || typeof raw !== 'object' || !Array.isArray(raw.tasks)) return { ok: false, value: null, issues: ['Réponse du moteur IA illisible.'] };
  const questions = (Array.isArray(raw.questions) ? raw.questions : []).map((q) => str(q, 200)).filter(Boolean).slice(0, 8);
  const seen = new Set();
  const uniqueId = (id, fallback) => {
    let v = str(id, 24).replace(/[^\w.-]/g, '') || fallback;
    while (seen.has(v)) v += '_';
    seen.add(v);
    return v;
  };

  const tasks = [];
  for (const [i, t] of raw.tasks.slice(0, MAX_TASKS).entries()) {
    if (!t || typeof t !== 'object') continue;
    const title = str(t.title, 120);
    if (!title) { issues.push('Une tâche sans titre a été ignorée.'); continue; }
    const id = uniqueId(t.id, `t${i + 1}`);
    let planned = checkDay(t.planned_day, now, issues, questions, title);
    let deadline = null;
    if (t.deadline && typeof t.deadline === 'object') {
      const d = checkDay({ date: t.deadline.date, said: t.deadline.said }, now, issues, questions, title);
      if (d) deadline = { date: d.date, time: toMinute(t.deadline.time) != null ? t.deadline.time : null, said: d.said };
    }
    if (planned && deadline && planned.date > deadline.date) {
      issues.push(`« ${title} » : le jour prévu (${planned.said}) tombe après l'échéance (${deadline.said}) ; jour ignoré.`);
      planned = null;
    }
    const subtasks = [];
    for (const [j, s] of (Array.isArray(t.subtasks) ? t.subtasks : []).slice(0, MAX_SUBTASKS).entries()) {
      const st = str(s?.title, 120);
      if (!st) continue;
      let sp = checkDay(s.planned_day, now, issues, questions, st);
      if (sp && deadline && sp.date > deadline.date) { issues.push(`« ${st} » : jour prévu après l'échéance, ignoré.`); sp = null; }
      subtasks.push({
        id: uniqueId(s.id, `${id}.${j + 1}`), title: st, planned_day: sp,
        duration_minutes: minutesOrNull(s.duration_minutes, issues, st),
        duration_source: oneOf(s.duration_source, ['said', 'estimated', 'unknown'], 'unknown'),
        depends_on: Array.isArray(s.depends_on) ? s.depends_on.map((x) => str(x, 24)).filter(Boolean) : [],
        confidence: oneOf(s.confidence, ['high', 'medium', 'low'], 'medium'),
      });
    }
    const sessionDays = [...new Set((Array.isArray(t.session_days) ? t.session_days : []).filter((d) => isDate(d) && d >= now.date && (!deadline || d <= deadline.date)))].sort().slice(0, 31);
    tasks.push({
      id, title, description: str(t.description, 500), planned_day: planned,
      part_of_day: oneOf(t.part_of_day, ['matin', 'apres-midi', 'soir'], null),
      start_time: toMinute(t.start_time) != null ? t.start_time : null,
      deadline,
      duration_minutes: minutesOrNull(t.duration_minutes, issues, title),
      duration_source: oneOf(t.duration_source, ['said', 'estimated', 'unknown'], 'unknown'),
      priority: oneOf(t.priority, ['low', 'normal', 'high', 'urgent'], 'normal'),
      session_days: sessionDays,
      session_minutes: minutesOrNull(t.session_minutes, issues, title),
      subtasks,
      depends_on: Array.isArray(t.depends_on) ? t.depends_on.map((x) => str(x, 24)).filter(Boolean) : [],
      project: str(t.project, 80) || null,
      source_quote: str(t.source_quote, 400),
      confidence: oneOf(t.confidence, ['high', 'medium', 'low'], 'medium'),
    });
  }

  // Doublons : même titre et même jour = une seule tâche (une précision ne doit pas en créer une seconde).
  const keyOf = (t) => `${norm(t.title).replace(/[^a-z0-9]+/g, ' ').trim()}|${t.planned_day?.date ?? ''}`;
  const byKey = new Map(); const merged = [];
  for (const t of tasks) {
    const k = keyOf(t); const first = byKey.get(k);
    if (!first) { byKey.set(k, t); merged.push(t); continue; }
    issues.push(`Doublon fusionné : « ${t.title} ».`);
    for (const s of t.subtasks) if (!first.subtasks.some((x) => norm(x.title) === norm(s.title))) first.subtasks.push(s);
    first.depends_on.push(...t.depends_on);
    first.duration_minutes ??= t.duration_minutes;
    first.deadline ??= t.deadline;
    // Les dépendances vers le doublon visent désormais la tâche conservée.
    for (const o of tasks) { o.depends_on = o.depends_on.map((d) => (d === t.id ? first.id : d)); for (const s of o.subtasks) s.depends_on = s.depends_on.map((d) => (d === t.id ? first.id : d)); }
  }

  // Dépendances : ids connus, pas d'auto-référence, pas de cycle.
  const known = new Set(merged.flatMap((t) => [t.id, ...t.subtasks.map((s) => s.id)]));
  const edges = new Map();
  for (const t of merged) {
    for (const node of [t, ...t.subtasks]) {
      node.depends_on = [...new Set(node.depends_on.filter((d) => known.has(d) && d !== node.id && !(node !== t && d === t.id)))];
      edges.set(node.id, node);
    }
  }
  const state = new Map();
  const visit = (id) => {
    state.set(id, 1);
    const node = edges.get(id);
    for (const d of [...node.depends_on]) {
      if (state.get(d) === 1) { node.depends_on = node.depends_on.filter((x) => x !== d); issues.push(`Dépendance circulaire retirée (« ${node.title} »).`); }
      else if (!state.has(d)) visit(d);
    }
    state.set(id, 2);
  };
  for (const id of edges.keys()) if (!state.has(id)) visit(id);

  const constraints = [];
  for (const c of (Array.isArray(raw.constraints) ? raw.constraints : []).slice(0, 30)) {
    const start = toMinute(c?.start_time); const end = c?.end_time === '24:00' ? 1440 : toMinute(c?.end_time);
    if (!isDate(c?.date) || start == null || end == null || end <= start) { issues.push('Une indisponibilité mal formée a été ignorée.'); continue; }
    if (c.date < now.date) continue;
    constraints.push({ date: c.date, start_time: c.start_time, end_time: c.end_time, reason: str(c.reason, 120) || 'Indisponible', said: str(c.said, 200) });
  }

  return { ok: true, value: { tasks: merged, constraints, questions, summary: str(raw.summary, 600) }, issues };
}

const PRIORITY = { low: 'LOW', normal: 'NORMAL', high: 'HIGH', urgent: 'URGENT' };
const PART_RANGE = { matin: PARTS.matin, 'apres-midi': PARTS['apres-midi'], soir: PARTS.soir };

/**
 * Compréhension validée → entrée du planificateur. Les sous-tâches qui ont leur propre jour ou leur propre durée
 * deviennent des séances distinctes (regroupées par projet) ; les autres restent une liste à cocher de la tâche principale.
 */
export function toPlanInput(u, now, opts = {}) {
  const items = [];
  const lastItemOf = new Map(); // id de tâche ou sous-tâche → id de séance qui la termine
  const horizon = { from: now.date, until: addDays(now.date, 6) };

  for (const t of u.tasks) {
    const deadline = t.deadline ? {
      date: t.deadline.date, minute: toMinute(t.deadline.time) ?? 1439, label: t.deadline.said, assumed: t.deadline.time == null,
    } : null;
    const separate = t.subtasks.filter((s) => s.planned_day || s.duration_minutes);
    const checklist = t.subtasks.filter((s) => !separate.includes(s));
    const group = t.project ?? (separate.length ? t.title : null);
    const base = (id, title, ref, minutes, said) => {
      const n = norm(title);
      const est = estimate(n, opts.learned);
      const day = ref ?? null;
      const [a, b] = t.part_of_day ? PART_RANGE[t.part_of_day] : [480, 1350];
      const fixed = toMinute(t.start_time);
      const item = {
        id, title, norm: n, group, deadline, horizon,
        window: day ? (fixed != null && ref === t.planned_day
          ? { date: day.date, start: fixed, end: fixed, fixed: true, label: day.said }
          : { date: day.date, start: a, end: b, fixed: false, label: day.said }) : null,
        recurrence: null, urgent: t.priority === 'urgent', low: t.priority === 'low', little: false, finishing: false,
        explicitMinutes: said ? minutes : null, dependsOn: [], notes: [], estimate: est,
        minutes: minutes ?? est.minutes, sessionMinutes: null, subtasks: [],
        priority: PRIORITY[t.priority], level: 'INSISTANT', source: { task: t.id },
      };
      if (deadline && item.window && (item.window.date > deadline.date || (item.window.date === deadline.date && item.window.start >= deadline.minute))) item.window = null;
      return item;
    };

    for (const s of separate) {
      const it = base(`${t.id}/${s.id}`, s.title, s.planned_day ?? t.planned_day, s.duration_minutes, s.duration_source === 'said');
      it.source = { task: t.id, sub: s.id };
      it.rawDeps = s.depends_on;
      if (s.confidence === 'low') it.notes.push('À vérifier : compréhension incertaine.');
      items.push(it);
      lastItemOf.set(s.id, it.id);
    }
    if (checklist.length || !separate.length) {
      const already = separate.reduce((a, s) => a + (s.duration_minutes ?? 0), 0);
      // Avec des séances datées, il ne reste que la liste à cocher : on l'estime elle, pas le titre (sinon le travail compte deux fois).
      const rest = checklist.reduce((a, s) => a + (s.duration_minutes ?? estimate(norm(s.title), opts.learned).minutes), 0);
      const own = t.duration_minutes != null ? Math.max(15, t.duration_minutes - already) : separate.length ? Math.max(15, rest) : null;
      const it = base(t.id, t.title, t.planned_day, own, t.duration_source === 'said' && already === 0);
      it.subtasks = checklist.map((s) => s.title);
      if (t.session_days.length) {
        it.recurrence = { type: 'days', dates: t.session_days, part: t.part_of_day };
        it.sessionMinutes = t.session_minutes ?? t.duration_minutes ?? 30;
        it.window = null;
      }
      if (t.description) it.notes.push(t.description);
      if (t.confidence === 'low') it.notes.push('À vérifier : compréhension incertaine.');
      it.rawDeps = t.depends_on;
      items.push(it);
      lastItemOf.set(t.id, it.id);
    } else {
      // Pas de séance « principale » : la tâche est terminée quand sa dernière sous-tâche l'est.
      lastItemOf.set(t.id, `${t.id}/${separate[separate.length - 1].id}`);
      for (const it of items.filter((x) => x.source.task === t.id)) it.rawDeps = [...it.rawDeps, ...t.depends_on];
    }
  }

  // Dépendances exprimées entre ids de la compréhension → ids de séances.
  for (const it of items) {
    it.dependsOn = [...new Set((it.rawDeps ?? []).map((d) => lastItemOf.get(d)).filter((d) => d && d !== it.id))];
    delete it.rawDeps;
    const hoursLeft = it.deadline ? daysBetween(now.date, it.deadline.date) * 24 + (it.deadline.minute - now.minute) / 60 : Infinity;
    it.level = it.urgent && hoursLeft <= 24 ? 'BRR' : it.urgent ? 'HARCELEMENT' : it.low ? 'NORMAL' : 'INSISTANT';
    if (it.priority === 'NORMAL' && hoursLeft <= 48) it.priority = 'HIGH';
    if (it.explicitMinutes) it.notes.push(`Durée indiquée : ${it.explicitMinutes} min.`);
    // L'horizon couvre tous les jours cités, échéances comprises.
    const last = [it.horizon.until, it.window?.date, it.deadline?.date, ...(it.recurrence?.dates ?? [])].filter(Boolean).sort().pop();
    it.horizon = { from: now.date, until: last };
  }

  const constraints = u.constraints.map((c) => ({
    date: c.date, start: toMinute(c.start_time), end: c.end_time === '24:00' ? 1440 : toMinute(c.end_time), label: c.reason,
  }));
  return { tasks: items, constraints, questions: u.questions, summary: u.summary };
}

/** Retouche d'une séance avant validation : modifie la compréhension (référence pour la suite de la conversation). */
export function applyItemEdit(u, itemId, act, value) {
  const [tid, sid] = itemId.split('/');
  const next = structuredClone(u);
  const t = next.tasks.find((x) => x.id === tid);
  if (!t) return next;
  const node = sid ? t.subtasks.find((s) => s.id === sid) : t;
  if (!node) return next;
  if (act === 'remove') {
    if (sid) t.subtasks = t.subtasks.filter((s) => s.id !== sid);
    else if (t.subtasks.some((s) => s.planned_day || s.duration_minutes)) t.subtasks = t.subtasks.filter((s) => s.planned_day || s.duration_minutes);
    else next.tasks = next.tasks.filter((x) => x.id !== tid);
    for (const o of next.tasks) { o.depends_on = o.depends_on.filter((d) => d !== tid && d !== sid); for (const s of o.subtasks) s.depends_on = s.depends_on.filter((d) => d !== tid && d !== sid); }
  } else if (act === 'minutes') {
    node.duration_minutes = Math.max(5, Math.min(MAX_MINUTES, value)); node.duration_source = 'said';
    if (!sid && t.session_days.length) t.session_minutes = node.duration_minutes;
  } else if (act === 'day') {
    node.planned_day = value ? { date: value, said: 'choisi à la main' } : null;
  } else if (act === 'priority') {
    t.priority = oneOf(value, ['low', 'normal', 'high', 'urgent'], t.priority);
  }
  return next;
}
