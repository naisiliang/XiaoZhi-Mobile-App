from pathlib import Path

root = Path(__file__).resolve().parents[1]
wake = (root/'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text(encoding='utf-8')
fetch = (root/'scripts/fetch-kws-model.sh').read_text(encoding='utf-8')
main = (root/'app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt').read_text(encoding='utf-8')

checks = {
    'uses sherpa offline recognizer': 'OfflineRecognizer' in wake and 'OfflineParaformerModelConfig' in wake,
    'does not use Android SpeechRecognizer': 'SpeechRecognizer' not in wake and 'RecognizerIntent' not in wake,
    'has local command capture': 'captureCommandAudio' in wake,
    'has local asr decode': 'decodeLocalCommand' in wake,
    'downloads paraformer small': 'sherpa-onnx-paraformer-zh-small-2024-03-09' in fetch,
    'ui says local command asr': 'sherpa-onnx' in main and '语音指令识别' in main,
}
failed = [k for k,v in checks.items() if not v]
if failed:
    print('FAIL:', ', '.join(failed))
    raise SystemExit(1)
print('PASS: local ASR source requirements present')
