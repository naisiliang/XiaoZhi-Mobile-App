from pathlib import Path
import subprocess, tempfile, textwrap
root = Path(__file__).resolve().parents[1]
resolver = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiEndpointResolver.kt'
client = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiClient.kt'
if not resolver.exists():
    raise SystemExit('AiEndpointResolver.kt missing')
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'EndpointHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.AiEndpointResolver
        fun main() {
            check(AiEndpointResolver.normalizeBaseUrl("https://a.example/") == "https://a.example")
            check(AiEndpointResolver.normalizeBaseUrl("https://a.example/v1") == "https://a.example")
            check(AiEndpointResolver.normalizeBaseUrl("https://a.example/v1/chat/completions") == "https://a.example")
            check(AiEndpointResolver.chatUrl("https://a.example") == "https://a.example/v1/chat/completions")
            check(AiEndpointResolver.responsesUrl("https://a.example/v1") == "https://a.example/v1/responses")
            println("PASS: AI endpoint resolver")
        }
    '''), encoding='utf-8')
    jar = td / 'endpoint.jar'
    subprocess.run(['kotlinc', str(resolver), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
text = client.read_text('utf-8')
for value in ['testEndpoint', 'CHAT_COMPLETIONS', 'RESPONSES', 'latencyMs', '只回复：OK']:
    if value not in text:
        raise SystemExit('AI endpoint feature missing: ' + value)
if 'println(settings.apiKey)' in text or 'Log.' in text and 'apiKey' in text:
    raise SystemExit('API key logging detected')
print('PASS: v0.6 AI endpoint source')
