The KWS model directory is downloaded by scripts/fetch-kws-model.sh before building.
Expected assets directory:
sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/
  encoder-epoch-13-avg-2-chunk-16-left-64.onnx
  decoder-epoch-13-avg-2-chunk-16-left-64.onnx
  joiner-epoch-13-avg-2-chunk-16-left-64.onnx
  tokens.txt

The app uses app/src/main/assets/keywords.txt for the phrase 小智小智.
