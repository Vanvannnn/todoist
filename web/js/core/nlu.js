// Compréhension d'une dictée libre en français → tâches structurées (échéances, créneaux souhaités,
// répétitions, urgence, durées, dépendances). Déterministe, hors ligne, sans service externe.
// Porté à l'identique en Kotlin (android/.../domain/voice/VoiceParser.kt) ; cas communs : design/fixtures/nlu.json.
import { addDays, isoDay, daysBetween, DAY_NAMES, MONTHS, fmtTime, fmtDay } from './dates.js';
import { estimate } from './estimator.js';

/** Minuscules sans accents, longueur identique au texte NFC (les positions restent alignées). */
export const norm = (s) => s.normalize('NFC').split('').map((c) => c.normalize('NFD')[0]).join('')
  .replace(/[’`]/g, "'").toLowerCase();

/** Plages des moments de la journée : [début, fin, heure retenue pour une échéance]. */
export const PARTS = {
  matin: [480, 720, 480], midi: [720, 840, 720], 'apres-midi': [810, 1080, 810],
  soir: [1080, 1350, 1080], nuit: [1260, 1410, 1320],
};
const PART_ALIASES = { 'apres midi': 'apres-midi', aprem: 'apres-midi', soiree: 'soir' };
const NUMBERS = { un: 1, une: 1, deux: 2, trois: 3, quatre: 4, cinq: 5, six: 6, sept: 7, huit: 8, dix: 10 };
const num = (s) => NUMBERS[s] ?? parseInt(s, 10);

const STOP = new Set(['avec', 'pour', 'dans', 'mais', 'tout', 'toute', 'toutes', 'tous', 'cette', 'semaine', 'demain',
  'faire', 'faut', 'faudrait', 'absolument', 'vraiment', 'aussi', 'encore', 'bien', 'peux', 'dois', 'veux', 'aimerais',
  'voudrais', 'avant', 'apres', 'jour', 'jours', 'chaque', 'termine', 'terminer', 'parce', 'rendre', 'soir', 'matin',
  'midi', 'nuit', 'aujourd', "aujourd'hui", 'leur', 'leurs', 'notre', 'votre', 'quelque', 'chose', 'heure', 'heures',
  'minutes', 'fois', 'plus', 'moins', 'sont', 'etre', 'soit', 'avoir', 'peu']);

const ADVERBS = '(?:(?:absolument|vraiment|bien|aussi|encore|surtout|imperativement|d\'abord|ensuite|enfin|egalement)\\s+)*';
const TRIGGERS = [
  "j'aimerais", 'je voudrais', 'je veux', 'je dois', 'il faut que je', 'il faudrait que je', 'faut que je',
  "j'ai besoin de", 'je vais', 'je compte', 'je pense', "n'oublie pas de", 'ne pas oublier de', 'penser a',
  'rappelle-moi de', 'rappelle moi de', "j'ai a", 'je prevois de', 'je devrais', 'il me faut', 'je peux',
  'je dois encore', 'ajoute', 'ajouter',
];
// « il faudrait absolument que je » : les adverbes peuvent s'intercaler avant « que je ».
const TRIGGER_RE = new RegExp('\\b(?:' + TRIGGERS.map((t) => t.replace(/ que je$/, ' ' + ADVERBS + 'que je').replace(/ /g, '\\s+'))
  .join('|') + ')\\s+' + ADVERBS, 'g');

// Subjonctifs fréquents après « que je » → infinitif (clé normalisée, valeur accentuée).
const SUBJ = { termine: 'terminer', finisse: 'finir', fasse: 'faire', rende: 'rendre', envoie: 'envoyer', appelle: 'appeler',
  aille: 'aller', prenne: 'prendre', ecrive: 'écrire', lise: 'lire', revise: 'réviser', prepare: 'préparer', range: 'ranger',
  avance: 'avancer', commence: 'commencer', reponde: 'répondre', paie: 'payer', sorte: 'sortir' };
const STRIP_RE = /\b(absolument|imperativement|vraiment|urgent|urgente|sans faute|obligatoirement|a tout prix|au plus vite|en priorite|un peu|\d+ fois|deux fois|trois fois|chaque jour|tous les jours|quotidiennement|chaque soir|tous les soirs|chaque matin|tous les matins)\b/g;
const INFINITIVE = /^(?:s'|se\s+)?[a-z-]+(?:er|ir|re|oir)$/;
const PRONOUN_START = /^(?:en|les|le|la|l'|y|lui|leur|m'en|m'y)\s*[a-z]/;
// Seul un groupe nominal hérite du verbe précédent (« réviser les maths, la physique ») ; « c'est urgent » arrête la liste.
const DETERMINER = /^(?:le|la|les|l'|un|une|des|du|de la|de l'|mon|ma|mes|ton|ta|tes|son|sa|ses|notre|nos|votre|vos|leur|leurs|cet|cette|ces)\b/;
const NOT_VERBS = new Set(['hier', 'soir', 'pire', 'cher', 'dernier', 'premier', 'entier', 'leur', 'mer', 'fer', 'air', 'noir', 'espoir', 'devoir']);

const URGENT_RE = /\b(absolument|imperativement|urgent|urgente|urgence|sans faute|obligatoirement|a tout prix|au plus vite|en priorite|prioritaire|crucial|vital)\b/;
const LOW_RE = /\b(si j'ai le temps|eventuellement|pas urgent|pas presse|quand j'aurai le temps|a l'occasion)\b/;
const DEADLINE_RE = /\b(avant|pour|d'ici|au plus tard|a rendre|rendre|rendu|rendue|termine|terminee|termines|fini|finie|boucle|deadline|echeance|limite|doit etre|doivent etre|jusqu'a)\b[^,.;]*$/;
const PLAN_RE = /\b(planifi|organis|planning|emploi du temps|ma semaine|ma journee|cale[rs]?\b|caser)/;
const DAILY_RE = /\b(?:un peu\s+)?(chaque jour|tous les jours|quotidiennement|chaque soir|tous les soirs|chaque matin|tous les matins)\b/;
const COUNT_RE = /\b(\d+|deux|trois|quatre|cinq|six)\s+fois\b/;
const LITTLE_RE = /\bun peu\b/;
const DURATION_RE = /\b(?:(?:pendant|durant|en|prendre|prendra|prend|environ|compter|faut)\s+)?(?:environ\s+)?(\d+|une|un|deux|trois|quatre)\s*(?:(h|heures?)\s*(\d{2})?|(min|minutes?))\b|\b(une demi-heure|une demi heure|un quart d'heure)\b/g;

// ---------- Expressions temporelles ----------
const TIME_PATTERNS = [
  ['rel', /\b(aujourd'hui|ce soir|ce matin|cet apres-midi|cet apres midi|cet aprem|cette nuit|ce midi|apres-demain|apres demain|demain)\b/g],
  ['weekday', new RegExp(`\\b(${DAY_NAMES.join('|')})(\\s+prochain)?\\b`, 'g')],
  ['date', new RegExp(`\\ble\\s+(\\d{1,2})(?:er)?(?:\\s+(${MONTHS.join('|')}))?\\b`, 'g')],
  ['in', /\bdans\s+(\d+|un|une|deux|trois|quatre|cinq)\s+(jours?|semaines?|heures?|minutes?|min)\b/g],
  ['part', /\b(?:le\s+|en\s+|dans la\s+|la\s+)?(apres-midi|apres midi|aprem|matin|midi|soiree|soir|nuit)\b/g],
  ['time', /\b(?:(a|vers|des|avant|pour|jusqu'a)\s+)?(\d{1,2})\s*(?:h|:)\s*(\d{2})?(?![\d])/g],
  ['scope', /\b(cette semaine|ce week-end|ce weekend|la semaine prochaine|semaine prochaine)\b/g],
];

function scanTimes(n, now, durationSpans) {
  const raw = [];
  for (const [type, re] of TIME_PATTERNS) {
    re.lastIndex = 0;
    for (const m of n.matchAll(re)) {
      const start = m.index; const end = m.index + m[0].length;
      if (durationSpans.some(([a, b]) => start < b && end > a)) continue;
      if (type === 'time') {
        const h = +m[2];
        // « 1h30 » sans « à / vers » est presque toujours une durée.
        if (h > 23 || (!m[1] && h < 7)) continue;
      }
      raw.push({ type, start, end, m });
    }
  }
  raw.sort((a, b) => a.start - b.start || (b.end - b.start) - (a.end - a.start));
  const toks = [];
  for (const t of raw) if (!toks.some((o) => t.start < o.end && t.end > o.start)) toks.push(t);

  // Fusion : jour + moment + heure adjacents forment une seule expression.
  const out = [];
  for (const t of toks) {
    const v = resolve(t, now);
    const prev = out[out.length - 1];
    const gap = prev ? n.slice(prev.end, t.start) : '';
    if (prev && /^[\s,]*(?:a|vers|des|en fin de|en debut de|dans la)?\s*$/.test(gap) && canMerge(prev, v)) {
      if (v.date && !prev.date) prev.date = v.date;
      if (v.part && !prev.part) prev.part = v.part;
      if (v.minute != null && prev.minute == null) prev.minute = v.minute;
      prev.end = t.end;
      continue;
    }
    out.push({ ...v, start: t.start, end: t.end });
  }
  return out;
}

const canMerge = (a, b) => a.kind !== 'scope' && b.kind !== 'scope' &&
  !(a.date && b.date) && !(a.part && b.part) && !(a.minute != null && b.minute != null);

function resolve(t, now) {
  const m = t.m;
  switch (t.type) {
    case 'rel': {
      const w = m[1].replace(' ', '-').replace('apres midi', 'apres-midi');
      const map = { "aujourd'hui": [0], 'ce-soir': [0, 'soir'], 'ce-matin': [0, 'matin'], 'cet-apres-midi': [0, 'apres-midi'],
        'cet-apres midi': [0, 'apres-midi'], 'cet-aprem': [0, 'apres-midi'], 'cette-nuit': [0, 'nuit'], 'ce-midi': [0, 'midi'],
        'apres-demain': [2], demain: [1] };
      const [d, part] = map[w] ?? map[m[1]] ?? [0];
      return { kind: 'when', date: addDays(now.date, d), part: part ?? null, minute: null };
    }
    case 'weekday': {
      const target = DAY_NAMES.indexOf(m[1]) + 1;
      let d = (target - isoDay(now.date) + 7) % 7;
      if (m[2] && d === 0) d = 7;
      return { kind: 'when', date: addDays(now.date, d), part: null, minute: null };
    }
    case 'date': {
      const day = +m[1];
      if (day < 1 || day > 31) return { kind: 'none' };
      const [y, mo] = now.date.split('-').map(Number);
      let month = m[2] ? MONTHS.indexOf(m[2]) + 1 : mo;
      let year = y;
      const iso = (yy, mm) => `${yy}-${String(mm).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
      if (iso(year, month) < now.date) { if (m[2]) year++; else if (++month > 12) { month = 1; year++; } }
      return { kind: 'when', date: iso(year, month), part: null, minute: null };
    }
    case 'in': {
      const q = num(m[1]); const u = m[2];
      if (u.startsWith('jour')) return { kind: 'when', date: addDays(now.date, q), part: null, minute: null };
      if (u.startsWith('semaine')) return { kind: 'when', date: addDays(now.date, 7 * q), part: null, minute: null };
      const total = now.minute + (u.startsWith('h') ? q * 60 : q);
      return { kind: 'when', date: addDays(now.date, Math.floor(total / 1440)), part: null, minute: total % 1440 };
    }
    case 'part': return { kind: 'when', date: null, part: PART_ALIASES[m[1]] ?? m[1], minute: null };
    case 'time': return { kind: 'when', date: null, part: null, minute: Math.min(23, +m[2]) * 60 + (m[3] ? Math.min(59, +m[3]) : 0) };
    case 'scope': {
      const w = m[1];
      if (w.includes('prochaine')) { const mon = addDays(now.date, 8 - isoDay(now.date)); return { kind: 'scope', from: mon, until: addDays(mon, 6) }; }
      if (w.includes('week')) { const sat = addDays(now.date, Math.max(0, 6 - isoDay(now.date))); return { kind: 'scope', from: sat, until: addDays(now.date, 7 - isoDay(now.date)) }; }
      return { kind: 'scope', from: now.date, until: addDays(now.date, 7 - isoDay(now.date)) };
    }
  }
  return { kind: 'none' };
}

function scanDurations(n) {
  const spans = []; let minutes = null;
  DURATION_RE.lastIndex = 0;
  for (const m of n.matchAll(DURATION_RE)) {
    const marked = /^(pendant|durant|en|prendre|prendra|prend|environ|compter|faut)\b/.test(m[0]);
    if (m[5]) minutes = m[5].includes('quart') ? 15 : 30;
    else if (m[4]) minutes = num(m[1]);
    else if (m[2]) {
      if (!marked && num(m[1]) > 4) continue; // « 21h30 » est une heure, « 1h30 » une durée
      minutes = num(m[1]) * 60 + (m[3] ? +m[3] : 0);
    }
    else continue;
    spans.push([m.index, m.index + m[0].length]);
  }
  return { spans, minutes };
}

// ---------- Tâches ----------
const stem = (w) => w.replace(/(?:s|x)$/, '');
const tokens = (n) => n.split(/[^a-z0-9'-]+/).map((w) => w.replace(/^(?:l|d|j|m|t|s|qu|n)'/, '')).filter(Boolean);

export function keywords(normTitle) {
  const toks = tokens(normTitle);
  return toks.slice(1).filter((w) => /^\d+$/.test(w) || (w.length >= 4 && !STOP.has(w))).map(stem);
}
const kwMatch = (a, b) => a === b || (Math.min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)));

function matchTask(tasks, text, minRatio) {
  const words = tokens(text).map(stem);
  let best = null; let bestScore = 0;
  for (const t of tasks) {
    const kws = keywords(t.norm);
    if (!kws.length) continue;
    const hits = kws.filter((k) => words.some((w) => kwMatch(w, k))).length;
    const ratio = hits / kws.length;
    if (hits > 0 && ratio >= minRatio && hits + ratio > bestScore) { best = t; bestScore = hits + ratio; }
  }
  return best;
}

const capitalize = (s) => s.charAt(0).toUpperCase() + s.slice(1);

function cleanTitle(orig, removeSpans) {
  let chars = orig.split('');
  for (const [a, b] of removeSpans) for (let i = a; i < b && i < chars.length; i++) chars[i] = '\u0000';
  let s = chars.join('').replace(/\u0000+/g, ' ');
  s = s.replace(/\s+(?:avant d['e]|apr[eè]s avoir|une fois que)\b.*$/i, '').replace(/\s+/g, ' ').replace(/^[\s,;:]+|[\s,;:.!?]+$/g, '')
    .replace(/\s+(?:et|puis|ou|de|d'|à|a|pour|avant|vers|dès|le|la|les|en)$/i, '').trim();
  return capitalize(s);
}

/**
 * @param text transcription brute
 * @param now {date:'AAAA-MM-JJ', minute}
 * @param opts {learned}
 */
export function parseDictation(text, now, opts = {}) {
  const orig = text.normalize('NFC').replace(/\s+/g, ' ').trim();
  const n = norm(orig);
  const tasks = [];
  const questions = [];
  let globalScope = null;
  let seq = 0;
  const pendingDeps = [];

  const sentences = [];
  const sre = /[^.!?;\n]+[.!?;\n]*/g;
  for (const m of orig.matchAll(sre)) if (m[0].trim()) sentences.push({ start: m.index, end: m.index + m[0].length });

  for (const s of sentences) {
    const so = orig.slice(s.start, s.end);
    const sn = n.slice(s.start, s.end);
    const { spans: durSpans, minutes: durMinutes } = scanDurations(sn);
    const times = scanTimes(sn, now, durSpans);
    const scope = times.find((t) => t.kind === 'scope');
    if (scope && !globalScope && tasks.length === 0) globalScope = scope;

    // 1. Énumérations introduites par une intention (« j'aimerais… », « je dois… »).
    const items = [];
    TRIGGER_RE.lastIndex = 0;
    const starts = [...sn.matchAll(TRIGGER_RE)].map((m) => m.index + m[0].length);
    // Sans formule d'intention : liste directe (« Réviser les maths… », « Planifie ma semaine : … », « Si j'ai le temps, lire… »).
    if (!starts.length) {
      const cands = [0, sn.indexOf(':') + 1, sn.indexOf(',') + 1].filter((i, k) => k === 0 || i > 0);
      const first = cands.map((i) => i + (sn.slice(i).length - sn.slice(i).trimStart().length))
        .find((i) => { const w = sn.slice(i).split(/\s+/)[0]; return i < sn.length && INFINITIVE.test(w) && !NOT_VERBS.has(w); });
      if (first != null) starts.push(first);
    }
    for (const tailStart of starts) {
      const cut = sn.slice(tailStart).search(/\s*(?:\bparce que\b|\bcar\b|\bpuisque\b|\bmais\b|,\s*ce qui\b|\bvu que\b|\bdonc\b)/);
      const tailEnd = cut >= 0 ? tailStart + cut : sn.length;
      const tail = sn.slice(tailStart, tailEnd);
      if (PRONOUN_START.test(tail)) continue; // « en faire », « les faire » : renvoie à une tâche déjà citée
      let pos = 0; let prevVerb = null;
      for (const part of tail.split(/(\s*,\s*(?:et\s+|puis\s+)?|\s+(?:et|puis|ainsi que)\s+)/)) {
        const segStart = tailStart + pos; pos += part.length;
        if (/^\s*(,|et|puis|ainsi que)/.test(part) && /^[\s,]*(?:et|puis|ainsi que)?[\s,]*$/.test(part)) continue;
        // « …et de ranger ma chambre » : la préposition ne fait pas partie de la tâche.
        const trimmed = part.replace(/^\s+/, '').replace(/^(?:de|d')\s*(?=[a-z])/, '');
        if (!trimmed) continue;
        const lead = part.length - trimmed.length;
        const first = trimmed.split(/\s+/)[0];
        const isVerb = (INFINITIVE.test(first) && !NOT_VERBS.has(first)) || SUBJ[first];
        if (!isVerb && !(prevVerb && DETERMINER.test(trimmed))) { if (items.length) break; continue; }
        items.push({ start: segStart + lead, end: segStart + part.length, verb: isVerb ? (SUBJ[first] ?? null) : null, inherit: isVerb ? null : prevVerb });
        if (isVerb) prevVerb = SUBJ[first] ?? so.substr(segStart + lead, first.length);
      }
    }

    // 2. Chaque élément devient une tâche, ou se rattache à une tâche existante qu'il cite.
    const targets = [];
    for (const it of items) {
      const removeSpans = [...times.map((t) => [t.start - it.start, t.end - it.start]), ...durSpans.map(([a, b]) => [a - it.start, b - it.start])];
      for (const m of sn.slice(it.start, it.end).matchAll(STRIP_RE)) removeSpans.push([m.index, m.index + m[0].length]);
      let title = cleanTitle(so.slice(it.start, it.end), removeSpans);
      if (!title) continue;
      if (it.verb) title = capitalize(title.replace(/^\S+/, (w) => it.verb === norm(w) ? w : it.verb));
      if (it.inherit) title = capitalize(`${it.inherit.toLowerCase()} ${title.charAt(0).toLowerCase()}${title.slice(1)}`);
      const tn = norm(title);
      const existing = matchTask(tasks, tn, 0.6);
      const target = existing ?? newTask(title, tn);
      // « que je termine mon exposé » : il ne reste qu'une partie du travail.
      if (existing && /^(terminer|finir|finaliser|boucler|achever)\b/.test(tn)) existing.finishing = true;
      targets.push({ task: target, start: it.start, end: it.end, inherited: !!it.inherit });
    }

    // 3. Sans élément explicite : la phrase précise une tâche déjà citée (« Les courses, je peux les faire demain »).
    if (!targets.length) {
      const t = matchTask(tasks, sn, 0.01);
      if (t) targets.push({ task: t, start: 0, end: sn.length });
    }

    // 4. Contraintes de la phrase.
    // Une indication vise l'élément qui la contient ; hors de tout élément, toute la phrase.
    const scoped = (m) => {
      if (!m) return [];
      const inside = targets.filter((tg) => m.index >= tg.start && m.index < tg.end);
      return (targets.length > 1 && inside.length ? inside : targets).map((tg) => tg.task);
    };
    const daily = sn.match(DAILY_RE);
    for (const tm of times) {
      if (tm.kind === 'scope') { for (const tg of targets) tg.task.horizon = { from: tm.from, until: tm.until }; continue; }
      if (tm.kind !== 'when') continue;
      // Une seule tâche visée : elle prend tout. Plusieurs : l'expression s'applique à l'élément qui la contient.
      const target = targets.length === 1 ? targets[0].task
        : targets.find((tg) => tm.start >= tg.start && tm.end <= tg.end)?.task ?? null;
      const before = sn.slice(Math.max(0, tm.start - 40), tm.start);
      const isDeadline = DEADLINE_RE.test(before);
      // « A, B et C avant vendredi » : l'échéance finale vaut pour toute l'énumération.
      const last = targets[targets.length - 1];
      const all = isDeadline && targets.length > 1 && target === last?.task && targets.slice(1).every((x) => x.inherited)
        ? targets.map((x) => x.task) : null;
      if (!target) {
        if (isDeadline) questions.push(`À quelle tâche correspond l'échéance « ${so.slice(tm.start, tm.end).trim()} » ?`);
        continue;
      }
      const date = tm.date ?? (tm.minute != null && tm.minute < now.minute && !tm.part ? addDays(now.date, 1) : now.date);
      const label = so.slice(tm.start, tm.end).trim();
      if (isDeadline) {
        const assumed = tm.minute == null;
        // « avant vendredi » (jour seul) : au plus tard la veille au soir.
        const strictDay = !tm.part && tm.minute == null && /\bavant\b/.test(before);
        const dDate = strictDay ? addDays(date, -1) : date;
        const minute = tm.minute ?? (tm.part ? PARTS[tm.part][2] : 1439);
        for (const t of all ?? [target]) {
          t.deadline = { date: dDate, minute, label, assumed };
          if (assumed) t.notes.push(`Hypothèse : « ${label} » compris comme ${fmtDay(dDate, now.date).toLowerCase()} à ${fmtTime(minute)}.`);
        }
      } else if (!daily) {
        const [a, b] = tm.part ? PARTS[tm.part] : [480, 1350];
        target.window = tm.minute != null
          ? { date, start: tm.minute, end: tm.minute, fixed: true, label }
          : { date, start: a, end: b, fixed: false, label };
      }
    }
    for (const t of scoped(sn.match(URGENT_RE))) t.urgent = true;
    for (const t of scoped(sn.match(LOW_RE))) t.low = true;
    for (const t of scoped(daily)) t.recurrence = { type: 'daily', part: daily[1].includes('soir') ? 'soir' : daily[1].includes('matin') ? 'matin' : null };
    const count = sn.match(COUNT_RE);
    if (!daily) for (const t of scoped(count)) t.recurrence = { type: 'count', n: num(count[1]) };
    for (const t of scoped(sn.match(LITTLE_RE))) t.little = true;
    if (durMinutes) for (const t of scoped({ index: durSpans[durSpans.length - 1][0] })) t.explicitMinutes = durMinutes;

    // 5. Dépendances : « après avoir X », « avant de Y ».
    const after = sn.match(/\b(?:apres avoir|une fois que j'aurai|une fois que j'ai)\s+([^,.;]+)/);
    const beforeDep = sn.match(/\bavant de\s+([^,.;]+)/);
    // Résolues en fin d'analyse : la tâche citée peut n'être dictée qu'ensuite.
    const main = targets[0]?.task;
    if (main && after) pendingDeps.push({ task: main, phrase: after[1], after: true });
    if (main && beforeDep) pendingDeps.push({ task: main, phrase: beforeDep[1], after: false });
  }
  for (const d of pendingDeps) {
    const other = matchTask(tasks.filter((t) => t !== d.task), norm(d.phrase), 0.3);
    if (!other) continue;
    const [first, then] = d.after ? [other, d.task] : [d.task, other];
    if (!then.dependsOn.includes(first.id)) then.dependsOn.push(first.id);
  }

  function newTask(title, tn) {
    const t = { id: `t${++seq}`, title, norm: tn, deadline: null, window: null, recurrence: null, horizon: null,
      urgent: false, low: false, little: false, finishing: false, explicitMinutes: null, dependsOn: [], notes: [] };
    tasks.push(t);
    return t;
  }

  // 6. Finalisation : priorité, niveau d'alerte, durée, horizon.
  const defaultHorizon = globalScope ?? { from: now.date, until: addDays(now.date, 6) };
  for (const t of tasks) {
    t.horizon ??= defaultHorizon;
    const est = estimate(t.norm, opts.learned);
    t.estimate = est;
    t.minutes = t.explicitMinutes ?? (t.finishing ? Math.max(15, Math.round(est.minutes * 0.6 / 5) * 5) : est.minutes);
    if (t.finishing && !t.explicitMinutes) t.notes.push('Il ne reste qu\'à terminer : estimation réduite.');
    if (t.explicitMinutes) t.notes.push(`Durée indiquée : ${t.explicitMinutes} min.`);
    t.sessionMinutes = t.recurrence?.type === 'daily'
      ? (t.explicitMinutes ?? Math.max(25, Math.round((t.little ? est.min * 0.75 : est.minutes) / 5) * 5))
      : null;
    const hoursLeft = t.deadline ? daysBetween(now.date, t.deadline.date) * 24 + (t.deadline.minute - now.minute) / 60 : Infinity;
    t.priority = t.urgent ? 'URGENT' : hoursLeft <= 48 ? 'HIGH' : t.low ? 'LOW' : 'NORMAL';
    t.level = t.urgent && hoursLeft <= 24 ? 'BRR' : t.urgent ? 'HARCELEMENT' : t.low ? 'NORMAL' : 'INSISTANT';
    if (t.level === 'BRR') t.notes.push('Échéance explicitement urgente : Bully Mode: Nuclear proposé.');
    if (t.deadline && t.window && (t.window.date > t.deadline.date || (t.window.date === t.deadline.date && t.window.start >= t.deadline.minute))) {
      t.notes.push('Le créneau souhaité tombe après l\'échéance : il sera ignoré.');
      t.window = null;
    }
    if (!t.deadline && !t.window && !t.recurrence) t.notes.push('Aucune échéance donnée : placée selon tes disponibilités.');
  }

  return { tasks, questions, intent: PLAN_RE.test(n) || tasks.length > 1 ? 'plan' : 'add', scope: globalScope };
}
