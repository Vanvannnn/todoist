// Génère les artefacts de la charte BULLYLIST pour le web et Android à partir de tokens.json et punchlines.json.
// Usage : node design/build.mjs   (aucune dépendance)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => JSON.parse(fs.readFileSync(path.join(root, p), 'utf8'));
const write = (p, s) => { fs.mkdirSync(path.dirname(path.join(root, p)), { recursive: true }); fs.writeFileSync(path.join(root, p), s); console.log('écrit', p); };

const t = read('design/tokens.json');
const pl = read('design/punchlines.json');
const HEADER = 'GÉNÉRÉ par design/build.mjs depuis design/*.json — ne pas modifier à la main.';
const kebab = (s) => s.replace(/[A-Z0-9]+/g, (m) => '-' + m.toLowerCase());

// ---------- Web : tokens.css ----------
const cssVars = (colors) => Object.entries(colors).map(([k, v]) => `  --${kebab(k)}: ${v};`).join('\n');
let css = `/* ${HEADER} */\n:root {\n${cssVars(t.color.dark)}\n`;
for (const [k, l] of Object.entries(t.color.level)) css += `  --${k}: ${l.base};\n  --${k}-deep: ${l.deep};\n  --${k}-ink: ${l.ink};\n`;
for (const [k, v] of Object.entries(t.color.priority)) css += `  --${k}: ${v};\n`;
t.color.project.forEach((c, i) => { css += `  --project-${i}: ${c};\n`; });
css += `  --font-display: '${t.font.display}', system-ui, sans-serif;\n  --font-body: '${t.font.body}', system-ui, sans-serif;\n  --font-mono: '${t.font.mono}', ui-monospace, monospace;\n`;
t.space.forEach((v, i) => { css += `  --s${i}: ${v}px;\n`; });
for (const [k, v] of Object.entries(t.radius)) css += `  --r-${kebab(k)}: ${v}px;\n`;
const bez = (a) => `cubic-bezier(${a.join(',')})`;
css += `  --t-fast: ${t.motion.fast}ms;\n  --t-base: ${t.motion.base}ms;\n  --t-slow: ${t.motion.slow}ms;\n  --ease: ${bez(t.motion.ease)};\n  --ease-emph: ${bez(t.motion.emphasis)};\n`;
for (const [k, v] of Object.entries(t.motion.pulse)) css += `  --pulse-${k}: ${v}ms;\n`;
for (const [k, v] of Object.entries(t.glow)) css += `  --glow-${k}: ${v};\n`;
css += `  color-scheme: dark;\n}\n:root[data-theme="light"] {\n${cssVars(t.color.light)}\n  color-scheme: light;\n}\n`;
for (const [k, s] of Object.entries(t.type)) {
  css += `.t-${k} { font-family: var(--font-${k === 'body' || k === 'small' || k === 'title' || k === 'label' ? 'body' : 'display'}); font-size: ${s.size}px; line-height: ${s.line}px; font-weight: ${s.weight}; font-stretch: ${s.width}%; letter-spacing: ${s.tracking}em;${k === 'label' ? ' text-transform: uppercase;' : ''} }\n`;
}
write('web/assets/css/tokens.css', css);

// ---------- Android : Tokens.kt ----------
const kc = (hex) => `Color(0xFF${hex.slice(1).toUpperCase()})`;
const kColors = (o) => Object.entries(o).map(([k, v]) => `    val ${k} = ${kc(v)}`).join('\n');
let kt = `// ${HEADER}\npackage com.brr.app.ui.theme\n\nimport androidx.compose.ui.graphics.Color\n\n`;
kt += `object BullyDark {\n${kColors(t.color.dark)}\n}\n\nobject BullyLight {\n${kColors(t.color.light)}\n}\n\n`;
kt += `/** Identité des quatre niveaux d'alerte (index 0 = niveau 1). */\nobject BullyLevels {\n`;
kt += `    val base = listOf(${Object.values(t.color.level).map((l) => kc(l.base)).join(', ')})\n`;
kt += `    val deep = listOf(${Object.values(t.color.level).map((l) => kc(l.deep)).join(', ')})\n`;
kt += `    val ink = listOf(${Object.values(t.color.level).map((l) => kc(l.ink)).join(', ')})\n`;
kt += `    val names = listOf(${Object.values(t.color.level).map((l) => JSON.stringify(l.name)).join(', ')})\n`;
kt += `    val pulseMs = listOf(0, ${t.motion.pulse.l2}, ${t.motion.pulse.l3}, ${t.motion.pulse.l4})\n}\n\n`;
kt += `object BullyPriority {\n${kColors(t.color.priority)}\n}\n\n`;
kt += `object BullyTokens {\n    val projectColors = listOf(${t.color.project.map((c) => `0xFF${c.slice(1).toUpperCase()}`).join(', ')})\n`;
for (const [k, v] of Object.entries(t.radius)) if (k !== 'pill') kt += `    const val radius${k[0].toUpperCase() + k.slice(1)} = ${v}\n`;
kt += `    const val motionFast = ${t.motion.fast}\n    const val motionBase = ${t.motion.base}\n    const val motionSlow = ${t.motion.slow}\n`;
kt += `    const val BRAND = ${JSON.stringify(t.brand.name)}\n    const val TAGLINE = ${JSON.stringify(t.brand.tagline)}\n}\n`;
write('android/app/src/main/java/com/brr/app/ui/theme/Tokens.kt', kt);

// ---------- Android : catalogue de punchlines ----------
const ks = (s) => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$') + '"';
let pk = `// ${HEADER}\npackage com.brr.app.punchline\n\n`;
pk += `enum class PunchlineCategory(val label: String, val description: String) {\n`;
pk += pl.categories.map((c) => `    ${c.id}(${ks(c.label)}, ${ks(c.description)}),`).join('\n') + '\n}\n\n';
pk += `/** Bibliothèque intégrée (${Object.values(pl.lines).reduce((a, l) => a + l.length, 0)} punchlines). */\nobject PunchlineCatalog {\n`;
pk += `    private fun p(c: PunchlineCategory, intensity: Int, text: String) =\n        Punchline(id = "b" + Integer.toHexString(text.hashCode()), text = text, category = c, intensity = intensity)\n\n`;
// Une fonction par catégorie : évite la limite de 64 Ko de bytecode par méthode.
for (const c of pl.categories) {
  pk += `    private fun ${c.id.toLowerCase()}() = PunchlineCategory.${c.id}.let { c ->\n        listOf(\n`;
  pk += pl.lines[c.id].map(([i, s]) => `            p(c, ${i}, ${ks(s)}),`).join('\n') + '\n        )\n    }\n\n';
}
pk += `    val all: List<Punchline> by lazy {\n        ${pl.categories.map((c) => c.id.toLowerCase() + '()').join(' + ')}\n    }\n}\n\n`;
pk += `/** Données des messages contextuels ({task}, {min}, {snooze}). */\nobject ContextualData {\n`;
pk += `    class Rule(val keywords: List<String>, val lines: List<String>)\n\n`;
pk += `    val rules = listOf(\n` + pl.contextual.map((r) => `        Rule(listOf(${r.keywords.map(ks).join(', ')}), listOf(\n${r.lines.map((l) => '            ' + ks(l) + ',').join('\n')}\n        )),`).join('\n') + '\n    )\n';
for (const k of ['generic', 'late', 'snooze']) pk += `    val ${k} = listOf(\n${pl[k].map((l) => '        ' + ks(l) + ',').join('\n')}\n    )\n`;
pk += '}\n';
write('android/app/src/main/java/com/brr/app/punchline/PunchlineCatalog.kt', pk);

// ---------- Web : données et sons ----------
write('web/assets/data/punchlines.json', JSON.stringify(pl));
write('web/assets/data/tokens.json', JSON.stringify(t));
fs.copyFileSync(path.join(root, 'design/fixtures/student-week.json'), path.join(root, 'web/assets/data/student-week.json'));
const raw = path.join(root, 'android/app/src/main/res/raw');
for (const f of fs.readdirSync(raw)) {
  fs.mkdirSync(path.join(root, 'web/assets/sounds'), { recursive: true });
  fs.copyFileSync(path.join(raw, f), path.join(root, 'web/assets/sounds', f));
}
console.log('sons copiés vers web/assets/sounds');
