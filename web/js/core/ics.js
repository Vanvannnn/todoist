// Lecture de flux iCalendar (RFC 5545) : CELCAT, iCloud (calendrier public), Google (adresse secrète iCal), fichiers .ics.
// Gère : lignes repliées, TZID, UTC, journées entières, DURATION, RRULE (DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL,
// COUNT, UNTIL, BYDAY), EXDATE, RECURRENCE-ID (cours déplacés) et STATUS:CANCELLED (cours annulés).
// Porté en Kotlin : android/.../calendar/IcsParser.kt ; cas communs : design/fixtures/ics.json.

const unfold = (text) => text.replace(/\r\n/g, '\n').replace(/\n[ \t]/g, '');
const unescape = (v) => v.replace(/\\n/gi, '\n').replace(/\\([,;\\])/g, '$1');

function parseValue(params, value) {
  const tz = params.TZID ?? null;
  const dateOnly = params.VALUE === 'DATE' || /^\d{8}$/.test(value);
  const m = value.match(/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/);
  if (!m) return null;
  return { y: +m[1], mo: +m[2], d: +m[3], h: +(m[4] ?? 0), mi: +(m[5] ?? 0), s: +(m[6] ?? 0), utc: !!m[7], tz, dateOnly };
}

export function parseICS(text) {
  const events = []; let cur = null; let calName = null; let depth = 0;
  for (const line of unfold(text).split('\n')) {
    if (!line.trim()) continue;
    const idx = line.indexOf(':');
    if (idx < 0) continue;
    const head = line.slice(0, idx); const value = line.slice(idx + 1).trim();
    const [name, ...ps] = head.split(';');
    const params = Object.fromEntries(ps.map((p) => { const [k, v = ''] = p.split('='); return [k.toUpperCase(), v.replace(/^"|"$/g, '')]; }));
    const key = name.toUpperCase();
    if (key === 'BEGIN') { if (value === 'VEVENT') cur = { exdates: [] }; else if (cur) depth++; continue; }
    if (key === 'END') { if (value === 'VEVENT' && cur) { if (cur.dtstart) events.push(cur); cur = null; } else if (cur && depth) depth--; continue; }
    if (!cur) { if (key === 'X-WR-CALNAME') calName = unescape(value); continue; }
    if (depth) continue; // VALARM, etc.
    switch (key) {
      case 'UID': cur.uid = value; break;
      case 'SUMMARY': cur.summary = unescape(value); break;
      case 'LOCATION': cur.location = unescape(value); break;
      case 'DESCRIPTION': cur.description = unescape(value); break;
      case 'STATUS': cur.status = value.toUpperCase(); break;
      case 'DTSTART': cur.dtstart = parseValue(params, value); break;
      case 'DTEND': cur.dtend = parseValue(params, value); break;
      case 'DURATION': cur.duration = parseDuration(value); break;
      case 'RRULE': cur.rrule = Object.fromEntries(value.split(';').map((p) => p.split('='))); break;
      case 'EXDATE': for (const v of value.split(',')) { const p = parseValue(params, v); if (p) cur.exdates.push(p); } break;
      case 'RECURRENCE-ID': cur.recurrenceId = parseValue(params, value); break;
    }
  }
  return { calName, events };
}

export function parseDuration(v) {
  const m = v.match(/^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/);
  if (!m) return 0;
  const ms = ((+(m[2] ?? 0) * 7 + +(m[3] ?? 0)) * 86400 + +(m[4] ?? 0) * 3600 + +(m[5] ?? 0) * 60 + +(m[6] ?? 0)) * 1000;
  return m[1] === '-' ? -ms : ms;
}

// ---------- Fuseaux ----------
const fmtCache = new Map();
function tzOffset(ms, tz) {
  let f = fmtCache.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
    fmtCache.set(tz, f);
  }
  const p = Object.fromEntries(f.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second) - ms;
}

/** Heure murale → instant (ms). tz inconnu ou absent : fuseau de l'appareil. */
export function toInstant(w) {
  if (w.utc) return Date.UTC(w.y, w.mo - 1, w.d, w.h, w.mi, w.s);
  if (w.tz && !w.dateOnly) {
    try {
      const naive = Date.UTC(w.y, w.mo - 1, w.d, w.h, w.mi, w.s);
      let guess = naive - tzOffset(naive, w.tz);
      guess = naive - tzOffset(guess, w.tz);
      return guess;
    } catch { /* TZID non IANA (ex. noms Windows) : repli sur l'heure locale */ }
  }
  return new Date(w.y, w.mo - 1, w.d, w.h, w.mi, w.s).getTime();
}

const addWallDays = (w, n) => { const t = new Date(Date.UTC(w.y, w.mo - 1, w.d + n)); return { ...w, y: t.getUTCFullYear(), mo: t.getUTCMonth() + 1, d: t.getUTCDate() }; };
const addWallMonths = (w, n) => { const t = new Date(Date.UTC(w.y, w.mo - 1 + n, 1)); return { ...w, y: t.getUTCFullYear(), mo: t.getUTCMonth() + 1 }; };
const wallDow = (w) => { const g = new Date(Date.UTC(w.y, w.mo - 1, w.d)).getUTCDay(); return ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'][g]; };
const daysInMonth = (w) => new Date(Date.UTC(w.y, w.mo, 0)).getUTCDate();
const sameDay = (a, b) => a.y === b.y && a.mo === b.mo && a.d === b.d;

/** Développe les occurrences qui chevauchent [fromMs, toMs[. */
export function expand(parsed, fromMs, toMs) {
  const out = []; const overrides = new Map();
  for (const e of parsed.events) if (e.recurrenceId && e.uid) overrides.set(`${e.uid}|${toInstant({ ...e.recurrenceId, tz: e.recurrenceId.tz ?? e.dtstart.tz })}`, e);

  const push = (e, startMs) => {
    const allDay = e.dtstart.dateOnly;
    let dur = e.dtend ? toInstant(e.dtend) - toInstant(e.dtstart) : (e.duration || (allDay ? 86400000 : 0));
    if (dur < 0) dur = 0;
    const endMs = startMs + dur;
    if (endMs > fromMs && startMs < toMs && e.status !== 'CANCELLED') {
      out.push({ uid: e.uid ?? null, title: e.summary ?? '(sans titre)', location: e.location ?? null, start: startMs, end: endMs, allDay });
    }
  };

  for (const e of parsed.events) {
    if (e.recurrenceId) continue;
    const start = e.dtstart;
    if (!e.rrule) { const k = `${e.uid}|${toInstant(start)}`; const ov = overrides.get(k); if (ov) { push(ov, toInstant(ov.dtstart)); overrides.delete(k); } else push(e, toInstant(start)); continue; }
    const r = e.rrule; const freq = r.FREQ; const interval = Math.max(1, +(r.INTERVAL ?? 1));
    const count = r.COUNT ? +r.COUNT : Infinity;
    const until = r.UNTIL ? toInstant({ ...parseValue({}, r.UNTIL), tz: r.UNTIL.endsWith('Z') ? null : start.tz }) : Infinity;
    const byday = r.BYDAY ? r.BYDAY.split(',').map((d) => d.replace(/^[+-]?\d+/, '')) : null;
    const isEx = (w) => e.exdates.some((x) => (x.dateOnly ? sameDay(x, w) : toInstant({ ...x, tz: x.tz ?? start.tz }) === toInstant(w)));
    let emitted = 0; let guard = 0;
    const emit = (w) => {
      const ms = toInstant(w);
      if (ms > until || emitted >= count) return false;
      emitted++;
      if (!isEx(w)) {
        const k = `${e.uid}|${ms}`; const ov = overrides.get(k);
        if (ov) { push(ov, toInstant(ov.dtstart)); overrides.delete(k); } else push(e, ms);
      }
      return true;
    };
    let w = { ...start };
    while (guard++ < 3000) {
      if (toInstant(w) > toMs + 86400000 * 7 || toInstant(w) > until || emitted >= count) break;
      if (freq === 'WEEKLY' && byday) {
        // Semaine de w (lundi) : toutes les journées BYDAY, dans l'ordre.
        const monday = addWallDays(w, -((['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'].indexOf(wallDow(w)))));
        for (let i = 0; i < 7; i++) {
          const day = addWallDays(monday, i);
          const cand = { ...start, y: day.y, mo: day.mo, d: day.d };
          if (byday.includes(wallDow(cand)) && toInstant(cand) >= toInstant(start)) if (!emit(cand) && (toInstant(cand) > until || emitted >= count)) break;
        }
        w = addWallDays(monday, 7 * interval);
        w = { ...start, y: w.y, mo: w.mo, d: w.d };
        continue;
      }
      if (!(freq === 'DAILY' && byday && !byday.includes(wallDow(w)))) emit(w);
      if (freq === 'DAILY') w = addWallDays(w, interval);
      else if (freq === 'WEEKLY') w = addWallDays(w, 7 * interval);
      else if (freq === 'MONTHLY') { let n = addWallMonths({ ...start, y: w.y, mo: w.mo, d: 1 }, interval); while (start.d > daysInMonth(n)) n = addWallMonths(n, interval); w = { ...n, d: start.d }; }
      else if (freq === 'YEARLY') w = { ...w, y: w.y + interval };
      else break;
    }
  }
  // Occurrences déplacées vers la fenêtre depuis une date hors période de développement.
  for (const ov of overrides.values()) push(ov, toInstant(ov.dtstart));
  return out.sort((a, b) => a.start - b.start);
}

/** Occurrences (instants) → événements locaux du planificateur, découpés par jour. */
export function toLocalEvents(occ, { profile = 'COURS', travelBefore, travelAfter } = {}) {
  const out = [];
  for (const o of occ) {
    if (o.allDay) continue; // une journée entière n'est pas un créneau occupé (anniversaires, vacances…)
    let s = new Date(o.start);
    const e = new Date(o.end);
    while (s < e) {
      const dayEnd = new Date(s.getFullYear(), s.getMonth(), s.getDate() + 1);
      const end = e < dayEnd ? e : dayEnd;
      const pad = (n) => String(n).padStart(2, '0');
      out.push({ date: `${s.getFullYear()}-${pad(s.getMonth() + 1)}-${pad(s.getDate())}`, start: s.getHours() * 60 + s.getMinutes(),
        end: end === dayEnd ? 1440 : end.getHours() * 60 + end.getMinutes(), title: o.title, location: o.location, profile, travelBefore, travelAfter });
      s = dayEnd;
    }
  }
  return out;
}
