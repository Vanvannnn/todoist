# B.R.R. — Be Right Responsible

Gestionnaire de tâches Android natif, élégant comme Todoist, avec un système de rappels
volontairement excessif, sarcastique et impossible à ignorer. Entièrement hors ligne : pas de compte,
pas de serveur, pas de publicité.

## Installer l'APK

APK déjà compilés (après `./gradlew assembleDebug assembleRelease`) :

| Variante | Chemin | Remarque |
|---|---|---|
| Release (recommandée) | `app/build/outputs/apk/release/app-release.apk` | Minifiée (R8), signée avec `keystore/brr-release.jks` |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | Package `com.brr.app.debug`, peut cohabiter avec la release |

Sur le téléphone :
1. Copier l'APK sur le téléphone (câble, Drive, mail…) ou `adb install -r app-release.apk`.
2. Ouvrir le fichier et autoriser « Installer des applis inconnues » pour l'appli utilisée.
3. Au premier lancement, suivre l'écran d'accueil et accorder les autorisations.

> **Garde `keystore/brr-release.jks` et `keystore.properties`** : ils sont nécessaires pour publier des mises
> à jour de la version release (Android refuse une mise à jour signée avec une autre clé). Ils ne sont pas versionnés.

## Compiler

Prérequis : JDK 17 et Android SDK (platform 35, build-tools 35.0.0). Android Studio Ladybug ou plus récent
ouvre directement le projet.

```bash
# indiquer le SDK (ou laisser Android Studio créer local.properties)
echo "sdk.dir=/chemin/vers/Android/Sdk" > local.properties

./gradlew testDebugUnitTest   # 47 tests unitaires
./gradlew assembleDebug       # APK debug
./gradlew assembleRelease     # APK release (signée si keystore.properties existe)
```

Pour signer la release avec ta propre clé :

```bash
keytool -genkeypair -keystore keystore/brr-release.jks -alias brr -keyalg RSA -keysize 2048 -validity 10000
cat > keystore.properties <<EOF
storeFile=keystore/brr-release.jks
storePassword=...
keyAlias=brr
keyPassword=...
EOF
```

Sans `keystore.properties`, `assembleRelease` produit `app-release-unsigned.apk` (non installable tel quel).

## Fonctionnalités

**Tâches** — création ultra-rapide en langage naturel (`Sortir les poubelles demain 20h !1 #Maison @brr`),
modification, suppression, description, échéance avec ou sans heure, rappels multiples avant l'échéance,
répétition (quotidienne, hebdomadaire avec jours choisis, mensuelle, annuelle, intervalle personnalisé),
priorités P1–P4, projets colorés, sous-tâches, recherche, filtres (priorité, niveau, projet), vues
Aujourd'hui / Demain / Cette semaine / Toutes / En retard, glisser pour terminer (→) ou reporter à demain (←),
annulation après validation, historique et tâches terminées, tableau de bord avec indice de procrastination.

**Saisie rapide** : `aujourd'hui`, `demain`, `après-demain`, `lundi`…`dimanche`, `18h`, `18h30`, `18:30`,
`à 9h`, `ce soir`, `dans 2h`, `dans 30 min`, `dans 3 jours`, `!1`…`!4` (ou `!urgente`, `!haute`…), `#Projet`
(créé s'il n'existe pas), `@normal`, `@insistant`, `@harcelement`, `@brr`.

**Quatre niveaux de rappel** (par tâche) :

| Niveau | Comportement |
|---|---|
| 1 Normal | Notification classique, son et vibration discrets, message personnalisé possible |
| 2 Insistant | Notification prioritaire, vibration marquée, bandeau animé dans l'appli, message sarcastique, actions +5/+15 min |
| 3 Harcèlement | Écran immersif plein écran (fond animé, compte à rebours, punchline), « C'EST FAIT » / « JE REPORTE », relances à intervalle défini |
| 4 Mode B.R.R. | Écran d'« urgence nationale », titre géant, son répété (`FLAG_INSISTENT`), notification persistante jusqu'à action |

**Escalade** — scénario par défaut : échéance (niveau 1) → +5 min (2) → +10 (3) → +15 (4) → +20 → +30 (dernier
avertissement). Intervalles, nombre maximum de relances (anti-boucle) et intervalle par tâche configurables.
Actions : terminer, +5 min, +15 min, +1 h, demain, couper les rappels de la tâche. Terminer une tâche annule
immédiatement toutes ses alarmes et retire sa notification.

**Punchlines** — 161 punchlines originales en 8 catégories (sarcastique, provocateur, motivation toxique,
humiliation légère, absurde, coach militaire, autodérision, dernier avertissement), intensité 1–4 adaptée au
niveau, tirage aléatoire sans répétition récente (40 dernières mémorisées), catégories désactivables,
punchlines personnelles, générateur contextuel basé sur le nom de la tâche (« La vaisselle ne va pas se laver
avec tes bonnes intentions. »).

**Personnalisation** — thème sombre/clair/système, mode B.R.R. on/off, escalade progressive, niveau par défaut,
intervalles, relances max, plage horaire autorisée (les rappels hors plage sont décalés), heure des tâches
« journée », son par niveau (6 sons originaux + son système + silencieux), volume de l'écran immersif,
vibration et durée, aperçu de chaque niveau.

## Respect du téléphone

- Les sons des notifications utilisent `USAGE_NOTIFICATION` : le mode silencieux et le vibreur s'appliquent.
- L'écran immersif ne joue un son que si la sonnerie est en mode normal **et** que Ne pas déranger est
  inactif ; il ne vibre pas en mode silencieux. Il ne joue rien si le canal a été rendu muet dans les réglages.
- Ne pas déranger : les notifications sont filtrées par Android selon ses propres règles (aucun accès
  « politique de notification » n'est demandé, aucun contournement).
- Le plein écran passe exclusivement par `setFullScreenIntent` et l'autorisation
  `USE_FULL_SCREEN_INTENT` (soumise à l'accord de l'utilisateur sur Android 14+). Pas de remplacement du
  launcher, pas de modification de réglages système.
- Le clignotement de l'écran immersif reste sous 1 Hz (pas de risque photosensible).

## Fiabilité

- `AlarmManager.setAlarmClock` (niveaux 3–4) ou `setExactAndAllowWhileIdle` (niveaux 1–2) ;
  repli automatique sur `setAndAllowWhileIdle` sans autorisation d'alarme exacte.
- `USE_EXACT_ALARM` (Android 13+) / `SCHEDULE_EXACT_ALARM` (Android 12–12L).
- Reprogrammation après redémarrage, mise à jour de l'appli, changement d'heure ou de fuseau, et
  changement d'autorisation d'alarme exacte (`SystemEventsReceiver`).
- Vérification au démarrage du processus et toutes les 6 h via WorkManager (`ReminderVerifyWorker`) :
  les alarmes disparues sont reprogrammées, les alarmes orphelines nettoyées.
- Protection contre les alarmes obsolètes et les doubles déclenchements (opérations sérialisées par un mutex).
- Écran « Fiabilité » : état de chaque autorisation, bouton pour l'accorder, diagnostic manuel,
  explications sur les restrictions des constructeurs. **Aucune alarme n'est présentée comme garantie.**

## Architecture

```
app/src/main/java/com/brr/app/
├── AppContainer.kt / BrrApplication.kt / MainActivity.kt   injection manuelle, démarrage
├── domain/            logique pure, sans Android (testée)
│   ├── ReminderPlanner.kt       prochain déclenchement, escalade, plages horaires, limite
│   ├── RecurrenceCalculator.kt  répétitions
│   ├── QuickAddParser.kt        saisie en langage naturel
│   └── TaskBuckets.kt           vues Aujourd'hui / Demain / Semaine / En retard
├── data/
│   ├── db/            Room : tâches, rappels, sous-tâches, projets, historique, punchlines perso
│   ├── prefs/         DataStore : réglages
│   └── repository/    TaskRepository, ProjectRepository, PunchlineRepository, TaskManager (façade)
├── reminder/          ReminderCoordinator (testé), AlarmManager, receivers, canaux, notifications,
│                      écran/son/vibration, WorkManager, autorisations
├── punchline/         catalogue, moteur de tirage, générateur contextuel
└── ui/                Compose + Material 3 (MVVM : un ViewModel par écran)
    ├── alarm/         écran immersif (AlarmActivity)
    ├── screens/       accueil, tâches, tableau de bord, édition, détail, projets, historique,
    │                  réglages, alarmes, punchlines, fiabilité
    ├── components/    composants partagés, icônes
    └── theme/         palette, typographie
```

Toute modification de tâche passe par `TaskManager` → `ReminderCoordinator`, qui recalcule l'unique
alarme de la tâche via `ReminderPlanner` : les rappels restent toujours cohérents avec les données.

## Ressources

- Sons : synthétisés pour le projet par `tools/generate_sounds.py` (aucun droit tiers).
  `python3 tools/generate_sounds.py app/src/main/res/raw` pour les régénérer.
- Polices : Space Grotesk et Inter, licence SIL Open Font License 1.1.
- Icônes : vecteurs du projet et Material Icons (Apache 2.0).

## Tests

`./gradlew testDebugUnitTest` — 47 tests :
`ReminderPlannerTest` (escalade complète, limites, report, plages horaires, tâches « journée »),
`ReminderCoordinatorTest` (programmation, annulation immédiate à la validation, report, demain, répétitions,
réparation des alarmes perdues, alarmes obsolètes), `RecurrenceCalculatorTest` (y compris changement d'heure),
`QuickAddParserTest`, `TaskBucketsTest`, `PunchlineEngineTest` (≥ 150 punchlines uniques, catégories,
non-répétition, intensité).
