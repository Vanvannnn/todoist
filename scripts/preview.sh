#!/usr/bin/env bash
# BULLYLIST — prévisualisation locale en une commande : ./scripts/preview.sh [port]
# Régénère la charte et les pages, puis sert le site et le simulateur. Aucune installation requise (Node + Python 3).
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${1:-8080}"
node design/build.mjs >/dev/null
node web/tools/build-pages.mjs >/dev/null
echo "BULLYLIST — prévisualisation"
echo "  Site        http://localhost:$PORT/"
echo "  Simulateur  http://localhost:$PORT/simulateur/"
echo "  Démo        http://localhost:$PORT/demo.html"
echo "Ctrl+C pour arrêter."
exec python3 -m http.server "$PORT" --directory web --bind 127.0.0.1
