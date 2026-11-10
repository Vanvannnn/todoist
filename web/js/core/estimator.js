// Estimation des durées : familles de mots-clés → fourchette (minutes), affinée par les durées réellement constatées.
import { round5 } from './dates.js';

/** Ordre significatif : la première famille reconnue l'emporte. */
export const FAMILIES = [
  { id: 'mail', label: 'Mail / message', keywords: ['mail', 'courriel', 'message', 'repondre a', 'sms'], range: [5, 15] },
  { id: 'appel', label: 'Appel', keywords: ['appeler', 'telephoner', 'rappeler', 'appel'], range: [5, 20] },
  { id: 'expose', label: 'Exposé / oral', keywords: ['expose', 'presentation', 'oral', 'diaporama', 'slides', 'soutenance'], range: [120, 240] },
  { id: 'dossier', label: 'Dossier / rédaction', keywords: ['dossier', 'rapport', 'memoire', 'rediger', 'redaction', 'dissertation', 'projet'], range: [90, 180] },
  { id: 'revision', label: 'Révision', keywords: ['reviser', 'revision', 'etudier', 'apprendre', 'chapitre', 'math', 'exercice', 'devoir', 'partiel', 'examen', 'td'], range: [45, 90] },
  { id: 'recherche', label: 'Recherche', keywords: ['recherche', 'chercher', 'se renseigner', 'comparer'], range: [20, 40] },
  { id: 'courses', label: 'Courses', keywords: ['course', 'supermarche', 'epicerie', 'acheter'], range: [45, 90] },
  { id: 'menage_grand', label: 'Grand ménage', keywords: ['appartement', 'maison', 'grand menage'], range: [60, 120] },
  { id: 'menage', label: 'Rangement / ménage', keywords: ['ranger', 'rangement', 'menage', 'nettoyer', 'aspirateur', 'chambre', 'bureau'], range: [30, 60] },
  { id: 'petit_menage', label: 'Petite corvée', keywords: ['vaisselle', 'linge', 'lessive', 'etendre', 'plier'], range: [15, 30] },
  { id: 'poubelle', label: 'Poubelles', keywords: ['poubelle', 'ordure', 'dechet'], range: [5, 10] },
  { id: 'sport', label: 'Sport', keywords: ['sport', 'courir', 'footing', 'muscu', 'salle', 'yoga', 'velo', 'seance'], range: [45, 90] },
  { id: 'cuisine', label: 'Cuisine', keywords: ['cuisiner', 'cuisine', 'repas', 'preparer a manger'], range: [30, 60] },
  { id: 'lecture', label: 'Lecture', keywords: ['lire', 'lecture', 'livre'], range: [30, 60] },
  { id: 'admin', label: 'Administratif', keywords: ['facture', 'payer', 'impot', 'banque', 'administratif', 'papier', 'formulaire', 'caf'], range: [15, 45] },
  { id: 'rdv', label: 'Rendez-vous', keywords: ['medecin', 'dentiste', 'rendez-vous', 'rdv', 'docteur'], range: [45, 90] },
];
const DEFAULT = { id: 'autre', label: 'Autre', range: [30, 60] };

/** Le verbe module l'ampleur : terminer une tâche demande moins que la préparer entièrement. */
const VERB_FACTORS = [
  [/^(terminer|finir|finaliser|boucler|achever)\b/, 0.6],
  [/^(avancer|continuer|poursuivre|travailler)\b/, 0.6],
  [/^(commencer|demarrer|entamer|attaquer)\b/, 0.5],
  [/^(relire|verifier|corriger)\b/, 0.35],
];

export function family(normTitle) {
  return FAMILIES.find((f) => f.keywords.some((k) => normTitle.includes(k))) ?? DEFAULT;
}

/**
 * @param normTitle titre normalisé (minuscules, sans accents)
 * @param learned {familyId: [minutes constatées…]}
 * @returns {min, max, minutes, family, source: 'defaut'|'appris'}
 */
export function estimate(normTitle, learned = {}) {
  const f = family(normTitle);
  let [min, max] = f.range;
  const factor = verbFactor(normTitle);
  min *= factor; max *= factor;
  let mid = (min + max) / 2;
  const samples = (learned[f.id] ?? []).filter((m) => m > 0).slice(-10);
  let source = 'defaut';
  if (samples.length) {
    // Mélange progressif : l'estimation par défaut compte pour deux mesures.
    const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
    const blended = (mid * 2 + mean * factor * samples.length) / (2 + samples.length);
    const scale = blended / mid;
    min *= scale; max *= scale; mid = blended;
    source = 'appris';
  }
  return { min: round5(min), max: round5(max), minutes: round5(mid), family: f.id, familyLabel: f.label, source };
}

export const verbFactor = (normTitle) => VERB_FACTORS.find(([re]) => re.test(normTitle))?.[1] ?? 1;

/** Ajoute une durée constatée, ramenée à l'échelle d'une tâche « complète » (garde les 10 dernières par famille). */
export function learn(learned, normTitle, minutes) {
  if (!(minutes > 0) || minutes > 12 * 60) return learned;
  const id = family(normTitle).id;
  return { ...learned, [id]: [...(learned[id] ?? []), Math.round(minutes / verbFactor(normTitle))].slice(-10) };
}
