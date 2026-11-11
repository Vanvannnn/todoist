// Tests de la plomberie du serveur (authentification, consentement, quotas, validation de la sortie du modèle).
// Le modèle est remplacé par une fonction de test : ces tests ne mesurent PAS la compréhension de l'IA
// (voir web/test/assistant-eval.mjs pour cela, avec une vraie clé).
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, DEFAULT_CONFIG, anthropicCaller, geminiCaller } from '../server.mjs';

const today = new Date().toISOString().slice(0, 10);
const now = { date: today, minute: 600 };
const okModel = (json) => async (input) => ({ json: typeof json === 'function' ? json(input) : json, model: 'test', usage: { input: 1, output: 1 } });
const task = (over = {}) => ({
  id: 't1', title: 'Faire les courses', description: '', planned_day: null, part_of_day: null, start_time: null, deadline: null,
  duration_minutes: null, duration_source: 'unknown', priority: 'normal', session_days: [], session_minutes: null, subtasks: [],
  depends_on: [], project: null, source_quote: '', confidence: 'high', ...over,
});

async function withServer(opts, fn) {
  const logs = [];
  const srv = createServer({ config: { ...DEFAULT_CONFIG, ...opts.config }, callModel: opts.callModel, log: (l) => logs.push(l) });
  await new Promise((r) => srv.listen(0, r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  try { await fn(base, logs); } finally { srv.close(); }
}
const post = (base, body, headers = {}) => fetch(`${base}/api/understand`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });

test('santé', () => withServer({ callModel: okModel({}) }, async (base) => {
  const r = await fetch(`${base}/api/health`);
  assert.equal(r.status, 200);
  assert.equal((await r.json()).ok, true);
}));

test('sans consentement : refus, le modèle n’est pas appelé', async () => {
  let called = false;
  await withServer({ callModel: async () => { called = true; } }, async (base) => {
    const r = await post(base, { text: 'Mardi courses', now });
    assert.equal(r.status, 403);
    assert.equal((await r.json()).error, 'consentement');
  });
  assert.equal(called, false);
});

test('jeton d’application exigé quand il est configuré', () => withServer({ config: { appToken: 's3cret' }, callModel: okModel({ tasks: [], constraints: [], questions: [], summary: '' }) }, async (base) => {
  assert.equal((await post(base, { text: 'x', now, consent: true })).status, 401);
  assert.equal((await post(base, { text: 'x', now, consent: true }, { Authorization: 'Bearer s3cret' })).status, 200);
}));

test('le jour de semaine faux du modèle est corrigé, la date du téléphone fait foi', () => withServer({
  callModel: okModel((input) => {
    // Le modèle « se trompe » : il annonce « jeudi » sur une date qui n'est pas un jeudi.
    const wrong = new Date(Date.UTC(...input.now.date.split('-').map((x, i) => +x - (i === 1 ? 1 : 0))));
    while (wrong.getUTCDay() !== 3) wrong.setUTCDate(wrong.getUTCDate() + 1); // un mercredi
    return { tasks: [task({ planned_day: { date: wrong.toISOString().slice(0, 10), said: 'jeudi' } })], constraints: [], questions: [], summary: 'ok' };
  }),
}, async (base) => {
  const r = await post(base, { text: 'Jeudi je fais les courses', now, tz: 'Europe/Paris', consent: true });
  assert.equal(r.status, 200);
  const body = await r.json();
  const d = body.understanding.tasks[0].planned_day.date;
  assert.equal(new Date(d + 'T12:00:00Z').getUTCDay(), 4, 'doit être un jeudi');
  assert.ok(body.issues.some((i) => i.startsWith('Date corrigée')));
}));

test('sortie de modèle inutilisable → 502, rien n’est inventé', () => withServer({ callModel: okModel({ nimporte: 'quoi' }) }, async (base) => {
  const r = await post(base, { text: 'Mardi courses', now, consent: true });
  assert.equal(r.status, 502);
}));

test('quota par minute', () => withServer({ config: { perMinute: 2 }, callModel: okModel({ tasks: [], constraints: [], questions: [], summary: '' }) }, async (base) => {
  const b = { text: 'x', now, consent: true };
  assert.equal((await post(base, b)).status, 200);
  assert.equal((await post(base, b)).status, 200);
  const r = await post(base, b);
  assert.equal(r.status, 429);
  assert.equal((await r.json()).error, 'trop_rapide');
}));

test('origine web non autorisée refusée', () => withServer({ callModel: okModel({}) }, async (base) => {
  const r = await post(base, { text: 'x', now, consent: true }, { Origin: 'https://evil.example' });
  assert.equal(r.status, 403);
}));

test('date du téléphone absurde refusée', () => withServer({ callModel: okModel({}) }, async (base) => {
  assert.equal((await post(base, { text: 'x', now: { date: '2001-01-01', minute: 0 }, consent: true })).status, 400);
}));

test('le contexte précédent est nettoyé avant d’être transmis au modèle', async () => {
  let seen = null;
  await withServer({ callModel: async (input) => { seen = input; return { json: { tasks: [], constraints: [], questions: [], summary: '' }, usage: {} }; } }, async (base) => {
    const previous = { tasks: [task({ title: '  Faire   les courses ', depends_on: ['t1', 'inconnu'] })], constraints: [], questions: [], summary: '' };
    assert.equal((await post(base, { text: 'Ah, et la pharmacie mardi', now, consent: true, previous })).status, 200);
  });
  assert.equal(seen.previous.tasks[0].title, 'Faire les courses');
  assert.deepEqual(seen.previous.tasks[0].depends_on, []);
});

test('les transcriptions ne sont jamais journalisées', () => withServer({ callModel: okModel({ tasks: [], constraints: [], questions: [], summary: '' }) }, async (base, logs) => {
  await post(base, { text: 'Secret médical très privé', now, consent: true });
  assert.ok(logs.length > 0);
  assert.ok(logs.every((l) => !l.includes('Secret')));
}));

test('sans clé API : erreur claire, pas de plantage', () => withServer({ callModel: anthropicCaller({ ...DEFAULT_CONFIG }) }, async (base) => {
  const saved = process.env.ANTHROPIC_API_KEY; delete process.env.ANTHROPIC_API_KEY;
  const r = await post(base, { text: 'x', now, consent: true });
  if (saved) process.env.ANTHROPIC_API_KEY = saved;
  assert.ok([500, 502, 503].includes(r.status), `statut ${r.status}`);
  assert.match((await r.json()).error, /config_ia|cle_invalide|ia_injoignable|ia_erreur/);
}));

test('Gemini : réponse lue (sans les parties « pensée »), quota 429 traduit', async () => {
  const saved = process.env.GEMINI_API_KEY; process.env.GEMINI_API_KEY = 'test';
  const json = { tasks: [], constraints: [], questions: [], summary: 'ok' };
  let sent;
  const ok = geminiCaller({ model: 'm' }, async (url, init) => { sent = { url, init }; return { ok: true, status: 200, json: async () => ({
    candidates: [{ finishReason: 'STOP', content: { parts: [{ thought: true, text: 'je réfléchis' }, { text: JSON.stringify(json) }] } }],
    usageMetadata: { promptTokenCount: 5, candidatesTokenCount: 3 } }) }; });
  const out = await ok({ text: 'x', now, tz: 'Europe/Paris', previous: null });
  assert.deepEqual(out.json, json);
  assert.equal(out.usage.input, 5);
  assert.match(sent.url, /models\/m:generateContent$/);
  assert.equal(sent.init.headers['x-goog-api-key'], 'test');
  const full = geminiCaller({ model: 'm' }, async () => ({ ok: false, status: 429, json: async () => ({}) }));
  await assert.rejects(full({ text: 'x', now, tz: 'Europe/Paris', previous: null }), (e) => e.code === 'ia_surchargee');
  if (saved) process.env.GEMINI_API_KEY = saved; else delete process.env.GEMINI_API_KEY;
});
