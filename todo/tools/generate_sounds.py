"""Génère les sons d'alarme originaux de B.R.R. (synthèse pure, libres de droits).
Usage : python3 tools/generate_sounds.py app/src/main/res/raw
"""
import math, struct, sys, wave, os

RATE = 22050

def env(i, n, attack=0.01, release=0.08):
    t = i / RATE; dur = n / RATE
    a = min(1.0, t / attack) if attack > 0 else 1.0
    r = min(1.0, (dur - t) / release) if release > 0 else 1.0
    return max(0.0, min(a, r))

def tone(freq, dur, vol=0.6, harmonics=(1.0,), attack=0.01, release=0.08, decay=0.0):
    n = int(RATE * dur); out = []
    for i in range(n):
        t = i / RATE
        s = sum(h * math.sin(2 * math.pi * freq * (k + 1) * t) for k, h in enumerate(harmonics))
        s /= sum(harmonics)
        d = math.exp(-decay * t) if decay else 1.0
        out.append(s * vol * env(i, n, attack, release) * d)
    return out

def sweep(f0, f1, dur, vol=0.6):
    n = int(RATE * dur); out = []; phase = 0.0
    for i in range(n):
        f = f0 + (f1 - f0) * (i / n)
        phase += 2 * math.pi * f / RATE
        s = math.sin(phase) * 0.7 + 0.3 * (1 if math.sin(phase) > 0 else -1)
        out.append(s * vol * env(i, n, 0.005, 0.02))
    return out

def silence(dur):
    return [0.0] * int(RATE * dur)

def write(path, samples):
    with wave.open(path, "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32000)) for s in samples))

def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    bell = (1.0, 0.5, 0.25, 0.12)
    # 1. Carillon doux : deux notes cristallines
    s = tone(880, 0.6, 0.45, bell, decay=4) + tone(1318.5, 0.9, 0.45, bell, decay=3.5)
    write(f"{outdir}/brr_carillon.wav", s)
    # 2. Double ping
    s = tone(1567.98, 0.18, 0.5, (1, .3), decay=10) + silence(0.07) + tone(2093, 0.35, 0.5, (1, .3), decay=8)
    write(f"{outdir}/brr_ping.wav", s)
    # 3. Klaxon : accord dissonant épais
    s = []
    for _ in range(2):
        a = tone(349, 0.35, 0.5, (1, .8, .6, .4, .3), 0.005, 0.03)
        b = tone(440, 0.35, 0.5, (1, .8, .6, .4, .3), 0.005, 0.03)
        s += [(x + y) * 0.6 for x, y in zip(a, b)] + silence(0.12)
    write(f"{outdir}/brr_klaxon.wav", s)
    # 4. Sirène montante/descendante
    s = sweep(600, 1400, 0.7, 0.55) + sweep(1400, 600, 0.7, 0.55)
    write(f"{outdir}/brr_sirene.wav", s)
    # 5. Clairon : fanfare de rassemblement (sol-do-mi-sol)
    notes = [(392, .18), (523.25, .18), (659.25, .18), (783.99, .45), (659.25, .15), (783.99, .6)]
    s = []
    for f, d in notes:
        s += tone(f, d, 0.5, (1, .7, .5, .35, .2), 0.02, 0.04) + silence(0.03)
    write(f"{outdir}/brr_clairon.wav", s)
    # 6. Alerte rouge : bips rapides
    s = []
    for _ in range(3):
        for _ in range(4):
            s += tone(2000, 0.07, 0.55, (1, .2), 0.002, 0.01) + silence(0.05)
        s += silence(0.25)
    write(f"{outdir}/brr_alerte.wav", s)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw")
