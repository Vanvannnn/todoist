# Charte graphique BULLYLIST

Source unique : [`design/tokens.json`](../design/tokens.json). `node design/build.mjs` génère :
- `web/assets/css/tokens.css` (site et simulateur) ;
- `android/.../ui/theme/Tokens.kt` (application).

Ne modifie jamais les fichiers générés.

## Principe : la couleur, c'est l'alarme

L'interface au calme est **monochrome** : graphite et blanc os, sans couleur décorative. On a l'impression d'un instrument de précision. La couleur n'apparaît que pour signaler une urgence, comme un voyant de salle de contrôle. Une tâche « se colore » à mesure qu'elle s'aggrave. Ce contraste est voulu : application premium au calme, crise nationale quand ça sonne.

| Rôle | Sombre | Clair |
|---|---|---|
| Fond | `#121419` | `#E9ECEF` |
| Surfaces | `#181B21` / `#1F232B` / `#282D37` | `#FFFFFF` / `#F5F6F8` / `#E1E5E9` |
| Texte | `#F2EFE8` (blanc os) | `#15171C` |
| Texte secondaire | `#A9AAB0`, `#71747D` | `#4C515B`, `#7A808A` |
| Accent (boutons principaux) | blanc os, texte graphite | graphite, texte blanc |
| Réussite | `#3FD18B` | `#14935A` |

### Niveaux d'alerte

| Niveau | Couleur | Fond profond | Mise en scène |
|---|---|---|---|
| 1 Normal | `#7C9CFF` bleu signal | `#0D1430` | notification élégante et discrète |
| 2 Insistant | `#FFB000` ambre | `#2A1C00` | carte ambre, secousse, halo, punchline, accès direct |
| 3 Bully Mode | `#FF5A1F` orange danger | `#2B0C02` | plein écran : Mission (anneau de compte à rebours), Radar, Contrôle (jauges) |
| 4 Bully Mode: Nuclear | `#FF2340` rouge nucléaire | `#30030B` | plein écran : Alerte nationale, DEFCON, Flash spécial (bandeau défilant), bandes de danger jaunes `#FFD400` |

Couleurs de projet : huit teintes désaturées, pour que seules les alertes soient vives.

## Typographie

Une seule famille, **Archivo** (SIL OFL), dont l'axe de largeur porte les deux personnalités :
- **interface calme** : largeur 110-125 %, ExtraBold/Black, approche négative. Large, posée, haut de gamme ;
- **alertes** : largeur 62-70 %, Black, MAJUSCULES. Condensée, urgente, façon bandeau d'alerte ;
- **texte courant** : Archivo à largeur 100 %.

**JetBrains Mono** (OFL) est réservée aux compteurs, aux numéros de mission et aux statuts (`STATUT : ÉCHEC DEPUIS 47 MINUTES`).

| Style | Taille / interligne | Graisse | Largeur |
|---|---|---|---|
| hero | 64 / 60 | 900 | 125 % |
| display | 40 / 42 | 800 | 118 % |
| h1 | 28 / 32 | 800 | 112 % |
| h2 | 21 / 26 | 700 | 108 % |
| title | 16 / 22 | 600 | 100 % |
| body | 15 / 22 | 400 | 100 % |
| alert | 58 / 54 | 900 | 68 % |

## Formes

- **Calme** : arrondis 4 / 8 / 12 / 18 / 26 px.
- **Alerte** : **coins chanfreinés** (12 px, 7 px pour les petits éléments), clin d'œil à la signalétique industrielle et militaire. Utilisés pour les boutons d'alerte, les pastilles de niveau, le logo et le bandeau in-app. Jamais dans l'interface calme.
- **Logo** : carré chanfreiné et point d'exclamation évidé.

## Espacement et mouvement

- Espacements (grille de 4) : 0, 4, 8, 12, 16, 20, 24, 32, 40, 56, 72, 96.
- Durées : 140 / 220 / 420 ms ; courbes `cubic-bezier(.2,.8,.2,1)` (standard) et `(.7,0,.2,1)` (emphase).
- Pulsations : niveau 2 à 1,6 s, niveau 3 à 1,2 s, niveau 4 à 1 s. **Aucun clignotement au-delà de 1 Hz** (sécurité photosensible) ; le flash du niveau 4 se limite à deux éclats au début.
- Apparition des alertes : éléments décalés de 80 ms, compteurs qui défilent jusqu'à leur valeur, punchline tapée lettre à lettre puis remplacée toutes les 6,5 s, secousse du titre toutes les 3 s.
- `prefers-reduced-motion` (web) et « Supprimer les animations » (Android) coupent les secousses, le défilement, le flash et l'escalade automatique du site.

## Ton

- Interface : phrases courtes, tutoiement, verbes d'action (« Tout accepter », « Je passe à l'action »).
- Alertes : majuscules, vocabulaire de crise, humour absurde. Jamais menaçant, discriminatoire ou réellement humiliant.
- Le nom s'écrit **BULLYLIST** dans le logo et les titres, « Bullylist » dans le texte courant.
