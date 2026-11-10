#!/usr/bin/env bash
# BULLYLIST — publie un APK compilé sur le site : ./scripts/publish-apk.sh [chemin.apk] ["notes de version"]
# Copie l'APK dans web/downloads/ et écrit web/downloads/latest.json (version, date, taille, SHA-256, compatibilité).
# La page Téléchargement n'affiche le bouton que si ce fichier existe.
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:-android/app/build/outputs/apk/debug/app-debug.apk}"
NOTES="${2:-}"
[ -f "$APK" ] || { echo "APK introuvable : $APK (compile d'abord : cd android && ./gradlew assembleDebug)"; exit 1; }
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts | head -1)
MINSDK=$(sed -n 's/.*minSdk = \([0-9]*\).*/\1/p' android/app/build.gradle.kts | head -1)
VARIANT=$([[ "$APK" == *release* ]] && echo "release" || echo "debug (signée avec la clé de débogage)")
FILE="bullylist-$VERSION.apk"
mkdir -p web/downloads
cp "$APK" "web/downloads/$FILE"
SIZE=$(stat -c %s "web/downloads/$FILE")
SHA=$(sha256sum "web/downloads/$FILE" | cut -d' ' -f1)
declare -A ANDROID=([21]="5.0" [23]="6.0" [24]="7.0" [26]="8.0" [28]="9" [29]="10" [30]="11" [31]="12" [33]="13" [34]="14")
node -e '
const [file, version, size, sha, minSdk, minAndroid, variant, notes] = process.argv.slice(1);
require("fs").writeFileSync("web/downloads/latest.json", JSON.stringify({ version, date: new Date().toISOString(), file, size: +size, sha256: sha, minSdk: +minSdk, minAndroid, variant, notes }, null, 2) + "\n");
' "$FILE" "$VERSION" "$SIZE" "$SHA" "$MINSDK" "${ANDROID[$MINSDK]:-?}" "$VARIANT" "$NOTES"
echo "Publié : web/downloads/$FILE ($((SIZE / 1024)) Ko, SHA-256 $SHA)"
