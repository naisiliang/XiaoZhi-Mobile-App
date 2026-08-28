#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
MODEL="sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${MODEL}.tar.bz2"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$ASSETS/$MODEL"
if [[ -s "$ASSETS/$MODEL/encoder-epoch-13-avg-2-chunk-16-left-64.onnx" ]]; then
  echo "KWS model already present"
  exit 0
fi

echo "Downloading KWS model..."
curl -fL --retry 4 --retry-delay 2 "$URL" -o "$TMP/model.tar.bz2"
tar -xjf "$TMP/model.tar.bz2" -C "$TMP"
SRC="$TMP/$MODEL"
cp "$SRC/encoder-epoch-13-avg-2-chunk-16-left-64.onnx" "$ASSETS/$MODEL/"
cp "$SRC/decoder-epoch-13-avg-2-chunk-16-left-64.onnx" "$ASSETS/$MODEL/"
cp "$SRC/joiner-epoch-13-avg-2-chunk-16-left-64.onnx" "$ASSETS/$MODEL/"
cp "$SRC/tokens.txt" "$ASSETS/$MODEL/"
echo "KWS model installed into app assets"
