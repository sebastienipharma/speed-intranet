#!/usr/bin/env sh
# build.sh — Construit l'exécutable autonome speed-intranet
#
# Prérequis : Python 3.7+ et pip doivent être installés sur la machine de build.
# L'exécutable produit ne nécessite PAS Python sur les machines cibles.
#
# Usage :
#   chmod +x build.sh
#   ./build.sh
#
# L'exécutable est produit dans le dossier dist/ avec un nom incluant la plateforme.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Détection de la plateforme
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$OS" in
  linux*)  PLATFORM="linux" ;;
  darwin*) PLATFORM="macos" ;;
  msys*|cygwin*|mingw*) PLATFORM="windows" ;;
  *)       PLATFORM="$OS" ;;
esac

BINARY_NAME="speedtest-${PLATFORM}-${ARCH}"
if [ "$PLATFORM" = "windows" ]; then
  BINARY_NAME="${BINARY_NAME}.exe"
fi

PROGRAM_FILE="speedtest.py"
VERSION_STATE_FILE=".version_state.json"
VERSION_MODULE_FILE="_version.py"

echo "Mise à jour de version automatique..."
python3 - "$PROGRAM_FILE" "$VERSION_STATE_FILE" "$VERSION_MODULE_FILE" <<'PY'
import hashlib
import json
import os
import sys

program_file, state_file, version_module_file = sys.argv[1], sys.argv[2], sys.argv[3]

with open(program_file, "rb") as f:
  current_hash = hashlib.sha256(f.read()).hexdigest()

state = {"version": "1.00", "program_hash": ""}
if os.path.exists(state_file):
  with open(state_file, "r", encoding="utf-8") as f:
    loaded = json.load(f)
    if isinstance(loaded, dict):
      state.update(loaded)

version = float(state.get("version", "1.00"))
previous_hash = state.get("program_hash", "")

if previous_hash and previous_hash != current_hash:
  version += 0.01

version_str = f"{version:.2f}"
new_state = {"version": version_str, "program_hash": current_hash}

with open(state_file, "w", encoding="utf-8") as f:
  json.dump(new_state, f, indent=2)
  f.write("\n")

with open(version_module_file, "w", encoding="utf-8") as f:
  f.write("# Fichier généré automatiquement par build.sh\n")
  f.write(f'VERSION = "{version_str}"\n')

print(f"Version active : {version_str}")
PY

echo "=== speed-intranet build ==="
echo "Plateforme : $PLATFORM ($ARCH)"
echo "Binaire    : dist/$BINARY_NAME"
echo ""

# Installation de PyInstaller si absent
if ! python3 -m PyInstaller --version > /dev/null 2>&1; then
  echo "Installation de PyInstaller..."
  pip3 install --quiet pyinstaller
fi

# Construction
python3 -m PyInstaller \
  --onefile \
  --name "$BINARY_NAME" \
  --distpath dist \
  --workpath /tmp/pyinstaller-build \
  --specpath /tmp/pyinstaller-build \
  --clean \
  --strip \
  speedtest.py

echo ""
echo "=== Terminé ==="
echo "Exécutable : dist/$BINARY_NAME"
ls -lh "dist/$BINARY_NAME"
