#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$ASSETS"

KWS_MODEL="sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
KWS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${KWS_MODEL}.tar.bz2"
if [[ ! -s "$ASSETS/$KWS_MODEL/encoder-epoch-13-avg-2-chunk-16-left-64.onnx" ]]; then
  echo "Downloading KWS model..."
  curl -fL --retry 5 --retry-delay 2 "$KWS_URL" -o "$TMP/kws.tar.bz2"
  tar -xjf "$TMP/kws.tar.bz2" -C "$TMP"
  mkdir -p "$ASSETS/$KWS_MODEL"
  cp "$TMP/$KWS_MODEL/encoder-epoch-13-avg-2-chunk-16-left-64.onnx" "$ASSETS/$KWS_MODEL/"
  cp "$TMP/$KWS_MODEL/decoder-epoch-13-avg-2-chunk-16-left-64.onnx" "$ASSETS/$KWS_MODEL/"
  cp "$TMP/$KWS_MODEL/joiner-epoch-13-avg-2-chunk-16-left-64.onnx" "$ASSETS/$KWS_MODEL/"
  cp "$TMP/$KWS_MODEL/tokens.txt" "$ASSETS/$KWS_MODEL/"
else
  echo "KWS model already present"
fi

ASR_MODEL="sherpa-onnx-paraformer-zh-small-2024-03-09"
ASR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${ASR_MODEL}.tar.bz2"
if [[ ! -s "$ASSETS/$ASR_MODEL/model.int8.onnx" ]]; then
  echo "Downloading local ASR model..."
  curl -fL --retry 5 --retry-delay 2 "$ASR_URL" -o "$TMP/asr.tar.bz2"
  tar -xjf "$TMP/asr.tar.bz2" -C "$TMP"
  mkdir -p "$ASSETS/$ASR_MODEL"
  cp "$TMP/$ASR_MODEL/model.int8.onnx" "$ASSETS/$ASR_MODEL/"
  cp "$TMP/$ASR_MODEL/tokens.txt" "$ASSETS/$ASR_MODEL/"
else
  echo "Local ASR model already present"
fi

echo "Offline KWS + ASR models installed into app assets"
