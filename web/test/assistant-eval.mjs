// Banque de scénarios de compréhension vocale : joue design/fixtures/assistant-scenarios.json et écrit un rapport.
//   node test/assistant-eval.mjs                                   → moteur local (hors ligne, sans IA)
//   node test/assistant-eval.mjs --server http://localhost:8787    → moteur IA (serveur Bullylist + vraie clé API)
//        [--token JETON] [--only 11,12]
// Le rapport (docs/rapports/) liste chaque échec : rien n'est masqué. Les appels au moteur IA sont facturés.
import fs from 'node:fs';
import { parseDictation, norm } from '../js/core/nlu.js';
import { toPlanInput } from '../js/core/understanding.js';

const FIX = JSON.parse(fs.readFileSync(new URL('../../design/fixtures/assistant-scenarios.json', import.meta.url)));
const arg = (k) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : null; };

export async function runLocal(s, now) {
  const r = parseDictation(s.turns.join(' '), now);
  return { items: r.tasks, constraints: r.constraints, questions: r.questions };
}

export async function runServer(s, now, url, token) {
  let previous = null; let last = null; let usage = { input: 0, output: 0 };
  for (const text of s.turns) {
    const res = await fetch(`${url.replace(/\/$/, '')}/api/understand`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ text, now, tz: 'Europe/Paris', previous, consent: true }),
    });
    const body = await res.json();
    if (!res.ok) throw new Error(`${res.status} ${body.error} : ${body.message}`);
    previous = body.understanding; last = body;
    usage = { input: usage.input + (body.usage?.input ?? 0), output: usage.output + (body.usage?.output ?? 0) };
  }
  const p = toPlanInput(last.understanding, now);
  return { items: p.tasks, constraints: p.constraints, questions: p.questions, understanding: last.understanding, issues: last.issues, usage };
}

const asList = (v) => (Array.isArray(v) ? v : [v]);
const inList = (x, v) => asList(v).includes(x ?? null);
const inRange = (x, v) => (Array.isArray(v) && v.length === 2 && typeof v[0] === 'number' ? x >= v[0] && x <= v[1] : asList(v).includes(x));
const words = (m) => norm(m).split(/\s+/).filter(Boolean);
const matches = (text, m) => words(m).every((w) => norm(text).includes(w));
const daysOf = (it) => {
  const s = new Set();
  if (it.window?.date) s.add(it.window.date);
  for (const d of it.recurrence?.dates ?? []) s.add(d);
  if (it.recurrence?.type === 'daily' && it.horizon) for (let d = it.horizon.from; d <= it.horizon.until; d = next(d)) s.add(d);
  return s;
};
const next = (d) => { const t = new Date(d + 'T12:00:00Z'); t.setUTCDate(t.getUTCDate() + 1); return t.toISOString().slice(0, 10); };

/** Vérifie un résultat ; renvoie la liste des écarts (vide = réussi). */
export function check(expect, out) {
  const errs = [];
  const { items, constraints = [], questions = [] } = out;
  const groups = new Set(items.map((it) => it.source?.task ?? it.id));
  if (expect.count != null && groups.size !== expect.count) errs.push(`${groups.size} tâche(s) au lieu de ${expect.count}`);
  for (const a of expect.absent ?? []) if (items.some((it) => matches(it.title, a))) errs.push(`« ${a} » aurait dû être supprimée`);
  if (expect.question && !questions.length) errs.push('aucune question de clarification');
  if (expect.noDayBefore) for (const it of items) if (it.window?.date && it.window.date < expect.noDayBefore) errs.push(`« ${it.title} » placée le ${it.window.date}, avant le ${expect.noDayBefore}`);
  for (const c of expect.constraints ?? []) {
    if (!constraints.some((x) => x.date === c.date && x.start <= c.covers && x.end > c.covers)) errs.push(`indisponibilité manquante le ${c.date} à ${Math.floor(c.covers / 60)}h`);
  }
  const seen = new Map();
  for (const it of items) {
    const k = `${norm(it.title)}|${it.window?.date ?? ''}`;
    if (seen.has(k)) errs.push(`doublon : « ${it.title} »`);
    seen.set(k, true);
  }
  const depsOf = (it, acc = new Set()) => { for (const id of it.dependsOn ?? []) if (!acc.has(id)) { acc.add(id); const o = items.find((x) => x.id === id); if (o) depsOf(o, acc); } return acc; };

  for (const e of expect.tasks ?? []) {
    const found = items.filter((it) => matches(it.title, e.match));
    if (e.exists) {
      if (!found.length && !items.some((it) => (it.subtasks ?? []).some((s) => matches(s, e.match)))) errs.push(`« ${e.match} » absente`);
      continue;
    }
    if (!found.length) { errs.push(`« ${e.match} » absente`); continue; }
    const fail = (it) => {
      const f = [];
      if ('day' in e && !(e.day === null ? !it.window : inList(it.window?.date, e.day))) f.push(`jour ${it.window?.date ?? 'aucun'} (attendu ${e.day ?? 'aucun'})`);
      if ('deadline' in e && !(e.deadline === null ? !it.deadline : inList(it.deadline?.date, e.deadline))) f.push(`échéance ${it.deadline?.date ?? 'aucune'} (attendu ${e.deadline ?? 'aucune'})`);
      if (e.notDeadline && it.deadline && asList(e.notDeadline).includes(it.deadline.date)) f.push(`échéance inventée ${it.deadline.date}`);
      if (e.dayOrDeadline && !(inList(it.window?.date, e.dayOrDeadline) || inList(it.deadline?.date, e.dayOrDeadline))) f.push(`ni jour ni échéance au ${asList(e.dayOrDeadline).join(' ou ')}`);
      if (e.minutes != null && !inRange(it.minutes, e.minutes)) f.push(`${it.minutes} min (attendu ${asList(e.minutes).join('–')})`);
      if (e.priority && !inList(it.priority, e.priority)) f.push(`priorité ${it.priority}`);
      if (e.afternoon && !(it.window && it.window.start >= 720)) f.push("pas placée l'après-midi");
      if (e.fixedStart != null && !(it.window?.fixed && it.window.start === e.fixedStart)) f.push(`heure ${it.window?.fixed ? it.window.start : 'libre'} (attendu ${e.fixedStart})`);
      if (e.within) {
        const [a, b] = e.within;
        const ok = it.window ? it.window.date >= a && it.window.date <= b : it.horizon && it.horizon.from >= a && it.horizon.until <= b;
        if (!ok) f.push(`hors de la période ${a} → ${b}`);
      }
      if (e.after) {
        const deps = depsOf(it);
        if (![...deps].some((id) => { const o = items.find((x) => x.id === id); return o && matches(o.title, e.after); })) f.push(`ne dépend pas de « ${e.after} »`);
      }
      return f;
    };
    // Séances multiples d'une même tâche (le moteur IA découpe parfois) : contrôles cumulés.
    const allDays = new Set(found.flatMap((it) => [...daysOf(it)]));
    const agg = [];
    if (e.sessionsOn) for (const d of e.sessionsOn) if (!allDays.has(d)) agg.push(`aucune séance le ${d}`);
    if (e.notOn) for (const d of e.notOn) if (allDays.has(d)) agg.push(`séance le ${d} (exclu)`);
    if (e.sessions != null) {
      const n = Math.max(found.length, ...found.map((it) => it.recurrence?.n ?? it.recurrence?.dates?.length ?? 0));
      if (n !== e.sessions) agg.push(`${n} séance(s) au lieu de ${e.sessions}`);
    }
    if (e.totalMinutes != null) {
      const total = found.reduce((a, it) => a + it.minutes, 0);
      if (total !== e.totalMinutes) agg.push(`${total} min au total (attendu ${e.totalMinutes})`);
    }
    const single = ['day', 'deadline', 'notDeadline', 'dayOrDeadline', 'minutes', 'priority', 'afternoon', 'fixedStart', 'within', 'after'].some((k) => k in e);
    const best = single ? found.map(fail).sort((a, b) => a.length - b.length)[0] : [];
    for (const f of [...best, ...agg]) errs.push(`« ${e.match} » : ${f}`);
  }
  return errs;
}

export function summarize(out) {
  const t = out.items.map((it) => `${it.title}${it.window ? ` [${it.window.date}${it.window.fixed ? ' ' + it.window.start : ''}]` : ''}${it.deadline ? ` ⏰${it.deadline.date}` : ''} ${it.minutes}min`);
  const c = (out.constraints ?? []).map((x) => `⛔ ${x.date} ${x.start}-${x.end}`);
  return [...t, ...c].join(' · ');
}

async function main() {
  const url = arg('--server'); const token = arg('--token') ?? process.env.BRR_APP_TOKEN;
  const only = arg('--only')?.split(',').map(Number);
  const engine = url ? 'ia' : 'local';
  const rows = []; let pass = 0; const byCat = {}; let usage = { input: 0, output: 0 };
  for (const s of FIX.scenarios.filter((x) => !only || only.includes(x.id))) {
    let out; let errs;
    try {
      out = url ? await runServer(s, FIX.now, url, token) : await runLocal(s, FIX.now);
      errs = check(s.expect, out);
      if (out.usage) usage = { input: usage.input + out.usage.input, output: usage.output + out.usage.output };
    } catch (e) { errs = [`erreur : ${e.message}`]; out = { items: [] }; }
    const ok = errs.length === 0;
    if (ok) pass++;
    (byCat[s.category] ??= [0, 0])[ok ? 0 : 1]++;
    rows.push({ s, ok, errs, out });
    console.log(`${ok ? '✓' : '✗'} ${String(s.id).padStart(2)} ${s.category.padEnd(26)} ${ok ? '' : errs.join(' ; ')}`);
  }
  const total = rows.length;
  console.log(`\n${engine === 'ia' ? 'Moteur IA' : 'Moteur local'} : ${pass}/${total} scénarios réussis.`);
  const md = [
    `# Compréhension vocale — moteur ${engine === 'ia' ? 'IA' : 'local (hors ligne)'}`, '',
    `Généré le ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC par \`web/test/assistant-eval.mjs\`. Date de référence des scénarios : lundi 9 novembre 2026, 10h00.`, '',
    `**${pass}/${total} scénarios réussis.**${engine === 'ia' ? ` Jetons : ${usage.input} en entrée, ${usage.output} en sortie.` : ''}`, '',
    '| Catégorie | Réussis | Échoués |', '|---|---|---|',
    ...Object.entries(byCat).map(([k, [a, b]]) => `| ${k} | ${a} | ${b} |`), '',
    '## Détail', '',
    ...rows.flatMap(({ s, ok, errs, out }) => [
      `### ${ok ? '✅' : '❌'} ${s.id}. ${s.category}`, '',
      ...s.turns.map((t, i) => `> ${s.turns.length > 1 ? `**Tour ${i + 1}.** ` : ''}${t}`), '',
      `Résultat : ${summarize(out) || '(rien)'}`, '',
      ...(ok ? [] : ['Écarts :', ...errs.map((e) => `- ${e}`), '']),
    ]),
  ].join('\n');
  const dir = new URL('../../docs/rapports/', import.meta.url);
  fs.mkdirSync(dir, { recursive: true });
  const file = new URL(`assistant-${engine}.md`, dir);
  fs.writeFileSync(file, md);
  console.log(`Rapport : docs/rapports/assistant-${engine}.md`);
}

if (import.meta.url === `file://${process.argv[1]}`) main();
