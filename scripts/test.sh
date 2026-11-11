#!/usr/bin/env bash
# BULLYLIST — tous les tests : ./scripts/test.sh [--android]
set -euo pipefail
cd "$(dirname "$0")/.."
node design/build.mjs >/dev/null && node web/tools/build-pages.mjs >/dev/null
echo "== Cœur partagé (Node)"; (cd web && npm test --silent)
echo "== Serveur IA (plomberie, sans appel au modèle)"; (cd server && { [ -d node_modules ] || npm ci --silent; } && npm test --silent)
echo "== Banque de scénarios vocaux (moteur local)"; (cd web && TZ=Europe/Paris node test/assistant-eval.mjs | tail -2)
PORT=8765
python3 -m http.server $PORT --directory web --bind 127.0.0.1 >/dev/null 2>&1 & SRV=$!
trap 'kill $SRV' EXIT; sleep 1
if node -e "require.resolve('playwright-core')" 2>/dev/null || [ -d web/node_modules/playwright-core ]; then
  echo "== Site (navigateur)"; (cd web && node test/e2e/site.e2e.mjs "http://127.0.0.1:$PORT/")
  echo "== Simulateur (navigateur)"; (cd web && node test/e2e/simulator.e2e.mjs "http://127.0.0.1:$PORT/simulateur/")
  echo "== Console vocale (navigateur)"; (cd web && node test/e2e/console.e2e.mjs "http://127.0.0.1:$PORT/console-ia/")
else
  echo "== Tests navigateur ignorés : 'cd web && npm i -D playwright-core' (≈ 8 Mo, réutilise Chromium)"
fi
if [ "${1:-}" = "--android" ]; then echo "== Android (JVM)"; (cd android && ./gradlew --no-daemon -q testDebugUnitTest); fi
