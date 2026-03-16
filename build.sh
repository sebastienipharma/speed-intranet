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
