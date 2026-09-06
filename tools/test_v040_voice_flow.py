from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
wake = (root/'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')

checks = [
    ('overlay controller field', 'AssistantOverlayController' in wake and 'overlay' in wake),
    ('overlay shown on wake', 'overlay.show()' in wake),
    ('overlay listening state', ('正在听你说' in wake or 'ConversationState.LISTENING' in wake) and 'overlay.update' in wake),
    ('overlay recognizing state', 'ConversationState.RECOGNIZING' in wake),
    ('overlay heard raw text', '我听到：$rawText' in wake or '我听到：$text' in wake),
    ('overlay executing state', '正在执行' in wake),
    ('overlay thinking state', '正在思考' in wake),
    ('waveform follows rms', 'overlay.updateAudioLevel' in wake and 'rms' in wake),
    # The v0.7 shared assistant pipeline trims the source-aware input into
    # `text` before normalization; the v0.4 implementation normalized
    # `rawText` directly. Both forms preserve the required behavior.
    ('spoken text normalized', (
        'VoiceCommandNormalizer.normalize(rawText)' in wake or
        'VoiceCommandNormalizer.normalize(text)' in wake
    )),
    ('router plans normalized command', 'router.plan(normalized)' in wake),
    ('overlay hidden when session ends', 'overlay.hide()' in wake),
    ('overlay released on destroy', 'overlay.release()' in wake),
]

# Enforce routing order in the voice/assistant pipeline: normalize before
# router. The v0.7 shared entry point is processAssistantInput; keep the
# legacy processUtterance fallback for older source snapshots.
try:
    pipeline_start = wake.index('private fun processAssistantInput(')
except ValueError:
    pipeline_start = wake.index('private fun processUtterance(')

pipeline = wake[pipeline_start:]
try:
    normalize_tokens = (
        'VoiceCommandNormalizer.normalize(rawText)',
        'VoiceCommandNormalizer.normalize(text)',
    )
    normalize_i = min(pipeline.index(token) for token in normalize_tokens if token in pipeline)
    router_i = pipeline.index('router.plan(normalized)')
    checks.append(('normalization occurs before router', normalize_i < router_i))
except (ValueError, TypeError):
    checks.append(('normalization occurs before router', False))

failed=[]
for name, ok in checks:
    print(('PASS' if ok else 'FAIL') + ': ' + name)
    if not ok:
        failed.append(name)
if failed:
    sys.exit(1)
