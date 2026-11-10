// Rendu des quatre niveaux de rappel BULLYLIST (site, démo, simulateur).
// Les écrans Android (ui/alarm/*.kt) reprennent la même mise en scène, les mêmes textes et les mêmes variantes.

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const reduced = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

export const LOGO_SVG = `<svg viewBox="0 0 32 32" aria-hidden="true"><path d="M7 1h24v24l-6 6H1V7z" fill="currentColor"/><path d="M13.5 7h5l-.9 11.5h-3.2zM13.4 21.6h5.2v4.4h-5.2z" fill="var(--bg, #121419)"/></svg>`;
export const VARIANTS = { 3: ['mission', 'radar', 'controle'], 4: ['nationale', 'defcon', 'flash'] };
export const LEVEL_NAMES = ['Normal', 'Insistant', 'Bully Mode', 'Bully Mode: Nuclear'];

/** Numéro de mission stable pour une tâche. */
export function missionNumber(seed) {
  let h = 7; for (const c of String(seed)) h = (h * 31 + c.charCodeAt(0)) % 99991;
  return String(h).padStart(5, '0');
}

function plural(n, w) { return `${n} ${w}${n > 1 ? 's' : ''}`; }

/** Machine à écrire (instantanée si mouvement réduit). */
function typewrite(el, text, speed = 22) {
  if (reduced()) { el.textContent = text; return () => {}; }
  let i = 0; const caret = '<span class="caret"></span>';
  const id = setInterval(() => { i++; el.innerHTML = esc(text.slice(0, i)) + caret; if (i >= text.length) { clearInterval(id); setTimeout(() => (el.innerHTML = esc(text)), 1200); } }, speed);
  return () => clearInterval(id);
}

/** Compteur qui défile jusqu'à la valeur (animation de chiffres). */
function countTo(el, to, ms = 900) {
  if (reduced()) { el.textContent = to; return; }
  const t0 = performance.now();
  const step = (t) => { const k = Math.min(1, (t - t0) / ms); el.textContent = Math.round(to * (1 - Math.pow(1 - k, 3))); if (k < 1) requestAnimationFrame(step); };
  requestAnimationFrame(step);
}

/**
 * @param host élément conteneur (position relative, ex. .phone-screen)
 * @param o {level 1-4, task, message, messages[], minutesLate, snoozeCount, nextInSec, variant, seed, preview, onAction(action)}
 *   actions : 'done' | 'snooze5' | 'snooze15' | 'snooze60' | 'tomorrow' | 'open' | 'disable'
 * @returns {close()}
 */
export function showAlert(host, o) {
  host.querySelectorAll(':scope > .al').forEach((n) => n.remove());
  const level = o.level;
  const variants = VARIANTS[level];
  const variant = o.variant ?? (variants ? variants[(o.seed ?? Math.floor(Math.random() * 99)) % variants.length] : null);
  const root = document.createElement('section');
  root.className = `al al-${level}${level >= 3 ? ' al-full' : ''}`;
  root.setAttribute('role', level >= 3 ? 'alertdialog' : 'alert');
  root.setAttribute('aria-label', `Rappel niveau ${level} : ${o.task}`);
  root.dataset.variant = variant ?? '';
  const timers = [];
  const late = Math.max(0, Math.round(o.minutesLate ?? 0));
  const mission = missionNumber(o.seed ?? o.task);
  const preview = o.preview ? '<span class="preview-tag">SIMULATION</span>' : '';

  if (level === 1) {
    root.innerHTML = `<div class="card">
      <div class="ico" style="color:var(--bg)">${LOGO_SVG}</div>
      <div class="meta"><span>Bullylist</span><span>maintenant</span></div>
      <div class="t">${esc(o.task)}</div>
      <div class="m">${esc(o.message)}</div>
      <div class="acts"><button data-a="done">Fait</button><button data-a="snooze15">+15 min</button><button data-a="snooze60">+1 h</button></div>
    </div>${preview}`;
  } else if (level === 2) {
    root.innerHTML = `<div class="card chamfer">
      <div class="top"><span>NIVEAU 2 · INSISTANT</span><span>${late ? `+${late} MIN` : 'MAINTENANT'}</span></div>
      <div class="t">${esc(o.task)}</div>
      <div class="m">${esc(o.message)}</div>
      <div class="acts"><button data-a="done">C'EST FAIT</button><button class="ghost" data-a="snooze5">+5 MIN</button><button class="ghost" data-a="open">OUVRIR</button></div>
    </div>${preview}`;
  } else if (level === 3) {
    const next = Math.max(0, o.nextInSec ?? 300);
    const head = { mission: 'OPÉRATION EN COURS', radar: 'CIBLE VERROUILLÉE', controle: 'CONTRÔLE DE MISSION' }[variant];
    const visual = variant === 'mission'
      ? `<div class="ring"><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="none" stroke="rgba(255,255,255,.12)" stroke-width="6"/><circle class="arc" cx="50" cy="50" r="45" fill="none" stroke="var(--l3)" stroke-width="6" stroke-dasharray="283" stroke-dashoffset="0" stroke-linecap="butt"/></svg><div class="v"><span class="cd">--:--</span><small>RELANCE</small></div></div>`
      : variant === 'controle'
        ? `<div class="gauges"><div class="gauge chamfer-sm"><b class="g1">0</b><span>minutes de retard</span></div><div class="gauge chamfer-sm"><b><span class="g2">0</span>%</b><span>motivation détectée</span><div class="meter"><i></i></div></div><div class="gauge chamfer-sm"><b class="g3">0</b><span>reports</span></div></div>`
        : `<div class="gauges"><div class="gauge chamfer-sm"><b class="g1">0</b><span>min de retard</span></div><div class="gauge chamfer-sm"><b class="cd">--:--</b><span>avant relance</span></div><div class="gauge chamfer-sm"><b>${esc(o.snoozeCount ?? 0)}</b><span>excuses</span></div></div>`;
    root.innerHTML = `${variant === 'radar' ? '<div class="radar"></div>' : '<div class="grid"></div>'}<div class="noise"></div>
      <div class="scroll al-stack">
        <div class="bar"><span><i class="dot"></i>BULLY MODE · NIVEAU 3</span><span>#${mission}</span></div>
        <div class="kicker">${head}</div>
        <h2 class="task"><span class="shake">${esc(o.task)}</span></h2>
        ${visual}
        <p class="quote"></p>
        <div class="acts">
          <button class="go chamfer" data-a="done">C'est fait</button>
          <div class="row"><button class="alt chamfer-sm" data-a="snooze5">5 min, promis</button><button class="alt chamfer-sm" data-a="tomorrow">Je reporte à demain</button></div>
        </div>
      </div>${preview}`;
    const cd = root.querySelectorAll('.cd'); const arc = root.querySelector('.arc');
    const t0 = Date.now();
    const tick = () => {
      const left = Math.max(0, next - Math.floor((Date.now() - t0) / 1000));
      cd.forEach((e) => (e.textContent = `${String(Math.floor(left / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')}`));
      if (arc) arc.style.strokeDashoffset = String(283 * (1 - left / Math.max(1, next)));
    };
    tick(); timers.push(setInterval(tick, 1000));
    const g1 = root.querySelector('.g1'); if (g1) countTo(g1, late);
    const g2 = root.querySelector('.g2'); if (g2) countTo(g2, 3);
    const g3 = root.querySelector('.g3'); if (g3) countTo(g3, o.snoozeCount ?? 0);
    const meter = root.querySelector('.meter i'); if (meter) setTimeout(() => (meter.style.width = '3%'), 400);
  } else {
    const titles = { nationale: 'ALERTE<br>NATIONALE', defcon: 'DEFCON 1', flash: 'URGENCE<br>ABSOLUE' };
    const top = variant === 'flash'
      ? `<div class="tv"><span class="live">EN DIRECT</span><span class="flashsp">FLASH SPÉCIAL</span></div>`
      : '<div class="hazard top"></div>';
    const ticker = (o.messages?.length ? o.messages : [o.message]).map((m) => `+++ ${esc(m)} `).join('') + `+++ ${esc(o.task.toUpperCase())} : TOUJOURS PAS FAIT `;
    root.innerHTML = `<div class="flash"></div><div class="noise"></div>${top}
      <div class="scroll al-stack">
        <div class="bar"><span><i class="dot"></i>BULLY MODE: NUCLEAR</span><span>NIVEAU 4</span></div>
        <h2 class="title">${titles[variant]}</h2>
        ${variant === 'defcon' ? '<div class="defcon"><span>5</span><span>4</span><span>3</span><span>2</span><span>1</span></div>' : ''}
        <div class="mission"><span>MISSION #${mission}</span><span>${o.preview ? 'EXERCICE' : 'PRIORITÉ ABSOLUE'}</span></div>
        <h3 class="task"><span class="shake">${esc(o.task)}</span></h3>
        <div class="status">STATUT : ÉCHEC DEPUIS <b class="late">0</b> <span class="unit">MINUTES</span></div>
        <p class="quote"></p>
        <div class="acts">
          <button class="go chamfer" data-a="done">Je passe à l'action</button>
          <button class="alt chamfer-sm" data-a="snooze5">Accorder 5 minutes</button>
          <button class="hopeless" data-a="tomorrow">Je suis un cas désespéré</button>
        </div>
      </div>
      ${variant === 'flash' ? `<div class="ticker"><span>${ticker}</span></div>` : '<div class="hazard"></div>'}${preview}`;
    const lateEl = root.querySelector('.late'); const unit = root.querySelector('.unit');
    countTo(lateEl, late, 1400);
    unit.textContent = late > 1 ? 'MINUTES' : 'MINUTE';
    // Le retard continue de grimper pendant que l'écran est affiché.
    const t0 = Date.now();
    timers.push(setInterval(() => { const v = late + Math.floor((Date.now() - t0) / 60000); if (v > late) { lateEl.textContent = v; unit.textContent = 'MINUTES'; } }, 5000));
    if (variant === 'defcon') {
      const cells = [...root.querySelectorAll('.defcon span')];
      cells.forEach((c, i) => timers.push(setTimeout(() => { cells.forEach((x) => x.classList.remove('on')); c.classList.add('on'); }, reduced() ? 0 : 500 + i * 420)));
      if (reduced()) cells[4].classList.add('on');
    }
  }

  // Punchline animée ; les messages défilent sur les écrans immersifs.
  const quote = root.querySelector('.quote');
  if (quote) {
    const list = [o.message, ...(o.messages ?? []).filter((m) => m !== o.message)];
    let i = 0; let stop = typewrite(quote, `« ${list[0]} »`);
    if (list.length > 1) timers.push(setInterval(() => { stop(); i = (i + 1) % list.length; stop = typewrite(quote, `« ${list[i]} »`); }, 6500));
    timers.push({ clear: () => stop() });
  }

  const close = () => { timers.forEach((t) => (t.clear ? t.clear() : (clearInterval(t), clearTimeout(t)))); root.remove(); };
  root.addEventListener('click', (e) => {
    const a = e.target.closest('[data-a]')?.dataset.a;
    if (!a) return;
    close();
    o.onAction?.(a);
  });
  host.appendChild(root);
  if (level >= 3) root.querySelector('.go')?.focus({ preventScroll: true });
  return { close, variant, root };
}

// ---------- Sons et vibrations (respect du mode du téléphone simulé) ----------
const SOUNDS = { 1: 'brr_carillon', 2: 'brr_ping', 3: 'brr_klaxon', 4: 'brr_sirene' };
let current = null;

/**
 * Reproduit les règles Android de l'application : son uniquement en mode sonnerie et hors Ne pas déranger ;
 * vibration en mode sonnerie ou vibreur ; rien en silencieux.
 * @returns description de ce qui a réellement été joué (pour la console)
 */
export function feedback(level, phone, base = '') {
  stopFeedback();
  const out = [];
  if (phone.dnd) return ['Ne pas déranger : aucun son, aucune vibration (Android filtre la notification).'];
  if (phone.mode === 'sound') {
    const a = new Audio(`${base}assets/sounds/${SOUNDS[level]}.wav`);
    a.volume = level >= 3 ? 0.7 : 0.45;
    a.loop = level === 4;
    a.play().then(() => out.push('son')).catch(() => {});
    current = a;
    out.push(`Son « ${SOUNDS[level].replace('brr_', '')} »${level === 4 ? ' en boucle jusqu\'à action' : ''}`);
  } else out.push(phone.mode === 'vibrate' ? 'Mode vibreur : pas de son' : 'Mode silencieux : ni son ni vibration');
  if (phone.mode !== 'silent' && level >= 2) {
    const pattern = level === 2 ? [250, 120, 250] : level === 3 ? [500, 200, 500, 200, 500] : [800, 250, 800, 250, 800, 250, 800];
    if (navigator.userActivation?.hasBeenActive && navigator.vibrate?.(pattern)) { vibrating = true; out.push('Vibration réelle (appareil compatible)'); }
    else out.push('Vibration simulée (secousse visuelle)');
  }
  return out;
}
let vibrating = false;
export function stopFeedback() { if (current) { current.pause(); current = null; } if (vibrating) { navigator.vibrate?.(0); vibrating = false; } }
