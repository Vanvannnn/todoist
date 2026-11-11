// Affichage et édition des tâches comprises et du planning proposé.
import { fmtTime, fmtDay, addDays } from '../core/dates.js';

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const LEVEL = { NORMAL: 'Normal', INSISTANT: 'Insistant', HARCELEMENT: 'Bully Mode', BRR: 'Nuclear' };
const PRIO = { URGENT: 'Urgente', HIGH: 'Haute', NORMAL: 'Normale', LOW: 'Basse' };
const lvlN = { NORMAL: 1, INSISTANT: 2, HARCELEMENT: 3, BRR: 4 };
export const fmtMin = (m) => (m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? String(m % 60).padStart(2, '0') : ''}` : `${m} min`);

/**
 * @param edit null (lecture seule) ou callback(action, taskId, value) — actions : remove | minutes | day | time | level
 */
export function renderTasks(el, tasks, now, edit = null) {
  el.innerHTML = tasks.map((t) => {
    const tags = [];
    if (t.deadline) tags.push(`<span class="tag dl">Avant ${esc(fmtDay(t.deadline.date, now.date).toLowerCase())} ${fmtTime(t.deadline.minute)}</span>`);
    if (t.window) tags.push(`<span class="tag">${t.window.fixed ? 'À' : 'Souhaité'} ${esc(fmtDay(t.window.date, now.date).toLowerCase())}${t.window.fixed ? ' ' + fmtTime(t.window.start) : ` (${esc(t.window.label)})`}</span>`);
    if (t.recurrence?.type === 'daily') tags.push(`<span class="tag">Chaque jour · ${fmtMin(t.sessionMinutes)}</span>`);
    if (t.recurrence?.type === 'count') tags.push(`<span class="tag">${t.recurrence.n} séances</span>`);
    if (t.recurrence?.type === 'days') tags.push(`<span class="tag">${t.recurrence.dates.length} séances · ${t.recurrence.dates.map((d) => esc(fmtDay(d, now.date).split(' ')[0].toLowerCase())).join(', ')} · ${fmtMin(t.sessionMinutes)}</span>`);
    if (t.group && t.group !== t.title) tags.push(`<span class="tag">Projet : ${esc(t.group)}</span>`);
    tags.push(`<span class="tag">≈ ${fmtMin(t.minutes)}${t.explicitMinutes ? '' : ` (${t.estimate.min}–${t.estimate.max} min)`}</span>`);
    tags.push(`<span class="tag">Priorité ${PRIO[t.priority]}</span>`);
    tags.push(t.level === 'BRR' ? '<span class="tag urgent">Nuclear</span>' : `<span class="tag">${LEVEL[t.level]}</span>`);
    if (t.dependsOn?.length) tags.push(`<span class="tag">Après « ${esc(tasks.find((x) => x.id === t.dependsOn[0])?.title ?? '')} »</span>`);
    const controls = edit ? `<div class="edit" data-id="${t.id}">
      <label class="sr-only" for="m-${t.id}">Durée en minutes</label><input id="m-${t.id}" type="number" min="5" max="600" step="5" value="${t.recurrence?.type === 'daily' || t.recurrence?.type === 'days' ? t.sessionMinutes : t.minutes}" data-act="minutes" title="Durée estimée (min)">
      <select data-act="day" aria-label="Jour souhaité"><option value="">Jour : automatique</option>${Array.from({ length: 21 }, (_, i) => addDays(now.date, i)).map((d) => `<option value="${d}" ${t.window?.date === d ? 'selected' : ''}>${esc(fmtDay(d, now.date))}</option>`).join('')}</select>
      <input type="time" data-act="time" aria-label="Heure imposée" value="${t.window?.fixed ? fmtTime(t.window.start).replace('h', ':') : ''}">
      <select data-act="level" aria-label="Niveau d'alerte">${Object.entries(LEVEL).map(([k, v]) => `<option value="${k}" ${t.level === k ? 'selected' : ''}>${v}</option>`).join('')}</select>
      <button data-act="remove">Supprimer</button></div>` : '';
    return `<li class="xtask"><div class="row1"><span class="tt">${esc(t.title)}</span><span class="lvl" data-l="${lvlN[t.level]}"></span></div>
      <div class="tags">${tags.join('')}</div>${t.subtasks?.length ? `<div class="note">À cocher : ${t.subtasks.map(esc).join(' · ')}</div>` : ''}${t.notes.length ? `<div class="note">${t.notes.map(esc).join('<br>')}</div>` : ''}${controls}</li>`;
  }).join('');
  if (!edit) return;
  el.onchange = el.onclick = (e) => {
    const c = e.target.closest('[data-act]'); if (!c) return;
    const id = c.closest('[data-id]').dataset.id;
    if (e.type === 'click' && c.dataset.act !== 'remove') return;
    if (e.type === 'click' && c.dataset.act === 'remove') edit('remove', id);
    if (e.type === 'change' && c.dataset.act !== 'remove') edit(c.dataset.act, id, c.value);
  };
}

/** Applique une modification de l'utilisateur à une tâche (avant replanification). */
export function applyEdit(tasks, act, id, value, now) {
  const t = tasks.find((x) => x.id === id); if (!t) return tasks;
  if (act === 'remove') return tasks.filter((x) => x.id !== id).map((x) => ({ ...x, dependsOn: x.dependsOn.filter((d) => d !== id) }));
  if (act === 'minutes') { const m = Math.max(5, Math.min(600, Number(value) || t.minutes)); if (t.recurrence?.type === 'daily' || t.recurrence?.type === 'days') t.sessionMinutes = m; else t.minutes = m; t.explicitMinutes = m; }
  if (act === 'day') t.window = value ? { date: value, start: 480, end: 1350, fixed: false, label: fmtDay(value, now.date).toLowerCase() } : null;
  if (act === 'time') {
    if (!value) { if (t.window?.fixed) t.window = null; } else {
      const [h, m] = value.split(':').map(Number);
      t.window = { date: t.window?.date ?? now.date, start: h * 60 + m, end: h * 60 + m, fixed: true, label: value };
    }
  }
  if (act === 'level') t.level = value;
  return [...tasks];
}

export function renderPlan(el, result) {
  const parts = [];
  if (!result.feasible) parts.push(`<p class="plan-warn"><b>Planning impossible tel quel.</b> ${result.unplaced.length} séance(s) n'ont pas trouvé de place. Voici ce que je propose :</p>`);
  for (const u of result.unplaced) parts.push(`<div class="plan-miss"><b>${esc(u.title)} — ${fmtMin(u.minutes)} non placées</b>Raison : ${esc(u.reason)}.<ul>${u.alternatives.map((a) => `<li>${esc(a)}</li>`).join('')}</ul></div>`);
  if (result.warnings.length) parts.push(`<div class="plan-warn">À savoir :<ul>${result.warnings.map((w) => `<li>${esc(w)}</li>`).join('')}</ul></div>`);
  for (const d of result.days) {
    const blocks = d.blocks.filter((b) => b.kind !== 'past');
    if (!blocks.some((b) => b.kind === 'task' || b.kind === 'event')) continue;
    parts.push(`<section class="plan-day"><h4>${esc(d.label)}<small>${fmtMin(d.focus)} de travail${d.free != null ? ` · ${fmtMin(d.free)} libres` : ''}</small></h4><ul class="blocks">${blocks.map((b) =>
      `<li class="blk ${b.kind}" ${b.level ? `data-level="${b.level}"` : ''}><span class="hr">${fmtTime(b.start)} – ${fmtTime(Math.min(b.end, 1440))}</span><span>${esc(b.title)}${b.location ? ` <span class="muted">· ${esc(b.location)}</span>` : ''}</span></li>`).join('')}</ul></section>`);
  }
  el.innerHTML = parts.join('');
}
