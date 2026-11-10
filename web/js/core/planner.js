// Construction d'un planning réaliste à partir de tâches et des disponibilités réelles.
// Règles : jamais pendant un engagement ni ses trajets, pauses entre blocs, vrais repas, plafond de concentration
// quotidien, marge avant les échéances, répartition sur plusieurs jours. Porté en Kotlin (domain/voice/SchedulePlanner.kt).
import { addDays, daysBetween, fmtTime, fmtDay, round5 } from './dates.js';

export const DEFAULT_SETTINGS = {
  dayStart: 8 * 60, dayEnd: 22 * 60 + 30,
  lunch: { from: 11 * 60 + 45, to: 14 * 60, dur: 60 },
  dinner: { from: 19 * 60, to: 21 * 60, dur: 45 },
  breakMin: 15, maxBlock: 90, minBlock: 25,
  maxFocusPerDay: 5 * 60, deadlineMargin: 60, maxDays: 14,
};
const PRIORITY_WEIGHT = { URGENT: 3, HIGH: 2, NORMAL: 1, LOW: 0 };
const PART_RANGE = { matin: [480, 720], 'apres-midi': [810, 1080], soir: [1080, 1350] };

/** Découpe une durée en séances de maxBlock au plus, sans séance plus courte que minBlock. */
export function chunks(total, maxBlock, minBlock) {
  const n = Math.max(1, Math.ceil(total / maxBlock));
  const base = round5(total / n);
  const out = Array(n).fill(base);
  out[n - 1] = Math.max(minBlock, total - base * (n - 1));
  return out;
}

function freeGaps(busy, from, to, breakMin) {
  // Les blocs de travail sont entourés d'une pause ; repas, trajets et engagements comptent déjà comme coupure.
  const iv = busy.map((b) => (b.kind === 'task' ? [b.start - breakMin, b.end + breakMin] : [b.start, b.end]))
    .sort((a, b) => a[0] - b[0]);
  const gaps = []; let cur = from;
  for (const [a, b] of iv) {
    if (b <= cur) continue;
    if (a >= to) break;
    if (a > cur) gaps.push([cur, Math.min(a, to)]);
    cur = Math.max(cur, b);
  }
  if (cur < to) gaps.push([cur, to]);
  return gaps;
}

function placeMeal(day, meal, S) {
  const gap = freeGaps(day.busy, meal.from, meal.to, 0).find(([a, b]) => b - a >= meal.dur);
  if (!gap) return false;
  day.busy.push({ start: gap[0], end: gap[0] + meal.dur, kind: 'meal', title: meal === S.lunch ? 'Déjeuner' : 'Dîner' });
  return true;
}

/**
 * @param input {tasks, events, now:{date,minute}, settings}
 *   tasks : sortie de parseDictation (ou équivalent) ; events : [{date,start,end,title,travelBefore,travelAfter}]
 */
export function plan({ tasks, events = [], now, settings = {} }) {
  const S = { ...DEFAULT_SETTINGS, ...settings, lunch: { ...DEFAULT_SETTINGS.lunch, ...settings.lunch }, dinner: { ...DEFAULT_SETTINGS.dinner, ...settings.dinner } };
  const warnings = []; const unplaced = [];
  const maxDate = addDays(now.date, S.maxDays - 1);
  let lastDate = now.date;
  for (const t of tasks) {
    const end = [t.horizon?.until, t.deadline?.date].filter(Boolean).sort().pop() ?? addDays(now.date, 6);
    if (end > lastDate) lastDate = end;
  }
  if (lastDate > maxDate) lastDate = maxDate;

  const days = [];
  for (let d = now.date; d <= lastDate; d = addDays(d, 1)) {
    const day = { date: d, busy: [], focus: 0, tasks: new Set() };
    for (const e of events.filter((e) => e.date === d)) {
      day.busy.push({ start: e.start, end: e.end, kind: 'event', title: e.title, location: e.location ?? null });
      // Un déplacement ne prend jamais zéro minute quand un lieu est indiqué.
      const before = e.travelBefore ?? (e.location ? 15 : 0);
      const after = e.travelAfter ?? (e.location ? 15 : 0);
      if (before > 0) day.busy.push({ start: e.start - before, end: e.start, kind: 'travel', title: 'Trajet' });
      if (after > 0) day.busy.push({ start: e.end, end: e.end + after, kind: 'travel', title: 'Trajet' });
    }
    if (d === now.date) day.busy.push({ start: 0, end: Math.ceil((now.minute + 10) / 5) * 5, kind: 'past', title: '' });
    for (const meal of [S.lunch, S.dinner]) {
      const passed = d === now.date && now.minute >= meal.to;
      if (!passed && !placeMeal(day, meal, S)) warnings.push(`${fmtDay(d, now.date)} : pas de place pour un vrai ${meal === S.lunch ? 'déjeuner' : 'dîner'} entre ${fmtTime(meal.from)} et ${fmtTime(meal.to)}.`);
    }
    days.push(day);
  }
  const dayIndex = (date) => days.findIndex((x) => x.date === date);

  // Séances à placer.
  const sessions = [];
  for (const t of tasks) {
    const latest = t.deadline ? shift(t.deadline, -S.deadlineMargin) : null;
    const from = t.horizon?.from && t.horizon.from > now.date ? t.horizon.from : now.date;
    const base = { task: t, latest, from, window: t.window ?? null };
    if (t.recurrence?.type === 'daily') {
      const until = [t.horizon?.until ?? lastDate, latest?.date ?? lastDate, lastDate].sort()[0];
      for (let d = from; d <= until; d = addDays(d, 1)) {
        sessions.push({ ...base, dur: t.sessionMinutes ?? 30, onlyDay: d, part: t.recurrence.part, window: null });
      }
    } else if (t.recurrence?.type === 'count') {
      for (let i = 0; i < t.recurrence.n; i++) sessions.push({ ...base, dur: Math.min(t.minutes, S.maxBlock), window: null });
    } else {
      for (const dur of chunks(t.minutes, S.maxBlock, S.minBlock)) sessions.push({ ...base, dur });
    }
  }

  // Ordre : créneaux imposés, puis échéance la plus proche, priorité, durée ; dépendances respectées.
  const key = (s) => [s.window?.fixed ? 0 : 1, s.latest ? s.latest.date + String(s.latest.minute).padStart(4, '0') : '9999',
    9 - (PRIORITY_WEIGHT[s.task.priority] ?? 1), String(9999 - s.dur).padStart(4, '0')].join('|');
  sessions.sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));
  const ordered = []; const pending = [...sessions]; const doneTasks = new Set();
  while (pending.length) {
    let i = pending.findIndex((s) => s.task.dependsOn?.every((id) => doneTasks.has(id) || !tasks.some((t) => t.id === id)) ?? true);
    if (i < 0) i = 0; // cycle : on ignore la dépendance plutôt que de bloquer
    const [s] = pending.splice(i, 1);
    ordered.push(s);
    if (!pending.some((p) => p.task === s.task)) doneTasks.add(s.task.id);
  }

  const placedEnd = {};
  for (const s of ordered) {
    const depEnd = (s.task.dependsOn ?? []).map((id) => placedEnd[id]).filter(Boolean).sort((a, b) => (a.date === b.date ? a.minute - b.minute : a.date < b.date ? -1 : 1)).pop();
    let ok = tryPlace(s, s.dur, depEnd);
    if (!ok && s.dur >= S.minBlock * 2 && !s.window?.fixed) {
      const half = round5(s.dur / 2);
      ok = tryPlace(s, half, depEnd) && (tryPlace(s, s.dur - half, depEnd) || 'partial');
      if (ok === 'partial') { unplaced.push(miss(s, s.dur - half)); continue; }
    }
    if (!ok) unplaced.push(miss(s, s.dur));
  }

  function tryPlace(s, dur, depEnd) {
    const t = s.task;
    let cands;
    if (s.onlyDay) cands = [s.onlyDay];
    else {
      const first = [s.from, depEnd?.date ?? s.from].sort().pop();
      const last = s.latest && s.latest.date < lastDate ? s.latest.date : lastDate;
      cands = days.map((d) => d.date).filter((d) => d >= first && d <= last);
      if (s.window && cands.includes(s.window.date)) cands = [s.window.date, ...cands.filter((d) => d !== s.window.date)];
    }
    let best = null;
    for (const date of cands) {
      const day = days[dayIndex(date)];
      if (!day) continue;
      const fixedHere = s.window?.fixed && s.window.date === date;
      if (!fixedHere && day.focus + dur > S.maxFocusPerDay) continue;
      let [from, to] = [S.dayStart, S.dayEnd];
      // Créneau souhaité (« demain après-midi ») : même moment de la journée, y compris les jours de repli.
      if (s.window && !s.window.fixed) [from, to] = [Math.max(from, s.window.start), Math.min(to, s.window.end)];
      if (s.part) [from, to] = PART_RANGE[s.part] ?? [from, to];
      if (s.latest && s.latest.date === date) to = Math.min(to, s.latest.minute);
      if (depEnd && depEnd.date === date) from = Math.max(from, depEnd.minute + S.breakMin);
      let start = null;
      if (fixedHere) {
        const end = s.window.start + dur;
        const clash = day.busy.some((b) => b.kind !== 'meal' && b.start < end && b.end > s.window.start);
        if (!clash) start = s.window.start;
        else if (!warnings.some((w) => w.includes(t.title))) warnings.push(`« ${t.title} » : l'horaire demandé (${fmtTime(s.window.start)}) est déjà occupé, un autre créneau est proposé.`);
      }
      if (start == null) {
        const gap = freeGaps(day.busy, from, to, S.breakMin).find(([a, b]) => b - a >= dur);
        if (gap) start = gap[0];
      }
      if (start == null) continue;
      const idx = dayIndex(date);
      // Le jour souhaité d'abord, puis les jours les plus proches de lui.
      const preferred = s.window ? (s.window.date === date ? -1000 : Math.abs(daysBetween(s.window.date, date)) * 40) : 0;
      const score = preferred + day.focus + (day.tasks.has(t.id) ? 600 : 0) + idx * 15;
      if (!best || score < best.score) best = { score, day, start };
      if (fixedHere && start === s.window.start) break;
    }
    if (!best) return false;
    if (s.window && best.day.date !== s.window.date && !warnings.some((w) => w.startsWith(`« ${t.title} »`))) {
      warnings.push(`« ${t.title} » : « ${s.window.label} » n'a pas de créneau libre assez long, proposé ${fmtDay(best.day.date, now.date).toLowerCase()} à ${fmtTime(best.start)}.`);
    }
    best.day.busy.push({ start: best.start, end: best.start + dur, kind: 'task', title: t.title, taskId: t.id, priority: t.priority, level: t.level });
    best.day.focus += dur;
    best.day.tasks.add(t.id);
    const end = { date: best.day.date, minute: best.start + dur };
    const prev = placedEnd[t.id];
    if (!prev || prev.date < end.date || (prev.date === end.date && prev.minute < end.minute)) placedEnd[t.id] = end;
    return true;
  }

  function miss(s, dur) {
    const t = s.task;
    const reason = s.latest ? `pas assez de temps libre avant l'échéance (${fmtDay(t.deadline.date, now.date).toLowerCase()} ${fmtTime(t.deadline.minute)})`
      : s.onlyDay ? `aucun créneau libre ${fmtDay(s.onlyDay, now.date).toLowerCase()}` : 'aucun créneau libre sur la période';
    const alternatives = [];
    const freeBefore = days.filter((d) => !s.latest || d.date <= s.latest.date).reduce((acc, d) => acc + freeGaps(d.busy, S.dayStart, s.latest?.date === d.date ? Math.min(S.dayEnd, s.latest.minute) : S.dayEnd, S.breakMin).reduce((a, [x, y]) => a + (y - x), 0), 0);
    const largest = Math.max(0, ...days.flatMap((d) => freeGaps(d.busy, S.dayStart, S.dayEnd, S.breakMin).map(([a, b]) => b - a)));
    if (largest >= S.minBlock && largest < dur) alternatives.push(`Réduire cette séance à ${round5(Math.min(largest, dur) - 5)} min.`);
    const movable = ordered.filter((o) => o.task !== t && !o.task.deadline && (PRIORITY_WEIGHT[o.task.priority] ?? 1) < (PRIORITY_WEIGHT[t.priority] ?? 1));
    if (movable.length) alternatives.push(`Reporter « ${movable[0].task.title} » (moins prioritaire) pour libérer du temps.`);
    if (s.latest) alternatives.push(freeBefore > 0 ? 'Demander un délai ou commencer plus tôt dans la journée.' : 'Demander un délai : il ne reste aucun temps libre avant l\'échéance.');
    else alternatives.push('Reporter cette tâche à la semaine suivante.');
    alternatives.push('Augmenter le temps de travail quotidien autorisé dans les réglages.');
    return { taskId: t.id, title: t.title, minutes: dur, reason, alternatives };
  }

  // Pauses visibles après chaque bloc de travail, quand la place existe.
  for (const day of days) {
    const extra = [];
    for (const b of day.busy.filter((x) => x.kind === 'task')) {
      const s = b.end; const e = b.end + S.breakMin;
      if (!day.busy.some((o) => o !== b && o.kind !== 'past' && o.start < e && o.end > s) && e <= S.dayEnd + S.breakMin) extra.push({ start: s, end: e, kind: 'break', title: 'Pause' });
    }
    day.busy.push(...extra);
    day.blocks = day.busy.filter((b) => b.kind !== 'past').sort((a, b) => a.start - b.start || a.end - b.end);
  }

  const total = sessions.reduce((a, s) => a + s.dur, 0);
  const missing = unplaced.reduce((a, u) => a + u.minutes, 0);
  return {
    days: days.map((d) => ({ date: d.date, label: fmtDay(d.date, now.date), focus: d.focus, blocks: d.blocks,
      free: freeGaps(d.busy.filter((b) => b.kind !== 'break'), S.dayStart, S.dayEnd, 0).reduce((a, [x, y]) => a + (y - x), 0) })),
    unplaced, warnings,
    feasible: unplaced.length === 0,
    summary: { sessions: sessions.length, plannedMinutes: total - missing, requestedMinutes: total },
    settings: S,
  };
}

const shift = (at, minutes) => {
  let m = at.minute + minutes; let d = at.date;
  while (m < 0) { m += 1440; d = addDays(d, -1); }
  while (m >= 1440) { m -= 1440; d = addDays(d, 1); }
  return { date: d, minute: m };
};

/** Vérifie les règles obligatoires d'un planning. Retourne la liste des violations (vide = conforme). */
export function validatePlan(result, input) {
  const S = result.settings; const errors = [];
  for (const day of result.days) {
    const tasksB = day.blocks.filter((b) => b.kind === 'task');
    const hard = day.blocks.filter((b) => b.kind === 'event' || b.kind === 'travel' || b.kind === 'meal');
    let focus = 0;
    for (const b of tasksB) {
      focus += b.end - b.start;
      if (b.start < S.dayStart || b.end > S.dayEnd) errors.push(`${day.date} ${b.title} hors journée`);
      if (b.end - b.start > S.maxBlock) errors.push(`${day.date} ${b.title} bloc trop long`);
      for (const h of hard) if (h.start < b.end && h.end > b.start) errors.push(`${day.date} ${b.title} chevauche ${h.title}`);
      for (const o of tasksB) if (o !== b && o.start >= b.start && o.start < b.end + S.breakMin) errors.push(`${day.date} ${b.title} → ${o.title} sans pause`);
      const t = input.tasks.find((x) => x.id === b.taskId);
      if (t?.deadline) {
        const lim = shift(t.deadline, -S.deadlineMargin);
        if (day.date > lim.date || (day.date === lim.date && b.end > lim.minute)) errors.push(`${day.date} ${b.title} après l'échéance`);
      }
      if (day.date === input.now.date && b.start < input.now.minute) errors.push(`${b.title} dans le passé`);
    }
    if (focus > S.maxFocusPerDay && !tasksB.some((b) => input.tasks.find((x) => x.id === b.taskId)?.window?.fixed)) errors.push(`${day.date} journée surchargée (${focus} min)`);
    // Un repas impossible (engagements sur toute la plage) doit au moins être signalé.
    if (daysBetween(input.now.date, day.date) > 0 && tasksB.length && !day.blocks.some((b) => b.kind === 'meal') &&
      !result.warnings.some((w) => w.startsWith(day.label))) errors.push(`${day.date} aucun repas`);
  }
  return errors;
}
