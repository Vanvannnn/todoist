# Audit — environnement, Starva Cloud et projet existant

Réalisé le 10 novembre 2026, avant toute modification.

## Ordinateur (WSL2 Ubuntu 24.04)

| Outil | État | Utilisation pour Bullylist |
|---|---|---|
| Node.js 24.20 (nvm) | installé | tests du cœur partagé, générateur de la charte, scripts |
| Python 3.12 | installé | serveur local de prévisualisation (`http.server`) |
| OpenJDK 17 | installé | compilation Android |
| Chromium (cache Playwright `~/.cache/ms-playwright`) | installé | tests automatisés du site et du simulateur |
| Chrome Windows | installé | ouvrir la prévisualisation |
| SDK Android de buildozer (`~/.buildozer`, plateforme 33) | installé (Starva) | **non utilisé** : le projet exige la plateforme 35 ; laissé intact |
| SDK Android Windows (`C:\Users\evand\AppData\Local\Android\Sdk`) | installé | `adb.exe` disponible ; aucun téléphone connecté pendant l'audit |
| Android Studio, émulateur | absents | **non installés** (inutiles) |

Espace disque libre : 934 Go.

## Starva Cloud

Le dossier s'appelle en réalité `~/starvaclaude` (application « Loopy »). Aucun dossier `Starva Cloud` n'existe ; `~/starva` et `~/starvav2` sont des versions antérieures.

- **Technologies** : application Android en **Python + Kivy**, empaquetée par **buildozer** ; serveur **FastAPI + SQLite** ; site statique servi par le serveur.
- **Méthode de simulation** : le code métier est en Python pur, partagé entre l'app et le serveur. `python main.py` lance l'application Kivy **directement sur le PC** (fenêtre de bureau, sortie GPS simulée par `capteurs/simulateur.py`) ; `uvicorn` sert l'API et le site sur `localhost:8000` ; `pytest` teste la logique pure.
- **Ce qui a été repris pour Bullylist** : le principe, et non la technologie. On garde un cœur métier pur et testé, partagé entre l'application et la simulation, une prévisualisation locale en une commande, un site servi en local, et un bilan honnête de ce qui est testé ou non. Bullylist est une application **Kotlin/Compose native** : le simulateur ne peut donc pas exécuter le code Android tel quel. La logique est écrite deux fois (JavaScript pour le navigateur, Kotlin pour Android), et les deux versions sont vérifiées contre **les mêmes cas de test JSON**.
- Starva Cloud n'a pas été modifié.

## Projet existant (B.R.R.)

- Dépôt `~/todo`. Le dossier suivi `todo/` avait été renommé en `todoist/` sans commit, avec un contenu identique au commit initial (vérifié fichier par fichier). Le renommage a été enregistré dans l'historique, puis le dossier est devenu `android/` (`git log --follow` fonctionne).
- Application Kotlin + Jetpack Compose + Room + DataStore + WorkManager, environ 6 800 lignes, architecture propre (`domain/` pur et testé, `ReminderCoordinator` central).
- État vérifié : **compilation et 47 tests unitaires réussis** avant modification.
- Problèmes relevés :
  1. Aucun SDK Android compatible sur l'ordinateur (l'APK V1 avait été compilé ailleurs).
  2. Le nom « B.R.R. » figurait dans 20 textes visibles.
  3. Pas de connexion réseau ni de calendrier : le mode pause était impossible.
  4. `keystore/` absent : la release V1 signée ne peut pas être mise à jour avec la même clé.
- Conservé tel quel : tâches, base de données (migration automatique 1 → 2 sans perte), projets, priorités, rappels et escalade, quatre niveaux, 161 punchlines, saisie rapide, répétitions, historique, statistiques, fiabilité des alarmes.

## Installations réalisées

| Élément | Taille | Pourquoi |
|---|---|---|
| `~/android-sdk-bullylist` (plateforme 35, build-tools 34, platform-tools) | 314 Mo | compiler l'APK sans toucher au SDK de Starva |
| Dépendances Gradle (`~/.gradle`) | + 1,2 Go | Compose, Room, Kotlin… |
| `web/node_modules/playwright-core` | 8 Mo | tests navigateur (réutilise le Chromium existant) |
| Polices Archivo et JetBrains Mono (OFL) | 0,8 Mo | identité visuelle |

Aucun droit administrateur, aucune modification système. Tout se supprime en effaçant ces dossiers.
