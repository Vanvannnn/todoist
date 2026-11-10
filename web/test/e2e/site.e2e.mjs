// Balayage du site : chaque page se charge sans erreur JavaScript ni texte « undefined/NaN ».
import { chromium } from 'playwright-core';
const b = await chromium.launch({ executablePath: process.env.CHROME_PATH ?? process.env.HOME + '/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome', args: ['--no-sandbox'] });
const p = await b.newPage();
const bad = [];
for (const path of ['index.html', 'fonctionnalites.html', 'demo.html', 'punchlines.html', 'assistant.html', 'telecharger.html', 'confidentialite.html', 'simulateur/']) {
  const errs = []; p.removeAllListeners('pageerror'); p.on('pageerror', (e) => errs.push(e.message));
  await p.goto((process.argv[2] ?? 'http://localhost:8080/') + path, { waitUntil: 'networkidle' }); await p.waitForTimeout(800);
  const txt = await p.evaluate(() => document.body.innerText);
  if (/undefined|NaN|\[object/.test(txt)) bad.push(`${path}: texte suspect`);
  if (errs.length) bad.push(`${path}: ${errs.join(' | ')}`);
  for (const l of [1, 2, 3, 4]) if (path === 'demo.html') { await p.click(`[data-level="${l}"]`); await p.waitForTimeout(700); const t = await p.evaluate(() => document.querySelector('.al')?.innerText ?? ''); if (/undefined|NaN/.test(t)) bad.push(`demo niveau ${l}`); for (const v of await p.locator('#d-var button').all()) { await v.click(); await p.waitForTimeout(500); const t2 = await p.evaluate(() => document.querySelector('.al')?.innerText ?? ''); if (/undefined|NaN/.test(t2)) bad.push(`demo variante niveau ${l}`); } }
}
console.log(bad.length ? bad.join('\n') : 'toutes les pages : aucun texte suspect, aucune erreur JS (y compris les 4 niveaux × variantes de la démo)');
await b.close();
