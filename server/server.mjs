// Serveur de compréhension vocale de Bullylist.
// L'application envoie la transcription (jamais l'audio) ; le serveur appelle Claude avec la clé API qu'il est seul à connaître,
// valide la réponse et renvoie une compréhension structurée. Aucune transcription n'est écrite dans les journaux.
//
//   ANTHROPIC_API_KEY=… node server.mjs      (ou GEMINI_API_KEY dans server/.env, puis npm start)
//
// Variables : voir docs/ASSISTANT-IA.md.
import http from 'node:http';
import Anthropic from '@anthropic-ai/sdk';
import { SCHEMA, calendarContext, validateUnderstanding } from '../web/js/core/understanding.js';
import { daysBetween } from '../web/js/core/dates.js';
import { SYSTEM_PROMPT, userMessage } from './prompt.mjs';

const env = (k, d) => process.env[k] || d;
const provider = env('BRR_PROVIDER', process.env.GEMINI_API_KEY ? 'gemini' : 'anthropic');
export const DEFAULT_CONFIG = {
  port: +env('PORT', 8787),
  provider,
  model: env('BRR_MODEL', provider === 'gemini' ? 'gemini-3.5-flash' :'claude-opus-5-5'),
  effort: env('BRR_EFFORT', 'high'),
  appToken: env('BRR_APP_TOKEN', ''),
  dailyLimit: +env('BRR_DAILY_LIMIT', 60),
  perMinute: +env('BRR_PER_MINUTE', 6),
  allowedOrigins: env('BRR_ALLOWED_ORIGINS', 'http://localhost:8080,http://127.0.0.1:8080').split(',').map((s) => s.trim()).filter(Boolean),
  maxChars: 6000,
};

class HttpError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

/** Appel réel au modèle. Renvoie l'objet JSON produit (non validé) et l'usage. */
export function anthropicCaller(config) {
  let client = null;
  return async ({ text, now, tz, previous }) => {
    let response;
    try {
      client ??= new Anthropic(); // ANTHROPIC_API_KEY, ou un profil `ant auth login`
      response = await client.beta.messages.create({
        model: config.model,
        max_tokens: 16000,
        betas: ['server-side-fallback-2026-07-01'],
        fallbacks: 'default',
        thinking: { type: 'adaptive' },
        output_config: { effort: config.effort, format: { type: 'json_schema', schema: SCHEMA } },
        system: [{ type: 'text', text: SYSTEM_PROMPT, cache_control: { type: 'ephemeral' } }],
        messages: [{ role: 'user', content: userMessage({ text, now, tz, previous, calendar: calendarContext(now) }) }],
      });
    } catch (e) {
      if (e instanceof Anthropic.AuthenticationError) throw new HttpError(500, 'cle_invalide', 'Clé API refusée par le fournisseur IA.');
      if (e instanceof Anthropic.RateLimitError) throw new HttpError(503, 'ia_surchargee', 'Le fournisseur IA limite les appels : réessaie dans un instant.');
      if (e instanceof Anthropic.APIConnectionError) throw new HttpError(503, 'ia_injoignable', 'Fournisseur IA injoignable.');
      if (e instanceof Anthropic.APIError) throw new HttpError(502, 'ia_erreur', `Erreur du fournisseur IA (${e.status}).`);
      // Hors erreurs d'API : client mal configuré (le plus souvent aucune clé ni profil).
      throw new HttpError(500, 'config_ia', 'Service IA non configuré (clé API absente ?).');
    }
    if (response.stop_reason === 'refusal') throw new HttpError(422, 'ia_refus', 'Le modèle a refusé de traiter cette dictée.');
    if (response.stop_reason === 'max_tokens') throw new HttpError(502, 'ia_tronquee', 'Réponse du modèle incomplète : dictée trop longue.');
    const textBlock = response.content.find((b) => b.type === 'text');
    let json;
    try { json = JSON.parse(textBlock?.text ?? ''); } catch { throw new HttpError(502, 'ia_illisible', 'Réponse du modèle illisible.'); }
    const u = response.usage ?? {};
    return {
      json, model: response.model,
      usage: { input: u.input_tokens ?? 0, output: u.output_tokens ?? 0, cacheRead: u.cache_read_input_tokens ?? 0, cacheWrite: u.cache_creation_input_tokens ?? 0 },
    };
  };
}

/** Appel à Gemini (Google AI Studio) par son API REST : même contrat que anthropicCaller. */
export function geminiCaller(config, fetchFn = fetch) {
  return async ({ text, now, tz, previous }) => {
    const key = process.env.GEMINI_API_KEY;
    if (!key) throw new HttpError(500, 'config_ia', 'Service IA non configuré (GEMINI_API_KEY absente).');
    let r;
    try {
      r = await fetchFn(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(config.model)}:generateContent`, {
        method: 'POST', signal: AbortSignal.timeout(60_000),
        headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
          contents: [{ role: 'user', parts: [{ text: userMessage({ text, now, tz, previous, calendar: calendarContext(now) }) }] }],
          generationConfig: { maxOutputTokens: 16000, responseMimeType: 'application/json', responseJsonSchema: SCHEMA },
        }),
      });
    } catch { throw new HttpError(503, 'ia_injoignable', 'Fournisseur IA injoignable.'); }
    const body = await r.json().catch(() => ({}));
    if (!r.ok) {
      if (r.status === 401 || r.status === 403 || /API_KEY/.test(JSON.stringify(body))) throw new HttpError(500, 'cle_invalide', 'Clé API refusée par le fournisseur IA.');
      if (r.status === 429 || r.status === 503) throw new HttpError(503, 'ia_surchargee', 'Quota du fournisseur IA atteint : réessaie plus tard.');
      throw new HttpError(502, 'ia_erreur', `Erreur du fournisseur IA (${r.status}).`);
    }
    const cand = body.candidates?.[0];
    if (body.promptFeedback?.blockReason || cand?.finishReason === 'SAFETY') throw new HttpError(422, 'ia_refus', 'Le modèle a refusé de traiter cette dictée.');
    if (cand?.finishReason === 'MAX_TOKENS') throw new HttpError(502, 'ia_tronquee', 'Réponse du modèle incomplète : dictée trop longue.');
    const out = (cand?.content?.parts ?? []).filter((p) => !p.thought).map((p) => p.text ?? '').join('');
    let json;
    try { json = JSON.parse(out); } catch { throw new HttpError(502, 'ia_illisible', 'Réponse du modèle illisible.'); }
    const u = body.usageMetadata ?? {};
    return {
      json, model: body.modelVersion ?? config.model,
      usage: { input: u.promptTokenCount ?? 0, output: u.candidatesTokenCount ?? 0, cacheRead: u.cachedContentTokenCount ?? 0, cacheWrite: 0 },
    };
  };
}

/** Quotas en mémoire par client (jeton ou adresse) : par minute et par jour. */
function limiter(config) {
  const minute = new Map(); const day = new Map();
  return (key) => {
    const t = Date.now(); const today = new Date().toISOString().slice(0, 10);
    const m = (minute.get(key) ?? []).filter((x) => t - x < 60_000);
    const d = day.get(key)?.date === today ? day.get(key) : { date: today, n: 0 };
    if (m.length >= config.perMinute) throw new HttpError(429, 'trop_rapide', 'Trop de demandes en une minute.');
    if (d.n >= config.dailyLimit) throw new HttpError(429, 'quota_jour', `Limite quotidienne atteinte (${config.dailyLimit} analyses).`);
    m.push(t); d.n++;
    minute.set(key, m); day.set(key, d);
    return config.dailyLimit - d.n;
  };
}

async function readJson(req, max = 64 * 1024) {
  let size = 0; const chunks = [];
  for await (const c of req) { size += c.length; if (size > max) throw new HttpError(413, 'trop_long', 'Requête trop volumineuse.'); chunks.push(c); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new HttpError(400, 'json', 'Corps JSON invalide.'); }
}

/** Vérifie la requête de l'application. La date locale du client fait foi (pas celle du serveur), dans une marge raisonnable. */
export function checkRequest(body, config, serverToday = new Date().toISOString().slice(0, 10)) {
  if (body?.consent !== true) throw new HttpError(403, 'consentement', "L'envoi de la dictée au service IA n'a pas été accepté.");
  const text = typeof body.text === 'string' ? body.text.trim() : '';
  if (!text) throw new HttpError(400, 'texte_vide', 'Transcription vide.');
  if (text.length > config.maxChars) throw new HttpError(413, 'trop_long', `Dictée trop longue (${config.maxChars} caractères au plus).`);
  const now = body.now;
  if (!now || typeof now.date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(now.date) || !Number.isInteger(now.minute) || now.minute < 0 || now.minute > 1439)
    throw new HttpError(400, 'date', 'Date locale du téléphone manquante ou invalide.');
  if (Math.abs(daysBetween(serverToday, now.date)) > 2) throw new HttpError(400, 'date', 'La date du téléphone semble fausse.');
  const tz = typeof body.tz === 'string' && /^[\w+/-]{1,64}$/.test(body.tz) ? body.tz : 'Europe/Paris';
  let previous = null;
  if (body.previous != null) {
    const v = validateUnderstanding(body.previous, now);
    if (!v.ok) throw new HttpError(400, 'contexte', 'Compréhension précédente invalide.');
    previous = v.value;
  }
  return { text, now, tz, previous };
}

export function createServer({ config = DEFAULT_CONFIG, callModel = (config.provider === 'gemini' ? geminiCaller : anthropicCaller)(config), log = console.log } = {}) {
  const take = limiter(config);
  return http.createServer(async (req, res) => {
    const started = Date.now();
    const origin = req.headers.origin;
    const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
    if (origin && config.allowedOrigins.includes(origin)) Object.assign(headers, {
      'Access-Control-Allow-Origin': origin, Vary: 'Origin',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    });
    const send = (status, body) => { res.writeHead(status, headers); res.end(JSON.stringify(body)); };
    const path = new URL(req.url, 'http://x').pathname;
    try {
      if (req.method === 'OPTIONS') { res.writeHead(204, headers); return res.end(); }
      if (req.method === 'GET' && path === '/api/health') return send(200, { ok: true, model: config.model, auth: !!config.appToken });
      if (req.method !== 'POST' || path !== '/api/understand') throw new HttpError(404, 'introuvable', 'Adresse inconnue.');
      if (origin && !config.allowedOrigins.includes(origin)) throw new HttpError(403, 'origine', 'Origine non autorisée.');
      const auth = req.headers.authorization ?? '';
      if (config.appToken && auth !== `Bearer ${config.appToken}`) throw new HttpError(401, 'jeton', "Jeton d'application manquant ou incorrect.");
      const remaining = take(config.appToken ? auth : (req.socket.remoteAddress ?? '?'));
      const input = checkRequest(await readJson(req), config);
      const out = await callModel(input);
      const v = validateUnderstanding(out.json, input.now);
      if (!v.ok) throw new HttpError(502, 'ia_illisible', v.issues[0]);
      log(`understand 200 ${Date.now() - started}ms in=${out.usage?.input} out=${out.usage?.output} cache=${out.usage?.cacheRead}`);
      return send(200, { understanding: v.value, issues: v.issues, model: out.model, usage: out.usage, remaining });
    } catch (e) {
      const err = e instanceof HttpError ? e : new HttpError(500, 'interne', 'Erreur interne du serveur.');
      if (!(e instanceof HttpError)) log(`erreur interne : ${e?.message}`);
      log(`${path} ${err.status} ${err.code} ${Date.now() - started}ms`);
      return send(err.status, { error: err.code, message: err.message });
    }
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const config = DEFAULT_CONFIG;
  if (config.provider === 'gemini' && !process.env.GEMINI_API_KEY) console.warn('Attention : GEMINI_API_KEY absente (à mettre dans server/.env).');
  if (config.provider !== 'gemini' && !process.env.ANTHROPIC_API_KEY) console.warn('Attention : ANTHROPIC_API_KEY absent (un profil `ant auth login` peut le remplacer).');
  if (!config.appToken) console.warn('Attention : BRR_APP_TOKEN vide, le serveur accepte toute application. À définir avant une mise en ligne.');
  createServer({ config }).listen(config.port, () => console.log(`Bullylist IA sur http://localhost:${config.port} (modèle ${config.model}, effort ${config.effort})`));
}
