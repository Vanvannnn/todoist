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
const NUMBERS = { un: 1, une: 1, deux: 2, trois: 3, quatre: 4, cinq: 5, six: 6, sept: 7, huit: 8, dix: 10, quinze: 15, trente: 30 };
const num = (s) => NUMBERS[s] ?? parseInt(s, 10);

const STOP = new Set(['avec', 'pour', 'dans', 'mais', 'tout', 'toute', 'toutes', 'tous', 'cette', 'semaine', 'demain',
  'faire', 'faut', 'faudrait', 'absolument', 'vraiment', 'aussi', 'encore', 'bien', 'peux', 'dois', 'veux', 'aimerais',
  'voudrais', 'avant', 'apres', 'jour', 'jours', 'chaque', 'termine', 'terminer', 'parce', 'rendre', 'soir', 'matin',
  'midi', 'nuit', 'aujourd', "aujourd'hui", 'leur', 'leurs', 'notre', 'votre', 'quelque', 'chose', 'heure', 'heures',
  'minutes', 'fois', 'plus', 'moins', 'sont', 'etre', 'soit', 'avoir', 'peu']);

const ADVERBS = '(?:(?:absolument|vraiment|bien|aussi|encore|surtout|imperativement|d\'abord|ensuite|enfin|egalement|quand meme|quand-meme)\\s+)*';
// « je fais les courses », « je révise », « je rédigerai l'introduction » : présent et futur valent une intention.
const PRESENT_RE = /\bje\s+(?!(?:pense|crois|trouve|sais|veux|dois|peux|vais|compte|souhaite|prevois|devrais|aime|deteste|suppose|imagine|ai|suis|me|te|ne|l|les|la|le|lui|leur|en|y|voudrais|aimerais|dis|parle|reste|serai|aurai|serais|aurais)\b)(?=[a-z]{3,}\b)/g;
const PRESENT_IRREGULAR = { fais: 'faire', lis: 'lire', ecris: 'écrire', finis: 'finir', rends: 'rendre', vois: 'voir', prends: 'prendre',
  mets: 'mettre', sors: 'sortir', cours: 'courir', dors: 'dormir', ferai: 'faire', irai: 'aller', lirai: 'lire', ecrirai: 'écrire', verrai: 'voir' };
/** Forme conjuguée → infinitif (orig garde les accents : « révise » → « réviser »). */
function presentVerb(n, orig) {
  if (PRESENT_IRREGULAR[n]) return PRESENT_IRREGULAR[n];
  if (/^[a-z]{2,}(?:erai|irai)$/.test(n)) return orig.slice(0, -2);
  if (/^[a-z]{2,}rai$/.test(n)) return orig.slice(0, -2) + 'e';
  if (/^[a-z]{2,}e$/.test(n)) return orig + 'r';
  return null;
}
// Participes après « après avoir » : « fini les recherches » → « Finir les recherches ».
const PARTICIPLE = { fini: 'finir', fait: 'faire', lu: 'lire', ecrit: 'écrire', pris: 'prendre', rendu: 'rendre', vu: 'voir', mis: 'mettre' };
const TRIGGERS = [
  "j'aimerais", 'je voudrais', 'je veux', 'je dois', 'il faut que je', 'il faudrait que je', 'faut que je',
  "j'ai besoin de", 'je vais', 'je compte', 'je pense', "n'oublie pas de", 'ne pas oublier de', 'penser a',
  'rappelle-moi de', 'rappelle moi de', "j'ai a", 'je prevois de', 'je devrais', 'il me faut', 'je peux',
  'je dois encore', 'ajoute', 'ajouter', "n'oublie pas d'", "ne pas oublier d'", "j'ai envie de", 'je souhaite',
];
// « il faudrait absolument que je » : les adverbes peuvent s'intercaler avant « que je ».
const TRIGGER_RE = new RegExp('\\b(?:' + TRIGGERS.map((t) => t.replace(/ que je$/, ' ' + ADVERBS + 'que je').replace(/ /g, '\\s+'))
  .join('|') + ")(?:\\s*,?\\s+|(?<='))" + ADVERBS, 'g');

// Subjonctifs fréquents après « que je » → infinitif (clé normalisée, valeur accentuée).
const SUBJ = { termine: 'terminer', finisse: 'finir', fasse: 'faire', rende: 'rendre', envoie: 'envoyer', appelle: 'appeler',
  aille: 'aller', prenne: 'prendre', ecrive: 'écrire', lise: 'lire', revise: 'réviser', prepare: 'préparer', range: 'ranger',
  avance: 'avancer', commence: 'commencer', reponde: 'répondre', paie: 'payer', sorte: 'sortir' };
const STRIP_RE = /\b(absolument|imperativement|vraiment|urgent|urgente|sans faute|obligatoirement|a tout prix|au plus vite|en priorite|un peu|\d+ fois|deux fois|trois fois|chaque jour|tous les jours|quotidiennement|chaque soir|tous les soirs|chaque matin|tous les matins|aussi|egalement)\b/g;
// « prévois les révisions », « ajoute le rendez-vous » : l'impératif peut introduire un simple groupe nominal.
const NOUN_TRIGGER_RE = /\b(?:prevois|planifie|programme|cale|ajoute|note)\s+(?:moi\s+)?(?!(?:ma|la|cette|notre|mon)\s+(?:semaine|journee|matinee|soiree|planning|emploi)\b)/g;
// « (que je) prévoie un peu de temps pour relire… » : la tâche est ce qui suit.
const TIME_FOR_RE = /^(?:prevoie|prevoir|prendre|garder|trouver|bloquer)\s+(?:(?:un peu de|un peu|du|le|un)\s+)?temps\s+(?:pour|de)\s+/;
// « mettre le rendez-vous chez le dentiste » : le verbe ne fait que demander l'ajout.
const META_VERB_RE = /^(?:mettre|ajouter|noter|inscrire|programmer|planifier|prevoir)\s+(?:(?:le|la|les|un|une|des|du|mon|ma|mes)\s+|l')(?=\S)/;
// Conversation : corrections, déplacements, annulations, indisponibilités.
const CORR_SPLIT_RE = /,\s*(?=(?:enfin non|ah non|non,? plutot|ou plutot)\b)/g;
const CORR_RE = /^[\s,]*(?:(?:ah|oh|euh|bon|et|mais)[\s,]+)*(?:enfin non|non|en fait|finalement|plutot|ou plutot|je me suis trompe|attends|correction)\b/;
const MOVE_RE = /\b(?:deplace|decale|reporte|mets|met|passe|bascule)\b/;
// « j'ai oublié quelque chose », « n'oublie pas de » ne sont pas des annulations.
const CANCEL_RE = /(?<!n'|j'ai |j'avais )\b(?:annule|supprime|enleve|retire|laisse tomber|oublie)\b(?!\s+(?:quelque|de\b|d'|qu))/;
const SAUF_RE = /\bsauf\s+(?:le\s+)?(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)s?\b/g;
const START_ONLY_RE = /^(?:commencer|attaquer|demarrer|debuter|m'y mettre|m'y atteler)$/;
const HAND_IN_RE = /^(?:rendre|remettre|deposer|soumettre|livrer)\b/;
const FILLER_RE = /\b(?:euh+|heu+|hum+|bah)\b[,.]?\s*/gi;
const BUSY_RE = /\b(?:j'ai|je suis (?:en|au|a la|a l'|chez)|je travaille|je bosse)\s+(?:(?:un|une|le|la|mon|ma|des|du)\s+)?(?:cours|td|tp|travail|boulot|rendez-vous|rdv|reunion|examen|partiel|stage|sport|entrainement|match|dentiste|medecin|kine)\b/;
const UNAVAIL_RE = /\b(?:pas disponible|pas dispo|indisponible|pas libre|je suis pris|je suis prise|ne me (?:mets|met|prevois|planifie) rien)\b/;
// Jour cité en tête de phrase (« Et dimanche, je veux… ») : il vaut pour toute la phrase et les suivantes.
const HEAD_GAP_RE = /^[\s,]*(?:(?:et|alors|ah|oh|puis|ensuite|bon|sinon|aussi|oui|donc|pour|quant a)[\s,]+)*$/;
// « ce que j'ai fait mercredi » : un jour passé cité en référence, pas une date de planification.
const PAST_REF_RE = /\b(?:j'ai|on a|j'avais|tu as)\s+\S+\s*$/;
const INFINITIVE = /^(?:s'|se\s+)?[a-z-]+(?:er|ir|re|oir)$/;
const PRONOUN_START = /^(?:(?:en|les|le|la|y|lui|leur|m'en|m'y)\s+|l')[a-z]/;
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
  ['in', /\bdans\s+(\d+|un|une|deux|trois|quatre|cinq|huit|dix|quinze)\s+(jours?|semaines?|heures?|minutes?|min)\b/g],
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
      if ((type === 'weekday' || type === 'date' || type === 'rel') && PAST_REF_RE.test(n.slice(Math.max(0, start - 30), start))) continue;
      if (type === 'weekday' && /\bsauf\s+(?:le\s+)?$/.test(n.slice(Math.max(0, start - 10), start))) continue; // « sauf mercredi »
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
      if (v.date && !prev.date) { prev.date = v.date; prev.sameDay = v.sameDay; }
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
      return { kind: 'when', date: addDays(now.date, d), part: null, minute: null, sameDay: d === 0 };
    }
    case 'date': {
      const day = +m[1];
      if (day < 1 || day > 31) return { kind: 'none' };
      const [y, mo] = now.date.split('-').map(Number);
      let month = m[2] ? MONTHS.indexOf(m[2]) + 1 : mo;
      let year = y;
      const iso = (yy, mm) => `${yy}-${String(mm).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
      // Un mois cité explicitement et déjà passé : on le signale au lieu de déplacer la date en silence.
      if (m[2] && iso(year, month) < now.date) return { kind: 'past', label: m[0] };
      if (iso(year, month) < now.date && ++month > 12) { month = 1; year++; }
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

// « une heure et demie », « deux heures et quart », « une heure trente ».
const DURATION_HALF_RE = /\b(?:(?:pendant|durant|environ|en)\s+)?(\d+|une|un|deux|trois|quatre)\s+heures?\s+(?:et\s+(demie|quart)|(trente|quinze|quarante-cinq|\d{2}))\b/g;

function scanDurations(n) {
  const list = [];
  for (const m of n.matchAll(DURATION_HALF_RE)) {
    if (/\b(?:a|vers|des|avant|apres|jusqu'a)\s+$/.test(n.slice(Math.max(0, m.index - 12), m.index))) continue; // « à 10 heures et demie »
    const extra = m[2] ? (m[2] === 'demie' ? 30 : 15) : ({ trente: 30, quinze: 15, 'quarante-cinq': 45 }[m[3]] ?? +m[3]);
    list.push({ start: m.index, end: m.index + m[0].length, minutes: num(m[1]) * 60 + extra });
  }
  DURATION_RE.lastIndex = 0;
  for (const m of n.matchAll(DURATION_RE)) {
    const start = m.index; const end = m.index + m[0].length;
    if (list.some((d) => start < d.end && end > d.start)) continue;
    const marked = /^(pendant|durant|en|prendre|prendra|prend|environ|compter|faut)\b/.test(m[0]);
    let minutes;
    if (m[5]) minutes = m[5].includes('quart') ? 15 : 30;
    else if (m[4]) minutes = num(m[1]);
    else if (m[2]) {
      if (!marked && num(m[1]) > 4) continue; // « 21h30 » est une heure, « 1h30 » une durée
      minutes = num(m[1]) * 60 + (m[3] ? +m[3] : 0);
    }
    else continue;
    list.push({ start, end, minutes });
  }
  list.sort((a, b) => a.start - b.start);
  return { spans: list.map((d) => [d.start, d.end]), minutes: list.length ? list[list.length - 1].minutes : null, list };
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

/** Toutes les tâches à égalité avec la meilleure correspondance (« le dossier » vise chaque séance du dossier). */
function matchTasks(tasks, text, minRatio) {
  const words = tokens(text).map(stem);
  const scored = [];
  for (const t of tasks) {
    const kws = keywords(t.norm);
    if (!kws.length) continue;
    const hits = kws.filter((k) => words.some((w) => kwMatch(w, k))).length;
    const ratio = hits / kws.length;
    if (hits > 0 && ratio >= minRatio) scored.push({ t, score: hits + ratio });
  }
  const top = Math.max(0, ...scored.map((s) => s.score));
  return scored.filter((s) => s.score === top).map((s) => s.t);
}

/** Plage horaire d'une indisponibilité dans une proposition (« après 18 heures », « de 14h à 16h », « le matin »). */
function busyRange(clause, part) {
  let m = clause.match(/\b(?:de|entre)\s+(\d{1,2})\s*(?:h|heures?)\s*(\d{2})?\s+(?:a|et)\s+(\d{1,2})\s*(?:h|heures?)\s*(\d{2})?/);
  if (m) return [+m[1] * 60 + (+m[2] || 0), +m[3] * 60 + (+m[4] || 0)];
  m = clause.match(/\b(?:apres|a partir de|des)\s+(\d{1,2})\s*(?:h|heures?)\s*(\d{2})?/);
  if (m) return [+m[1] * 60 + (+m[2] || 0), 1440];
  m = clause.match(/\b(?:avant|jusqu'a)\s+(\d{1,2})\s*(?:h|heures?)\s*(\d{2})?/);
  if (m) return [0, +m[1] * 60 + (+m[2] || 0)];
  if (/\btoute la journee\b/.test(clause)) return [0, 1440];
  if (part) return [PARTS[part][0], PARTS[part][1]];
  return null;
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
  const orig = text.normalize('NFC').replace(FILLER_RE, '').replace(/\s+/g, ' ').trim();
  const n = norm(orig);
  const tasks = [];
  const questions = [];
  let globalScope = null;
  let seq = 0;
  const pendingDeps = [];
  const constraints = [];
  let dayCtx = null; // jour de la « section » en cours (« Mardi, … » vaut pour les phrases suivantes)
  let lastTargets = []; // tâches visées par la dernière phrase qui en citait

  const sentences = [];
  const sre = /[^.!?;\n]+[.!?;\n]*/g;
  for (const m of orig.matchAll(sre)) {
    if (!m[0].trim()) continue;
    // « deux heures, enfin non, une heure trente » : la correction forme sa propre phrase.
    let a = m.index;
    for (const c of n.slice(m.index, m.index + m[0].length).matchAll(CORR_SPLIT_RE)) {
      sentences.push({ start: a, end: m.index + c.index }); a = m.index + c.index + 1;
    }
    sentences.push({ start: a, end: m.index + m[0].length });
  }

  for (const s of sentences) {
    const so = orig.slice(s.start, s.end);
    const sn = n.slice(s.start, s.end);
    const { spans: durSpans, minutes: durMinutes, list: durList } = scanDurations(sn);
    const times = scanTimes(sn, now, durSpans);
    const scope = times.find((t) => t.kind === 'scope');
    if (scope && !globalScope && tasks.length === 0) globalScope = scope;
    if (scope) dayCtx = null;
    for (const t of times.filter((x) => x.kind === 'past')) questions.push(`« ${so.slice(t.start, t.end).trim()} » est déjà passé : quelle date voulais-tu dire ?`);
    const dated = times.filter((t) => t.kind === 'when' && t.date);
    const head = dated.find((t) => HEAD_GAP_RE.test(sn.slice(0, t.start)) && !DEADLINE_RE.test(sn.slice(0, t.start))) ?? null;
    const used = new Set(); // expressions temporelles déjà consommées (indisponibilité)

    // 0. Indisponibilités : « mardi j'ai cours le matin », « mercredi, ne me mets rien après 18 heures ».
    const busy = sn.match(BUSY_RE) ?? sn.match(UNAVAIL_RE);
    if (busy) {
      const cStart = sn.lastIndexOf(',', busy.index) + 1;
      const cEndRel = sn.slice(busy.index).search(/,|\bdonc\b|\bmais\b|\balors\b/);
      const cEnd = cEndRel >= 0 ? busy.index + cEndRel : sn.length;
      const inClause = times.filter((t) => t.kind === 'when' && t.start >= cStart && t.end <= cEnd);
      inClause.forEach((t) => used.add(t));
      const date = inClause.find((t) => t.date)?.date ?? head?.date ?? dayCtx?.date ?? null;
      const part = inClause.find((t) => t.part)?.part ?? (head && !inClause.length ? head.part : null);
      const range = busyRange(sn.slice(cStart, cEnd), part) ?? (UNAVAIL_RE.test(busy[0]) ? [0, 1440] : null);
      if (date && range) constraints.push({ date, start: range[0], end: Math.min(range[1], 1440), label: capitalize(so.slice(busy.index, cEnd).replace(/[\s,.;!?]+$/, '').trim()) });
      else if (!date) questions.push(`Quel jour correspond « ${so.slice(busy.index, cEnd).trim()} » ?`);
    }

    // 1. Énumérations introduites par une intention (« j'aimerais… », « je dois… »).
    const items = [];
    TRIGGER_RE.lastIndex = 0;
    const starts = [...sn.matchAll(TRIGGER_RE)].map((m) => m.index + m[0].length);
    NOUN_TRIGGER_RE.lastIndex = 0;
    const nounStarts = new Set([...sn.matchAll(NOUN_TRIGGER_RE)].map((m) => m.index + m[0].length));
    starts.push(...[...nounStarts].filter((i) => !starts.includes(i)));
    PRESENT_RE.lastIndex = 0;
    const presentStarts = new Set([...sn.matchAll(PRESENT_RE)].map((m) => m.index + m[0].length).filter((i) => !starts.includes(i)));
    starts.push(...presentStarts);
    starts.sort((a, b) => a - b);
    // Sans formule d'intention : liste directe (« Réviser les maths… », « Planifie ma semaine : … », « Si j'ai le temps, lire… »).
    if (!starts.length) {
      const cands = [0, sn.indexOf(':') + 1, sn.indexOf(',') + 1].filter((i, k) => k === 0 || i > 0);
      const first = cands.map((i) => i + (sn.slice(i).length - sn.slice(i).trimStart().length))
        .find((i) => { const w = sn.slice(i).split(/\s+/)[0]; return i < sn.length && INFINITIVE.test(w) && !NOT_VERBS.has(w); });
      if (first != null) starts.push(first);
    }
    for (let tailStart of starts) {
      const nounOk = nounStarts.has(tailStart);
      const tf = sn.slice(tailStart).match(TIME_FOR_RE);
      if (tf) tailStart += tf[0].length;
      const cut = sn.slice(tailStart).search(/\s*(?:\bparce que\b|\bcar\b|\bpuisque\b|\bmais\b|,\s*ce qui\b|\bvu que\b|\bdonc\b)/);
      const tailEnd = cut >= 0 ? tailStart + cut : sn.length;
      const tail = sn.slice(tailStart, tailEnd);
      if (!nounOk && PRONOUN_START.test(tail)) continue; // « en faire », « les faire » : renvoie à une tâche déjà citée
      let pos = 0; let prevVerb = null;
      const subjCtx = /\bque\s+(?:je|j')\s*$/.test(sn.slice(0, tailStart).trimEnd() + ' ') || /que\s+je\s+$/.test(sn.slice(0, tailStart));
      for (const part of tail.split(/(\s*,\s*(?:et\s+|puis\s+)?|\s+(?:et|puis|ainsi que)\s+)/)) {
        const segStart = tailStart + pos; pos += part.length;
        if (/^\s*(,|et|puis|ainsi que)/.test(part) && /^[\s,]*(?:et|puis|ainsi que)?[\s,]*$/.test(part)) continue;
        // « …et de ranger ma chambre » : la préposition ne fait pas partie de la tâche.
        // « …et que je l'emmène » : on retire « que je » et le pronom complément.
        const afterQue = /^\s*que\s+(?:je\s+|j')/.test(part);
        const trimmed = part.replace(/^\s+/, '').replace(/^(?:de|d')\s*(?=[a-z])/, '')
          .replace(/^que\s+(?:je\s+|j')/, '').replace(/^(?:l'|(?:le|la|les|lui|leur)\s+)(?=[a-z]+e\b)/, '');
        if (!trimmed) continue;
        const lead = part.length - trimmed.length;
        const first = trimmed.split(/\s+/)[0].replace(/[.!?;:,]+$/, "");
        // Subjonctif régulier du 1er groupe (« lave », « emmène ») → infinitif.
        const pres = presentStarts.has(tailStart) && segStart + lead === tailStart ? presentVerb(first, so.substr(segStart + lead, first.length)) : null;
        const subj = SUBJ[first] ?? pres ?? ((subjCtx || afterQue) && /^[a-z]{3,}e$/.test(first) ? norm(so.substr(segStart + lead, first.length)).replace(/e(?=[a-z]e$)/, 'e') + 'r' : null);
        const isVerb = (INFINITIVE.test(first) && !NOT_VERBS.has(first)) || subj;
        const noun = !isVerb && !prevVerb && nounOk && DETERMINER.test(trimmed);
        if (!isVerb && !noun && !(prevVerb && DETERMINER.test(trimmed))) { if (items.length) break; continue; }
        items.push({ start: segStart + lead, end: segStart + part.length, verb: isVerb ? (subj ?? null) : null, inherit: isVerb ? null : prevVerb, noun });
        if (isVerb) prevVerb = subj ?? so.substr(segStart + lead, first.length);
      }
    }

    // 2. Chaque élément devient une tâche, ou se rattache à une tâche existante qu'il cite.
    // Jour qui vaut pour les tâches de la phrase : celui cité en tête, sinon celui de la section en cours.
    const sentenceDay = head ?? (dated.some((t) => !used.has(t)) ? null : dayCtx);
    const targets = [];
    for (const it of items) {
      const removeSpans = [...times.map((t) => [t.start - it.start, t.end - it.start]), ...durSpans.map(([a, b]) => [a - it.start, b - it.start])];
      for (const m of sn.slice(it.start, it.end).matchAll(STRIP_RE)) removeSpans.push([m.index, m.index + m[0].length]);
      let title = cleanTitle(so.slice(it.start, it.end), removeSpans);
      if (!title) continue;
      if (it.verb) title = capitalize(title.replace(/^\S+/, (w) => it.verb === norm(w) ? w : it.verb));
      if (it.inherit) title = capitalize(`${it.inherit.toLowerCase()} ${title.charAt(0).toLowerCase()}${title.slice(1)}`);
      const meta = norm(title).match(it.noun ? DETERMINER : META_VERB_RE);
      if (meta && title.length > meta[0].length + 2) title = capitalize(title.slice(meta[0].length).trimStart());
      const tn = norm(title);
      // « mais je veux commencer mardi » : pas une nouvelle tâche, le jour de début de celle qui vient d'être citée.
      if (START_ONLY_RE.test(tn)) {
        const ref = targets[0]?.task ?? lastTargets[0];
        if (ref) { targets.push({ task: ref, start: it.start, end: it.end, inherited: false, fresh: false }); continue; }
      }
      let existing = matchTask(tasks, tn, 0.6);
      // « avancer le dossier » mardi puis « terminer le dossier » jeudi : deux séances distinctes, la seconde après la première.
      const splitDay = existing && sentenceDay?.date && existing.window && existing.window.date !== sentenceDay.date;
      if (splitDay) existing = null;
      // Tâche créée d'après « après avoir fini les recherches » puis dite explicitement : la formulation explicite l'emporte.
      if (existing?.fromDep) { existing.title = title; existing.norm = tn; existing.fromDep = false; }
      const target = existing ?? newTask(title, tn);
      if (splitDay) {
        const prev = matchTask(tasks.filter((x) => x !== target), tn, 0.6);
        if (prev) target.dependsOn.push(prev.id);
      }
      // « que je termine mon exposé » : il ne reste qu'une partie du travail.
      if ((existing || splitDay) && /^(terminer|finir|finaliser|boucler|achever)\b/.test(tn)) target.finishing = true;
      targets.push({ task: target, start: it.start, end: it.end, inherited: !!it.inherit, fresh: !existing });
    }

    // Conversation : correction (« enfin non, plutôt… »), déplacement (« mets ça dimanche »), annulation (« laisse tomber les courses »).
    const correction = CORR_RE.test(sn);
    const cancel = !busy && sn.match(CANCEL_RE);
    if (!targets.length && cancel) {
      const victims = matchTasks(tasks, sn.slice(cancel.index), 0.3);
      for (const v of victims.length ? victims : lastTargets.slice(-1)) {
        tasks.splice(tasks.indexOf(v), 1);
        for (const o of tasks) o.dependsOn = o.dependsOn.filter((id) => id !== v.id);
      }
      lastTargets = [];
      continue;
    }
    const move = !busy && (correction || MOVE_RE.test(sn)) && !targets.length;
    if (move) {
      const whens = times.filter((t) => t.kind === 'when' && !used.has(t));
      const dest = whens[whens.length - 1];
      if (dest || durMinutes) {
        // « déplace la tâche de mardi à samedi » : la source est le premier jour cité.
        const src = whens.length > 1 && whens[0].date ? tasks.filter((t) => t.window?.date === whens[0].date) : [];
        const named = matchTasks(tasks, sn, 0.3);
        const victims = src.length ? src : named.length ? named : lastTargets;
        for (const v of victims) {
          if (durMinutes) v.explicitMinutes = durMinutes;
          if (dest) {
            const date = dest.date ?? v.window?.date ?? dayCtx?.date ?? now.date;
            const [a, b] = dest.part ? PARTS[dest.part] : [480, 1350];
            const label = so.slice(dest.start, dest.end).trim();
            v.window = dest.minute != null ? { date, start: dest.minute, end: dest.minute, fixed: true, label } : { date, start: a, end: b, fixed: false, label };
          }
        }
        if (victims.length) { lastTargets = victims; continue; }
      }
    }

    // 3. Sans élément explicite : la phrase précise une tâche déjà citée (« Les courses, je peux les faire demain »).
    if (!targets.length) {
      // Plusieurs durées (« les recherches une heure, les diapositives deux heures ») : chacune vise la tâche de sa proposition.
      if (durList.length > 1) {
        let from = 0;
        for (const d of durList) {
          const t = matchTask(tasks, sn.slice(from, d.end), 0.01);
          if (t) t.explicitMinutes = d.minutes;
          from = d.end;
        }
        continue;
      }
      const ts = matchTasks(tasks, sn, 0.01);
      for (const t of ts) targets.push({ task: t, start: 0, end: sn.length, whole: true });
      // « Je pense qu'une heure et demie devrait suffire » : précision sur la tâche qui vient d'être citée.
      // « 2 heures pour la physique » nomme autre chose : on ne l'attribue pas au hasard.
      const namesOther = /\bpour\s+(?:le|la|les|l'|mon|ma|mes)\s*\S/.test(sn);
      if (!ts.length && (correction || (durMinutes && !namesOther)) && lastTargets.length) for (const t of lastTargets) targets.push({ task: t, start: 0, end: sn.length });
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
      if (tm.kind !== 'when' || used.has(tm)) continue;
      const before = sn.slice(Math.max(0, tm.start - 40), tm.start);
      // « Dans deux semaines, je dois rendre mon projet » : rendre un travail est toujours une échéance.
      const handIn = targets.length === 1 && HAND_IN_RE.test(targets[0].task.norm);
      const isDeadline = DEADLINE_RE.test(before) || handIn;
      // Jour en tête de phrase : il vaut pour toutes les tâches de la phrase.
      if (tm === head && !isDeadline && targets.length > 1 && !daily) {
        const [a, b] = tm.part ? PARTS[tm.part] : [480, 1350];
        const label = so.slice(tm.start, tm.end).trim();
        for (const tg of targets) if (!tg.task.window) setWindow(tg.task, tm.minute != null ? { date: tm.date, start: tm.minute, end: tm.minute, fixed: true, label } : { date: tm.date, start: a, end: b, fixed: false, label }, tm);
        continue;
      }
      // Une seule tâche visée : elle prend tout. Plusieurs : l'expression s'applique à l'élément qui la contient.
      const target = targets.length === 1 ? targets[0].task
        : targets.find((tg) => tm.start >= tg.start && tm.end <= tg.end)?.task ?? null;
      // « A, B et C avant vendredi » : l'échéance finale vaut pour toute l'énumération.
      const last = targets[targets.length - 1];
      // Phrase qui précise plusieurs tâches citées (« le dossier » = chaque séance du dossier) : toutes sont visées.
      const all = targets.length > 1 && targets.every((x) => x.whole) ? targets.map((x) => x.task)
        : isDeadline && targets.length > 1 && target === last?.task && targets.slice(1).every((x) => x.inherited)
          ? targets.map((x) => x.task) : null;
      if (!target) {
        if (isDeadline) questions.push(`À quelle tâche correspond l'échéance « ${so.slice(tm.start, tm.end).trim()} » ?`);
        continue;
      }
      // Moment sans jour (« l'après-midi ») : le jour de la phrase ou de la section, sinon aujourd'hui.
      const date = tm.date ?? sentenceDay?.date ?? (tm.minute != null && tm.minute < now.minute && !tm.part ? addDays(now.date, 1) : now.date);
      const label = so.slice(tm.start, tm.end).trim();
      if (tm.sameDay) target.notes.push(`Hypothèse : « ${label} » compris comme aujourd'hui. Dis « ${label} prochain » pour la semaine suivante.`);
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
        for (const t of all ?? [target]) t.window = tm.minute != null
          ? { date, start: tm.minute, end: tm.minute, fixed: true, label }
          : { date, start: a, end: b, fixed: false, label };
      }
    }
    // Phrase sans date : les nouvelles tâches héritent du jour de la section (« Mercredi, … . Je voudrais aussi… »).
    if (sentenceDay && sentenceDay !== head && !daily) {
      const [a, b] = sentenceDay.part ? PARTS[sentenceDay.part] : [480, 1350];
      for (const tg of targets) if (tg.fresh && !tg.task.window && !tg.task.recurrence) setWindow(tg.task, { date: sentenceDay.date, start: a, end: b, fixed: false, label: sentenceDay.label }, sentenceDay);
    }
    for (const t of scoped(sn.match(URGENT_RE))) t.urgent = true;
    for (const t of scoped(sn.match(LOW_RE))) t.low = true;
    const except = [...sn.matchAll(SAUF_RE)].map((m) => DAY_NAMES.indexOf(m[1]) + 1);
    for (const t of scoped(daily)) { t.recurrence = { type: 'daily', part: daily[1].includes('soir') ? 'soir' : daily[1].includes('matin') ? 'matin' : null }; t.except = except; }
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
    // La tâche citée seulement dans la dépendance (« avant de commencer les diapositives ») existe aussi.
    for (const d of [after, beforeDep]) {
      if (!main || !d || matchTask(tasks.filter((t) => t !== main), d[1], 0.3)) continue;
      const ws = d[1].trim().split(/\s+/);
      if (ws.length < 2 || !DETERMINER.test(ws.slice(1).join(' '))) continue; // « avant de cuisiner pour samedi » : pas d'objet, pas de tâche
      const at = d.index + d[0].length - d[1].length;
      const origWords = so.slice(at, at + d[1].length).trim().split(/\s+/);
      const verb = PARTICIPLE[ws[0]] ?? (INFINITIVE.test(ws[0]) && !NOT_VERBS.has(ws[0]) ? origWords[0] : /^[a-z]{3,}e$/.test(ws[0]) ? origWords[0] + 'r' : null);
      if (!verb) continue;
      const title = capitalize([verb, ...origWords.slice(1)].join(' '));
      newTask(title, norm(title)).fromDep = true;
    }

    // La section change avec un nouveau jour planifié (pas une échéance) ; une phrase de tâches devient le contexte des précisions.
    const newDay = head ?? dated.find((t) => !DEADLINE_RE.test(sn.slice(Math.max(0, t.start - 40), t.start)));
    if (newDay) dayCtx = { date: newDay.date, part: newDay.part, label: so.slice(newDay.start, newDay.end).trim(), sameDay: newDay.sameDay };
    if (targets.length) lastTargets = targets.map((tg) => tg.task);
  }

  function setWindow(task, w, tm) {
    task.window = w;
    if (tm?.sameDay && !task.notes.some((x) => x.startsWith('Hypothèse : « ' + w.label))) task.notes.push(`Hypothèse : « ${w.label} » compris comme aujourd'hui. Dis « ${w.label} prochain » pour la semaine suivante.`);
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
    // « chaque jour sauf mercredi » : une séance par jour de la période, sans les jours exclus.
    if (t.recurrence?.type === 'daily' && t.except?.length) {
      const dates = [];
      for (let d = t.horizon.from > now.date ? t.horizon.from : now.date; d <= t.horizon.until; d = addDays(d, 1)) if (!t.except.includes(isoDay(d))) dates.push(d);
      t.recurrence = { type: 'days', dates, part: t.recurrence.part };
    }
    delete t.except;
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

  return { tasks, questions, constraints, intent: PLAN_RE.test(n) || tasks.length > 1 ? 'plan' : 'add', scope: globalScope };
}
