// Test de bout en bout du simulateur dans un vrai Chromium (playwright-core).
// Usage : node test/e2e/simulator.e2e.mjs [url] — nécessite le serveur local et un Chromium (CHROME_PATH ou cache Playwright).
import { chromium } from 'playwright-core';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';

const url = process.argv[2] ?? 'http://localhost:8080/simulateur/';
const exe = process.env.CHROME_PATH ?? `${os.homedir()}/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`;
if (!fs.existsSync(exe)) { console.log(`Chromium introuvable (${exe}) : test ignoré.`); process.exit(0); }
const shots = process.env.SHOTS;
const browser = await chromium.launch({ executablePath: exe, args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('dialog', (d) => d.accept());
let n = 0; const step = async (name, fn) => { await fn(); n++; console.log(`ok ${n} - ${name}`); if (shots) await page.screenshot({ path: `${shots}/sim-${n}.png` }); };

await page.goto(url, { waitUntil: 'networkidle' });
await page.evaluate(() => localStorage.clear());
await page.reload({ waitUntil: 'networkidle' });

await step('chargement de la semaine étudiante', async () => {
  await page.click('#c-demo');
  assert.equal(await page.evaluate(() => window.__sim.state.events.length), 10);
  await page.waitForSelector('text=L2 Gestion');
});

await step('dictée (mode texte) → tâches et planning', async () => {
  await page.click('#fab-voice');
  await page.click('.voice [data-a="sample"]');
  await page.waitForSelector('.voice .xtask');
  assert.equal(await page.locator('.voice .xtask').count(), 5);
  assert.ok(await page.locator('.voice .blk.task').count() >= 5);
  assert.ok(await page.locator('.voice .blk.event').count() >= 1);
});

await step('modifier une estimation puis replanifier', async () => {
  const input = page.locator('.voice .xtask input[type=number]').nth(3);
  await input.fill('60'); await input.dispatchEvent('change');
  await page.waitForTimeout(200);
  assert.ok(await page.locator('.voice .xtask').count() === 5);
});

await step('tout accepter → créneaux enregistrés', async () => {
  await page.click('.voice [data-v="accept"]');
  const tasks = await page.evaluate(() => window.__sim.state.tasks);
  assert.ok(tasks.length >= 5);
  assert.ok(tasks.filter((t) => t.plannedStart).length >= 5);
  await page.waitForSelector('#wk .blk.task');
});

await step('tâche en retard → alerte Nuclear (écran verrouillé)', async () => {
  await page.click('#c-late');
  await page.waitForSelector('.al-4', { timeout: 4000 });
  assert.match(await page.locator('.al-4 .status').innerText(), /ÉCHEC DEPUIS/);
});

await step('« Je passe à l\'action » termine la tâche', async () => {
  await page.click('.al-4 [data-a="done"]');
  const t = await page.evaluate(() => window.__sim.state.tasks.find((x) => x.title === 'Sortir les poubelles'));
  assert.equal(t.done, true);
  assert.equal(await page.locator('.al').count(), 0);
});

await step('pause de calendrier : rappel retenu et reporté', async () => {
  // Isolé des cours de démonstration, dont l'horaire dépend du moment où le test tourne.
  await page.evaluate(() => { const S = window.__sim.state; S.events = S.events.filter((e) => e.source !== 'demo'); });
  await page.click('#c-pause');
  await page.waitForTimeout(1500);
  assert.equal(await page.locator('.al').count(), 0, 'aucune alerte pendant le cours');
  const log = await page.locator('#log').innerText();
  assert.match(log, /mode pause/);
  const nt = await page.evaluate(() => { const s = window.__sim; const t = s.state.tasks.find((x) => x.title === 'Réviser le chapitre 4'); return s.nextTrigger(t); });
  assert.ok(nt.pausedBy && nt.at > Date.now() + 40 * 60000);
});

await step('fin du cours + trajet : le rappel sonne', async () => {
  await page.evaluate(() => {
    const s = window.__sim; const t = s.state.tasks.find((x) => x.title === 'Réviser le chapitre 4');
    s.state.clockOffset += s.nextTrigger(t).at - s.now() + 60000; s.tick();
  });
  await page.waitForSelector('.al', { timeout: 4000 });
  assert.match(await page.locator('#log').innerText(), /RELANCE|ÉCHÉANCE/);
});

await step('Ne pas déranger : pas de plein écran', async () => {
  await page.evaluate(() => document.querySelectorAll('.al').forEach((a) => a.remove()));
  await page.check('#c-dnd');
  await page.click('[data-test="4"]');
  await page.waitForTimeout(400);
  assert.equal(await page.locator('.al-4').count(), 0);
  assert.match(await page.locator('#shade').innerText(), /Ne pas déranger/);
  await page.uncheck('#c-dnd');
});

await step('les quatre boutons de test affichent chaque niveau', async () => {
  for (const l of [1, 2, 3, 4]) {
    await page.click(`[data-test="${l}"]`);
    await page.waitForSelector(`.al-${l}`, { timeout: 2000 });
  }
  await page.click('.al-4 [data-a="snooze5"]'); // un plein écran masque l'application, comme sur un téléphone
  assert.equal(await page.locator('.al').count(), 0);
});

await step('import ICS (fichier)', async () => {
  await page.click('[data-tab="cal"]');
  const ics = JSON.parse(fs.readFileSync(new URL('../../../design/fixtures/ics.json', import.meta.url))).cases[0].ics;
  await page.setInputFiles('#ics-file', { name: 'celcat.ics', mimeType: 'text/calendar', buffer: Buffer.from(ics) });
  await page.waitForSelector('text=L2 Gestion - Groupe 3');
});

await step('état conservé après rechargement', async () => {
  await page.reload({ waitUntil: 'networkidle' });
  assert.ok(await page.evaluate(() => window.__sim.state.tasks.length) >= 6);
});

assert.deepEqual(errors, [], 'aucune erreur JavaScript');
console.log(`${n} étapes réussies, aucune erreur JavaScript.`);
await browser.close();
