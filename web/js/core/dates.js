// Arithmétique de calendrier sur des dates locales « AAAA-MM-JJ » et des minutes depuis minuit.
// Aucune dépendance au fuseau : les conversions vers/depuis Date se font aux bords (ics.js, UI).

export const DAY_NAMES = ['lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi', 'dimanche'];
export const DAY_SHORT = ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];
export const MONTHS = ['janvier', 'fevrier', 'mars', 'avril', 'mai', 'juin', 'juillet', 'aout', 'septembre', 'octobre', 'novembre', 'decembre'];

const pad = (n) => String(n).padStart(2, '0');
const parts = (d) => d.split('-').map(Number);

export function addDays(date, n) {
  const [y, m, d] = parts(date);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`;
}

/** 1 = lundi … 7 = dimanche (ISO). */
export function isoDay(date) {
  const [y, m, d] = parts(date);
  const w = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return w === 0 ? 7 : w;
}

export function daysBetween(a, b) {
  const [y1, m1, d1] = parts(a);
  const [y2, m2, d2] = parts(b);
  return Math.round((Date.UTC(y2, m2 - 1, d2) - Date.UTC(y1, m1 - 1, d1)) / 86400000);
}

export const fmtTime = (min) => `${pad(Math.floor(min / 60))}h${pad(min % 60)}`;
export const round5 = (n) => Math.max(5, Math.round(n / 5) * 5);

/** Comparaison de deux instants {date, minute}. */
export const cmp = (a, b) => (a.date === b.date ? a.minute - b.minute : a.date < b.date ? -1 : 1);

export function fmtDay(date, today) {
  const diff = daysBetween(today, date);
  if (diff === 0) return "Aujourd'hui";
  if (diff === 1) return 'Demain';
  const [, m, d] = parts(date);
  const name = DAY_NAMES[isoDay(date) - 1];
  return `${name[0].toUpperCase()}${name.slice(1)} ${d} ${MONTHS[m - 1].replace('fevrier', 'février').replace('aout', 'août').replace('decembre', 'décembre')}`;
}

export function localDate(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
export const localMinute = (d) => d.getHours() * 60 + d.getMinutes();
export function toDateObj(date, minute) {
  const [y, m, d] = parts(date);
  return new Date(y, m - 1, d, Math.floor(minute / 60), minute % 60);
}
