// Dictée dans le navigateur (Web Speech API) avec visualisation du niveau sonore.
// Aucun enregistrement n'est conservé : le flux micro n'alimente que l'analyseur de niveau, puis est coupé.
// Attention : dans Chrome, la reconnaissance est effectuée par un service en ligne de Google → consentement demandé.

export const speechSupported = () => !!(window.SpeechRecognition || window.webkitSpeechRecognition);

export function createDictation({ onText, onState, onLevel }) {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  let rec = null; let wanted = false; let finalText = ''; let stream = null; let ctx = null; let raf = 0;

  async function meter() {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      ctx = new AudioContext();
      const an = ctx.createAnalyser(); an.fftSize = 512;
      ctx.createMediaStreamSource(stream).connect(an);
      const buf = new Uint8Array(an.fftSize);
      const loop = () => { an.getByteTimeDomainData(buf); let s = 0; for (const v of buf) s += (v - 128) ** 2; onLevel?.(Math.min(1, Math.sqrt(s / buf.length) / 40)); raf = requestAnimationFrame(loop); };
      loop();
    } catch { onLevel?.(0); }
  }
  function stopMeter() { cancelAnimationFrame(raf); stream?.getTracks().forEach((t) => t.stop()); ctx?.close(); stream = null; ctx = null; onLevel?.(0); }

  function start(initial = '') {
    if (!SR) { onState?.('unsupported'); return; }
    finalText = initial ? initial.trim() + ' ' : '';
    wanted = true;
    rec = new SR();
    rec.lang = 'fr-FR'; rec.continuous = true; rec.interimResults = true;
    rec.onresult = (e) => {
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) finalText += e.results[i][0].transcript.trim() + ' ';
        else interim += e.results[i][0].transcript;
      }
      onText?.(finalText, interim);
    };
    rec.onerror = (e) => { if (e.error === 'not-allowed' || e.error === 'service-not-allowed') { wanted = false; onState?.('denied'); } };
    // La reconnaissance s'arrête après un silence : on relance tant que l'utilisateur n'a pas mis en pause.
    rec.onend = () => { if (wanted) { try { rec.start(); } catch { /* déjà relancée */ } } else { stopMeter(); onState?.('paused'); } };
    rec.start(); meter(); onState?.('listening');
  }
  function pause() { wanted = false; rec?.stop(); }
  return { start, pause, get text() { return finalText; } };
}
