# BULLYLIST

**YOUR TASKS. YOUR PROBLEM. OUR BUSINESS.** Gestionnaire de tâches Android, assistant vocal de planification et système d'alertes qui va du rappel discret au *Bully Mode: Nuclear*.

## En une commande

```bash
./scripts/preview.sh
```

Puis ouvre :

| | Adresse |
|---|---|
| Site | http://localhost:8080/ |
| **Simulateur** (application + console de test) | http://localhost:8080/simulateur/ |
| Démo des alertes | http://localhost:8080/demo.html |

Aucune installation : Node.js et Python 3 suffisent (déjà présents).

## Contenu

| Dossier | Rôle |
|---|---|
| `android/` | Application Android (Kotlin, Compose, Room). Ex-B.R.R., historique conservé |
| `web/` | Site, simulateur et cœur JavaScript partagé (aucune dépendance d'exécution) |
| `web/simulateur/` | Simulateur navigateur : l'application, sa console de notifications, de calendrier et d'horloge |
| `web/js/core/` | Dictée → tâches, estimation des durées, planning, lecture ICS, mode pause, punchlines |
| `design/` | **Charte partagée** : `tokens.json`, `punchlines.json`, cas de test communs (`fixtures/`), générateur `build.mjs` |
| `scripts/` | `preview.sh`, `test.sh`, `publish-apk.sh` |
| `docs/` | Audit, installation, calendriers, charte, assistant IA, bilan |
| `server/` | Serveur léger de compréhension IA (garde la clé API hors de l’application) |
| `releases/v1/` | APK de la version 1 (B.R.R.), conservés |

## Commandes

```bash
./scripts/preview.sh [port]          # site + simulateur en local
./scripts/test.sh                    # cœur JS + site + simulateur (navigateur réel)
./scripts/test.sh --android          # … + tests unitaires Android
node design/build.mjs                # régénère CSS/Kotlin depuis design/*.json
cd android && ./gradlew assembleDebug  # APK → android/app/build/outputs/apk/debug/app-debug.apk
./scripts/publish-apk.sh             # met l'APK à disposition sur la page Téléchargement
```

Pour compiler l'APK, `android/local.properties` doit pointer vers le SDK (`sdk.dir=/home/vanvan/android-sdk-bullylist`). Voir [docs/INSTALLATION.md](docs/INSTALLATION.md).

## Documentation

- [docs/AUDIT.md](docs/AUDIT.md) : l'ordinateur, Starva Cloud, l'état du projet V1, les installations réalisées
- [docs/INSTALLATION.md](docs/INSTALLATION.md) : compiler, installer l'APK, publier le site
- [docs/CALENDRIERS.md](docs/CALENDRIERS.md) : CELCAT, Google, Apple, mode pause
- [docs/ASSISTANT-IA.md](docs/ASSISTANT-IA.md) : assistant vocal V2.1, serveur IA, coûts, confidentialité, tests
- [docs/rapports/assistant-local.md](docs/rapports/assistant-local.md) : banque de 34 scénarios, moteur local
- [docs/CHARTE.md](docs/CHARTE.md) : identité visuelle
- [docs/BILAN.md](docs/BILAN.md) : ce qui est terminé, ce qui est testé et comment, ce qui reste à vérifier sur téléphone
