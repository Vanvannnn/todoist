# Bilan de la V2

État au 10 novembre 2026. Branche `v2` du dépôt `~/todo`.

## Tests réellement exécutés

| Suite | Commande | Résultat |
|---|---|---|
| Cœur partagé JavaScript (dictée, planning, ICS, pause, estimation, punchlines) | `cd web && npm test` | **26/26** |
| Site dans Chromium (7 pages, 4 niveaux × variantes de la démo, aucune erreur JS ni texte « undefined ») | `node web/test/e2e/site.e2e.mjs` | **réussi** |
| Simulateur de bout en bout dans Chromium (12 étapes, détail ci-dessous) | `node web/test/e2e/simulator.e2e.mjs` | **12/12** |
| Android, tests unitaires JVM (47 tests V1 conservés, parité avec le web, pause, ICS, captures) | `cd android && ./gradlew testDebugUnitTest` | **63/63** |
| Compilation de l'APK | `./gradlew assembleDebug` | **réussie** |
| Rendu réel des 6 écrans d'alerte Compose (layoutlib, sans émulateur) | `./gradlew recordPaparazziDebug` | captures dans `android/app/src/test/snapshots/images/` |
| Absence de débordement horizontal à 390 px (7 pages) | script Playwright | **réussi** |

Tout se relance avec `./scripts/test.sh --android`.

**Parité JavaScript/Kotlin** : les mêmes fichiers `design/fixtures/*.json` sont joués par les deux implémentations (9 dictées, 2 flux ICS avec résultats calculés à la main, 7 cas de pause). Un test « propriété » génère **300 semaines aléatoires** dans chaque langage et vérifie qu'aucune règle du planning n'est violée :
- pas de tâche pendant un cours ou un trajet ;
- pause entre deux blocs ;
- blocs de 90 minutes au plus ;
- plafond quotidien respecté ;
- marge avant l'échéance ;
- repas présent, ou absence signalée.

Ces tests ont trouvé de vrais défauts, tous corrigés :
- « envoyer » pris pour le pronom « en » ;
- « 21h30 » lu comme une durée ;
- « il faudrait absolument que je » mal compris en Kotlin ;
- titres d'alerte affichant « undefined » ;
- variables CSS de la charte mal nommées ;
- punchline vide sur Android sans animation.

### Étapes du test du simulateur

1. Chargement de la semaine étudiante.
2. Dictée de l'exemple du brief → 5 tâches et planning.
3. Modification d'une estimation → replanification.
4. « Tout accepter » → créneaux enregistrés.
5. Tâche en retard → écran Nuclear.
6. « Je passe à l'action » termine la tâche.
7. Pause de cours : aucune alerte, rappel décalé à la fin du cours + 20 min.
8. Le rappel sonne à la reprise.
9. Ne pas déranger : pas de plein écran.
10. Les 4 boutons TEST NIVEAU.
11. Import ICS de fichier.
12. Persistance après rechargement.

## Terminé

| Fonctionnalité | Web / simulateur | Android |
|---|---|---|
| Marque BULLYLIST partout (nom, icône, notifications, écrans, site) | ✔ | ✔ (le nom de paquet interne `com.brr.app` est conservé pour les mises à jour, il n'est pas affiché) |
| Charte partagée (une source → CSS + Kotlin) | ✔ | ✔ |
| 4 niveaux d'alerte, 6 variantes immersives, compteurs, punchlines défilantes, ≤ 1 Hz | ✔ | ✔ (rendu vérifié par capture) |
| 463 punchlines / 17 catégories (302 nouvelles), messages contextuels et de retard, intensité réglable, catégories désactivables | ✔ | ✔ |
| Dictée → tâches, échéances, hypothèses affichées, urgence, répétitions, durées, dépendances | ✔ | ✔ (même logique, testée) |
| Estimation des durées + apprentissage des durées réelles | ✔ | ✔ |
| Planning réaliste, alternatives si impossible, édition (durée, jour, heure, niveau, suppression), « Tout accepter » | ✔ | ✔ |
| Lecture ICS (CELCAT, iCloud public, Google iCal) : répétitions, exceptions, cours déplacés ou annulés, fuseaux | ✔ (fichier) | ✔ (lien et fichier) |
| Mode pause : profils, trajets, fusion, report sans perte, exception explicite au niveau 4, coupure manuelle | ✔ | ✔ (intégré à `ReminderPlanner`, testé) |
| Toutes les fonctions V1 (tâches, projets, priorités, répétitions, escalade, historique, statistiques…) | partiel (le simulateur n'en reproduit qu'une partie) | ✔ conservées, 47 tests V1 réussis |
| Site : accueil interactif, fonctionnalités, démo, punchlines, assistant, téléchargement, confidentialité, SEO de base | ✔ | — |
| APK compilé et publié sur la page Téléchargement | ✔ | `web/downloads/bullylist-2.0.0.apk` |

## Simulé dans le navigateur, et seulement simulé

Le simulateur reproduit l'interface et la **logique**. Il ne prouve pas que les mécanismes Android fonctionnent :
- notifications système, canaux et sons Android ;
- plein écran sur écran verrouillé ;
- `AlarmManager` exact et redémarrage du téléphone ;
- Ne pas déranger réel ;
- autorisations ;
- reconnaissance vocale du téléphone ;
- lecture des agendas du téléphone.

Le son et la vibration du simulateur suivent les mêmes règles que l'application, mais ils sont joués par le navigateur.

## À vérifier sur un vrai téléphone (non testé : aucun appareil n'était branché)

1. Installation de l'APK, puis migration de la base V1 → V2 sur un téléphone qui a déjà des tâches. La migration automatique Room est générée et validée à la compilation, mais elle n'a pas été exécutée sur un appareil.
2. Notifications des niveaux 1 et 2, plein écran des niveaux 3 et 4 (autorisation Android 14+), écran verrouillé.
3. Programmation, annulation, report, escalade et répétition des alarmes en conditions réelles, y compris après un redémarrage. La logique est couverte par les tests V1 et V2 ; le comportement d'`AlarmManager` sur l'appareil ne l'est pas.
4. Mode pause avec un vrai calendrier, puis reprise après un cours.
5. Dictée avec la reconnaissance vocale du téléphone : qualité de la transcription, comportement hors ligne avec le pack français.
6. Lien CELCAT réel de ton université (format de son flux) et agendas Google du téléphone.

## Nécessite une configuration de ta part

| Élément | Ce qui manque |
|---|---|
| Emploi du temps CELCAT | Le lien d'abonnement iCal de ton université (voir `docs/CALENDRIERS.md`) |
| Google Agenda | Ton compte Google ajouté au téléphone, puis l'autorisation « Agenda » |
| Apple Calendar | Un lien de calendrier public, ou DAVx⁵ (aucune intégration native Apple n'existe sur Android) |
| APK release (≈ 4 Mo, minifié) | Une clé de signature à créer et à conserver (`docs/INSTALLATION.md`) ; l'APK actuel est signé avec la clé de débogage |
| Site en ligne | Un hébergement statique de ton choix ; rien n'a été publié |

## Non développé, volontairement

- **OAuth Google Calendar direct** : il exige un projet Google Cloud, un identifiant client et une vérification de l'application. La lecture des agendas déjà synchronisés sur le téléphone donne le même résultat, sans mot de passe.
- **Lecture de pages CELCAT** (scraping) et saisie d'identifiants : exclues par principe.
- **Reconnaissance vocale embarquée** (type Whisper) : le service du téléphone fonctionne hors ligne avec le pack de langue français, sans ajouter des centaines de Mo à l'APK.

## Espace disque utilisé

| Élément | Taille |
|---|---|
| Dépendances Gradle (`~/.gradle`, dont layoutlib pour les captures) | + 2,5 Go (de 1,1 à 3,6 Go) |
| SDK Android dédié (`~/android-sdk-bullylist`) | 314 Mo |
| `web/node_modules` (playwright-core) | 8 Mo |
| Fichiers de compilation (`android/app/build`, supprimables) | 109 Mo |
| Projet hors `.git` et fichiers générés | 30 Mo |
