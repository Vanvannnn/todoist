// Tirage des punchlines : même logique que PunchlineEngine.kt / ContextualPunchlines.kt.
import { norm } from './nlu.js';

export const LEVELS = ['NORMAL', 'INSISTANT', 'HARCELEMENT', 'BRR'];
export const CONTEXTUAL_RATE = 0.35;
export const RECENT_MEMORY = 40;

/** Même identifiant que Kotlin : "b" + hashCode Java du texte en hexadécimal. */
export function stableId(text) {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (Math.imul(31, h) + text.charCodeAt(i)) | 0;
  return 'b' + (h >>> 0).toString(16);
}

export function catalog(data) {
  return data.categories.flatMap((c) => data.lines[c.id].map(([intensity, text]) => ({ id: stableId(text), text, category: c.id, intensity })));
}

export function candidates(pool, req) {
  const maxI = req.maxIntensity ?? 4;
  const off = new Set(req.disabledCategories ?? []);
  let enabled = pool.filter((p) => !off.has(p.category) && p.intensity <= maxI);
  if (!enabled.length) enabled = pool.filter((p) => !off.has(p.category));
  if (!enabled.length) return [{ id: 'fallback', text: "Il est temps de s'y mettre.", category: 'SARCASTIQUE', intensity: 1 }];
  const target = Math.min(LEVELS.indexOf(req.level) + 1, maxI);
  const finals = enabled.filter((p) => p.category === 'DERNIER_AVERTISSEMENT');
  let base = req.isFinal && finals.length ? finals
    : enabled.filter((p) => p.category !== 'DERNIER_AVERTISSEMENT' || req.level === 'BRR');
  if (!base.length) base = enabled;
  let byI = base.filter((p) => Math.abs(p.intensity - target) <= 1);
  if (!byI.length) byI = base;
  const recent = new Set(req.recentIds ?? []);
  const fresh = byI.filter((p) => !recent.has(p.id));
  return fresh.length ? fresh : byI;
}

export function fill(tpl, task, min, snooze) {
  return tpl.replaceAll('{task}', task.trim()).replaceAll('{min}', String(min)).replaceAll('{snooze}', String(snooze));
}

export function contextual(data, title, minutesLate, snoozeCount, rnd = Math.random) {
  const n = norm(title);
  const kw = data.contextual.filter((r) => r.keywords.some((k) => n.includes(k))).flatMap((r) => r.lines)
    .filter((l) => !l.includes('{min}') || minutesLate > 0);
  const pool = [...kw, ...kw, ...data.generic, ...(minutesLate > 0 ? data.late : []), ...(snoozeCount >= 2 ? data.snooze : [])];
  return fill(pool[Math.floor(rnd() * pool.length)], title, minutesLate, snoozeCount);
}

/** @returns {text, id, category} */
export function pick(data, pool, req, rnd = Math.random) {
  if (req.contextualEnabled !== false && req.taskTitle && !req.isFinal && rnd() < CONTEXTUAL_RATE) {
    return { text: contextual(data, req.taskTitle, req.minutesLate ?? 0, req.snoozeCount ?? 0, rnd), id: null, category: null };
  }
  const list = candidates(pool, req);
  const c = list[Math.floor(rnd() * list.length)];
  return { text: c.text, id: c.id, category: c.category };
}

export const pushRecent = (recent, id) => (id ? [...recent.filter((x) => x !== id), id].slice(-RECENT_MEMORY) : recent);
