from pathlib import Path
root = Path(__file__).resolve().parents[1]
path = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt'
if not path.exists():
    raise SystemExit('AiOrchestrator.kt missing')
text = path.read_text('utf-8')
for value in ['tool_calls', '"type"', '"tool_call"', '"reply"', 'AiConversationMemory']:
    if value not in text:
        raise SystemExit('orchestrator feature missing: ' + value)
for name in ['open_app','navigate','search_nearby','open_web','set_volume']:
    if name not in text:
        raise SystemExit('tool schema missing: ' + name)
print('PASS: v0.6 AI orchestrator source')
