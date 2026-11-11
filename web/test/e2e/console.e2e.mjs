// Test de bout en bout de la console vocale dans Chromium (playwright-core).
// Le chemin « IA » passe par le vrai serveur Bullylist (server/server.mjs) dont le modèle est remplacé par une réponse
// fixe : on vérifie la plomberie (requête, contexte de conversation, validation, affichage, retouches, repli local),
// PAS la compréhension du modèle (voir test/assistant-eval.mjs --server avec une vraie clé).
// Usage : node test/e2e/console.e2e.mjs [url]
import { chromium } from 'playwright-core';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import { createServer, DEFAULT_CONFIG } from '../../../server/server.mjs';

const url = process.argv[2] ?? 'http://localhost:8080/console-ia/';
const exe = process.env.CHROME_PATH ?? `${os.homedir()}/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`;
if (!fs.existsSync(exe)) { console.log(`Chromium introuvable (${exe}) : test ignoré.`); process.exit(0); }

// Réponse fixe du « modèle » : deux tours de conversation.
const calls = [];
const T = (o) => ({ description: '', part_of_day: null, start_time: null, deadline: null, duration_minutes: null, duration_source: 'unknown', priority: 'normal',
  session_days: [], session_minutes: null, subtasks: [], depends_on: [], project: null, source_quote: '', confidence: 'high', ...o });
const fake = async (input) => {
  calls.push(input);
  const d = (k) => { const t = new Date(input.now.date + 'T12:00:00Z'); t.setUTCDate(t.getUTCDate() + k); return t.toISOString().slice(0, 10); };
  const tasks = [T({ id: 't1', title: 'Faire les courses', planned_day: { date: d(1), said: 'demain' } })];
  if (input.previous) tasks.push(T({ id: 't2', title: 'Passer à la pharmacie', planned_day: { date: d(1), said: 'demain' } }));
  return { json: { tasks, constraints: [{ date: d(2), start_time: '18:00', end_time: '24:00', reason: 'Pas après 18 h', said: 'rien après 18 heures' }], questions: [], summary: input.previous ? "J'ai ajouté la pharmacie." : "J'ai compris : les courses demain." }, model: 'modèle-de-test', usage: { input: 10, output: 5 } };
};
const srv = createServer({ config: { ...DEFAULT_CONFIG, allowedOrigins: [new URL(url).origin] }, callModel: fake, log: () => {} });
await new Promise((r) => srv.listen(0, '127.0.0.1', r));
const api = `http://127.0.0.1:${srv.address().port}`;

const browser = await chromium.launch({ executablePath: exe, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('dialog', (d) => d.accept());
let n = 0; const step = async (name, fn) => { await fn(); n++; console.log(`ok ${n} - ${name}`); };

try {
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: 'networkidle' });

  await step('moteur local : dictée multi-jours → tâches datées et planning', async () => {
    await page.check('[name=engine][value=local]');
    await page.fill('#ref-date', '2026-11-09'); await page.fill('#ref-time', '10:00');
    await page.fill('#text', "Mardi, je fais mes courses. Mercredi, je révise les maths. Enfin non, plutôt jeudi. Dimanche, je range l'appartement.");
    await page.click('#go');
    await page.waitForSelector('#tasks .xtask');
    assert.equal(await page.locator('#tasks .xtask').count(), 3);
    await page.click('[data-tab=plan]');
    assert.ok(await page.locator('#plan .blk.task').count() >= 3);
    const days = await page.locator('#plan .plan-day h4').allTextContents();
    assert.ok(days.some((d) => d.includes('Jeudi')) && !days.some((d) => d.includes('Mercredi')), `jours : ${days}`);
  });

  await step('sans accord d’envoi, rien ne part au serveur', async () => {
    await page.click('#reset');
    await page.check('[name=engine][value=ia]');
    await page.fill('#url', api); await page.dispatchEvent('#url', 'change');
    await page.fill('#text', 'Demain les courses.');
    await page.click('#go');
    await page.waitForSelector('.status.err');
    assert.equal(calls.length, 0);
  });

  await step('moteur IA : compréhension affichée, conversation conservée au 2e tour', async () => {
    await page.check('#consent');
    await page.click('[data-tab=interp]');
    await page.click('#go');
    await page.waitForSelector('#summary:has-text("les courses demain")');
    await page.fill('#text', 'Ah, et la pharmacie aussi.');
    await page.click('#go');
    await page.waitForSelector('#summary:has-text("ajouté la pharmacie")');
    assert.equal(calls.length, 2);
    assert.equal(calls[1].previous.tasks[0].title, 'Faire les courses', 'le 2e tour reçoit la compréhension précédente');
    assert.equal(await page.locator('#tasks .xtask').count(), 2);
    assert.equal(await page.locator('#constraints li').count(), 1);
  });

  await step('retouche : supprimer une tâche puis continuer la conversation', async () => {
    await page.locator('#tasks .xtask', { hasText: 'pharmacie' }).locator('[data-act=remove]').click();
    assert.equal(await page.locator('#tasks .xtask').count(), 1);
    await page.fill('#text', 'Rien de plus.');
    await page.click('#go');
    await page.waitForFunction(() => document.querySelector('#status').textContent.includes('Compris'));
    assert.equal(calls[2].previous.tasks.length, 1, 'la suppression fait partie du contexte envoyé');
  });

  await step('serveur injoignable → repli local annoncé', async () => {
    await page.click('#reset');
    await page.fill('#url', 'http://127.0.0.1:9'); await page.dispatchEvent('#url', 'change');
    await page.fill('#text', 'Jeudi, je dois appeler le garage.');
    await page.click('#go');
    await page.waitForSelector('.status.err');
    assert.match(await page.textContent('#status'), /Analyse locale utilisée/);
    assert.equal(await page.locator('#tasks .xtask').count(), 1);
  });

  await step('validation du planning', async () => {
    await page.click('#accept');
    assert.match(await page.textContent('#accepted'), /validée/);
  });

  assert.deepEqual(errors, [], 'aucune erreur JavaScript');
  console.log(`${n} étapes réussies, aucune erreur JavaScript.`);
} finally {
  await browser.close();
  srv.close();
}
