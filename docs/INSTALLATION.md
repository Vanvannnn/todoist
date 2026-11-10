# Installation et déploiement

## Prévisualiser sur l'ordinateur

```bash
cd ~/todo
./scripts/preview.sh          # http://localhost:8080/ et /simulateur/
```

Prérequis : Node.js et Python 3 (déjà installés). La dictée au micro du simulateur fonctionne dans Chrome. Si le navigateur ne la prend pas en charge, le mode texte donne exactement la même analyse.

## Compiler l'APK Android

Prérequis déjà en place : JDK 17 et SDK `~/android-sdk-bullylist` (plateforme 35). Sur une autre machine, crée `android/local.properties` :

```properties
sdk.dir=/chemin/vers/Android/Sdk
```

Gradle télécharge seul les éléments manquants du SDK si leurs licences sont acceptées (dossier `licenses/`).

```bash
cd android
./gradlew testDebugUnitTest   # tests unitaires (logique, parité avec le web)
./gradlew assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
```

**Version release (plus légère, environ 4 Mo)** : crée ta clé une fois, puis **garde-la précieusement**. Android refuse les mises à jour signées avec une autre clé.

```bash
mkdir -p keystore
keytool -genkeypair -keystore keystore/bullylist.jks -alias bullylist -keyalg RSA -keysize 2048 -validity 10000
cat > keystore.properties <<EOF
storeFile=keystore/bullylist.jks
storePassword=…
keyAlias=bullylist
keyPassword=…
EOF
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

## Installer sur le téléphone

**Par câble (ADB)** : `adb.exe` est déjà présent côté Windows.

```bash
/mnt/c/Users/evand/AppData/Local/Android/Sdk/platform-tools/adb.exe devices
/mnt/c/Users/evand/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r "$(wslpath -w android/app/build/outputs/apk/debug/app-debug.apk)"
```

**Sans câble** : copie l'APK sur le téléphone (Drive, e-mail…), ouvre-le et autorise « Installer des applis inconnues » pour cette source.

Points importants :
- La version debug s'installe sous `com.brr.app.debug`, à côté d'une éventuelle release. Le nom affiché est « Bullylist ».
- L'identifiant interne `com.brr.app` est conservé pour qu'une mise à jour garde tes tâches. Si l'APK V1 installé a été signé par une autre clé (compilé sur une autre machine), Android refusera la mise à jour : exporte tes tâches ou désinstalle-le d'abord.
- La base passe automatiquement de la version 1 à la version 2 (ajout de colonnes et de tables uniquement, aucune perte).

Au premier lancement, accorde dans l'écran d'accueil puis dans **Réglages → Fiabilité et autorisations** :
- notifications ;
- alarmes exactes ;
- notifications plein écran (Android 14+) ;
- micro (assistant vocal) ;
- agenda (facultatif, pour Google Agenda).

## Publier l'APK sur le site

```bash
./scripts/publish-apk.sh [chemin.apk] ["notes de version"]
```

Le script copie l'APK dans `web/downloads/` et écrit `latest.json` (version, date, taille, SHA-256, compatibilité). La page Téléchargement affiche le bouton seulement si ce fichier existe.

## Déployer le site

Le dossier `web/` est un site **statique**, sans étape de construction à l'hébergement.
1. `node design/build.mjs && node web/tools/build-pages.mjs`
2. `./scripts/publish-apk.sh` si un APK doit être proposé
3. Envoyer le contenu de `web/` (sauf `node_modules/`, `test/`, `dev/`, `src/`, `tools/`) sur n'importe quel hébergement statique : GitHub Pages, Netlify, Vercel, Cloudflare Pages, ou un serveur web classique.
4. Dans `web/sitemap.xml`, remplacer `./` par l'adresse publique.

Rien n'a été mis en ligne : la publication reste ton choix.
