// Mode pause automatique : pendant un engagement (et le trajet qui suit), les rappels sont reportés, jamais perdus.
// Même règle que ReminderPlanner.applyPauses côté Android.

export const PROFILES = {
  COURS: { label: 'Cours', pause: true, travelBefore: 20, travelAfter: 20 },
  RDV: { label: 'Rendez-vous', pause: true, travelBefore: 20, travelAfter: 20 },
  TRAVAIL: { label: 'Travail', pause: true, travelBefore: 30, travelAfter: 30 },
  PERSO: { label: 'Temps personnel', pause: false, travelBefore: 0, travelAfter: 0 },
  SOMMEIL: { label: 'Sommeil', pause: true, travelBefore: 0, travelAfter: 0 },
};

/**
 * @param events [{start, end (ms), profile, travelAfter?}]
 * @returns intervalles de pause fusionnés [{start, end, title}]
 */
export function pauseIntervals(events, profiles = PROFILES) {
  const iv = events.filter((e) => profiles[e.profile]?.pause)
    .map((e) => ({ start: e.start, end: e.end + (e.travelAfter ?? profiles[e.profile].travelAfter) * 60000, title: e.title }))
    .sort((a, b) => a.start - b.start);
  const out = [];
  for (const i of iv) {
    const last = out[out.length - 1];
    if (last && i.start <= last.end) { last.end = Math.max(last.end, i.end); last.title += ` + ${i.title}`; } else out.push({ ...i });
  }
  return out;
}

/**
 * Reporte un rappel tombant pendant une pause à la fin de celle-ci.
 * Le niveau 4 ne traverse une pause que si la tâche l'autorise explicitement (bypassPause).
 */
export function deferForPause(at, intervals, { level = 'NORMAL', bypassPause = false, enabled = true } = {}) {
  if (!enabled || (bypassPause && level === 'BRR')) return { at, pausedBy: null };
  let t = at; let by = null;
  for (const i of intervals) if (t >= i.start && t < i.end) { t = i.end; by = i; }
  return { at: t, pausedBy: by };
}
