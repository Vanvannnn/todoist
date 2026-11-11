# Assistant vocal V2.1 : compréhension par IA

## En bref

Bullylist comprend une dictée de deux façons :

| Moteur | Où | Quand | Qualité |
|---|---|---|---|
| **Local** | Sur le téléphone (`VoiceParser.kt`, `web/js/core/nlu.js`) | Par défaut, et en secours si l'IA est indisponible | 32/34 scénarios de la banque de tests |
| **IA** | Serveur Bullylist (`server/`) qui appelle Claude | Si l'utilisateur l'active **et** accepte l'envoi | Non mesuré : nécessite une clé API (voir « Tester ») |

L'IA est **désactivée par défaut**. Sans elle, l'application fonctionne entièrement hors ligne.

## Architecture

```
Téléphone                         Serveur Bullylist (Node)                  Anthropic
reconnaissance vocale Android ──► POST /api/understand ──────────────────► Claude (sorties structurées JSON)
(texte uniquement)                 · jeton d'application, quotas            
                                   · date/heure/fuseau DU TÉLÉPHONE
                                   · calendrier des 5 semaines fourni
                                 ◄─ validation (understanding.js) ◄──────── JSON
validation défensive (Understanding.kt)
→ séances → planificateur (agenda + indisponibilités) → « Tout accepter » → tâches Bullylist ordinaires
```

- **Étape 1, voix → texte** : la reconnaissance vocale d'Android (inchangée). L'audio ne quitte jamais l'application vers le serveur Bullylist.
- **Étape 2, texte → données** : Claude reçoit la transcription, la date locale, l'heure, le fuseau et un calendrier explicite (« mardi 2026-11-10 ») pour ne jamais calculer un jour de tête. Il répond dans un schéma JSON imposé (`SCHEMA` dans `web/js/core/understanding.js`).
- **Jamais de confiance aveugle** : le serveur revalide tout (types, dates réelles, jour de semaine cohérent avec la date, dates passées signalées par une question, durées plausibles, doublons fusionnés, dépendances inconnues et cycles retirés). L'application relit encore la réponse de façon défensive.
- **Conversation** : chaque nouveau tour envoie la compréhension précédente (retouches de l'utilisateur comprises). Le modèle renvoie la liste complète mise à jour, avec les mêmes identifiants : pas de doublon, rien n'est effacé sans annulation explicite.
- **Repli** : réseau coupé, serveur arrêté, quota atteint → le moteur local relit toute la conversation et l'écran l'indique. Les dictées non transmises sont envoyées au tour suivant.

### Format des données

Chaque tâche distingue : jour de travail (`planned_day`), échéance (`deadline`), heure imposée (`start_time`), durée (`duration_minutes` + `duration_source` : dite / estimée / inconnue), priorité, jours de séances (`session_days`), sous-tâches (avec leur propre jour et durée), dépendances, projet, extrait source, niveau de confiance. Les indisponibilités (« j'ai cours le matin », « rien après 18 heures ») sont des `constraints`, pas des tâches. La date de création est celle de l'enregistrement ; l'heure de rappel est le début du bloc planifié. Ces notions ne sont jamais confondues.

À l'enregistrement, une tâche composée devient : une tâche par séance datée, rangées dans **un seul projet** au nom du travail, et la liste à cocher (sous-tâches sans jour) sur la tâche principale. Ce sont des tâches Bullylist ordinaires : modifiables, déplaçables, rappels, projet, terminables. **Aucune migration de base n'a été nécessaire** (projets, sous-tâches, `plannedStart`, `deadlineAt` existaient).

## Mettre en service

### 1. Clé API

Créer une clé sur <https://console.anthropic.com> (compte de facturation requis). Elle ne doit **jamais** être mise dans l'application : seul le serveur la connaît.

### 2. Lancer le serveur

```bash
cd server && npm ci
ANTHROPIC_API_KEY=sk-ant-… BRR_APP_TOKEN=$(openssl rand -hex 24) node server.mjs
# → Bullylist IA sur http://localhost:8787
```

| Variable | Défaut | Rôle |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Clé du fournisseur (ou profil `ant auth login`) |
| `GEMINI_API_KEY` | — | Clé Google AI Studio. Si elle est présente, Gemini est utilisé (mettre la clé dans `server/.env`, puis `npm start`). Sur le niveau gratuit, Google peut utiliser les requêtes pour améliorer ses produits |
| `BRR_PROVIDER` | `gemini` si `GEMINI_API_KEY` existe, sinon `anthropic` | Force le fournisseur |
| `BRR_APP_TOKEN` | vide | Jeton exigé de l'application. **À définir avant toute mise en ligne**, sinon n'importe qui peut consommer ton crédit |
| `BRR_MODEL` | `claude-opus-5-5` (`gemini-3.5-flash` avec Gemini) | Modèle. `claude-haiku-5-5` divise le coût par ~40, au prix d'une compréhension moins fine (à mesurer avec la banque de tests) |
| `BRR_EFFORT` | `high` | Profondeur de réflexion (`low`…`max`). La fiabilité a été demandée avant la vitesse |
| `BRR_DAILY_LIMIT` / `BRR_PER_MINUTE` | 60 / 6 | Quotas par jeton (ou par adresse sans jeton) |
| `BRR_ALLOWED_ORIGINS` | `http://localhost:8080,…` | Origines web autorisées (console) |
| `PORT` | 8787 | Port d'écoute |

Le serveur n'a qu'une dépendance (`@anthropic-ai/sdk`) et lit `web/js/core/` : déployer le dépôt entier (ou ces deux dossiers). Tout hébergeur Node convient ; en production, le placer derrière **HTTPS** (la version publiée de l'application refuse `http`).

### 3. Configurer l'application

Réglages › **Assistant IA** : adresse du serveur, jeton, « Tester la connexion », accord d'envoi, puis activer. Pour un test depuis le PC sur le réseau local, la version de test (APK debug) accepte `http://ADRESSE-DU-PC:8787`.

## Coûts (estimations, non mesurées)

Tarifs Claude Opus 5.5 : 4 $ / million de jetons lus, 20 $ / million écrits (consignes en cache : 0,20 $). Une dictée d'une semaine ≈ 4 000 jetons lus et 3 000 à 6 000 écrits (réflexion comprise), soit **environ 0,08 à 0,15 $ par analyse**. Chaque précision relit la compréhension précédente (un peu plus de jetons lus). Avec Claude Haiku 5.5 : ~0,003 $ par analyse. Les chiffres réels s'affichent dans la console (`usage`) et dans le rapport du serveur. Les quotas par jour bornent la dépense.

## Confidentialité

- Désactivé par défaut. Rien ne part sans l'accord explicite (Réglages › Assistant IA), retirable à tout moment.
- Envoyé : le texte dicté, la date/heure/fuseau, le planning en cours de discussion. Jamais l'audio, les autres tâches ni l'agenda.
- Le serveur ne journalise aucune transcription (test automatique dédié). Anthropic traite la requête selon ses conditions d'API.
- Pages mises à jour : Réglages › Stockage, page Confidentialité du site.

## Tester

```bash
./scripts/test.sh            # tout, sans appel facturé (moteur local, plomberie du serveur, navigateurs)
./scripts/test.sh --android  # + tests JVM Android (parité JS/Kotlin)

# Console vocale : http://localhost:8080/console-ia/ (après `cd web && npm start`)
# Banque de 34 scénarios contre le VRAI modèle (facturé, ~3 à 5 $ avec Opus) :
node web/test/assistant-eval.mjs --server http://localhost:8787 --token $BRR_APP_TOKEN
# → docs/rapports/assistant-ia.md
```

## Limites connues

- **La compréhension par l'IA n'a pas été mesurée** : aucune clé n'était disponible pendant le développement. La plomberie (requêtes, contexte, validation, repli, affichage) est testée avec une réponse fixe, ce qui ne dit rien de la qualité du modèle. Lancer la banque `--server` avant de s'y fier.
- Moteur local : 2 scénarios échouent (« terminé deux jours avant le 25 », « répartir entre mardi et jeudi ») : ils demandent un raisonnement, laissé à l'IA.
- En mode local, une précision relit toute la conversation : les retouches manuelles faites entre deux dictées sont perdues (en mode IA, elles sont conservées).
- Modèle local sur le téléphone : écarté. Un modèle capable de ce niveau de compréhension en français pèse plusieurs Go et serait lent sur la plupart des téléphones ; le moteur déterministe amélioré joue ce rôle de secours.
- La dictée de la console (navigateur) n'est pas la reconnaissance d'Android : la transcription peut différer sur le téléphone.
