// Assemble les pages du site : web/src/pages/*.html + web/partials/*.html → web/*.html. Aucune dépendance.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const part = (n) => fs.readFileSync(path.join(web, 'partials', `${n}.html`), 'utf8');
const partials = { head: part('head'), nav: part('nav'), foot: part('foot') };
const pages = fs.readdirSync(path.join(web, 'src/pages')).filter((f) => f.endsWith('.html'));
for (const f of pages) {
  const src = fs.readFileSync(path.join(web, 'src/pages', f), 'utf8');
  const out = src.replace(/<!-- @(\w+) -->/g, (m, n) => partials[n] ?? m);
  fs.writeFileSync(path.join(web, f), `<!-- GÉNÉRÉ depuis src/pages/${f} par tools/build-pages.mjs — modifier la source. -->\n${out}`);
}
const urls = pages.map((f) => `  <url><loc>./${f === 'index.html' ? '' : f}</loc></url>`).join('\n');
fs.writeFileSync(path.join(web, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<!-- Remplacer « ./ » par l'adresse publique lors du déploiement. -->\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`);
console.log(`${pages.length} pages assemblées`);
