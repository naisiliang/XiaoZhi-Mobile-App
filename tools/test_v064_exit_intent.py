from pathlib import Path
import subprocess, tempfile, textwrap

root = Path(__file__).resolve().parents[1]
detector = root / 'app/src/main/java/com/lchuang/xiaozhimobile/ConversationExitDetector.kt'
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'ExitHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*
        fun main() {
            val d = ConversationExitDetector()
            listOf(
                "退出", "退出吧", "退下", "你退下吧", "没什么事了", "没事了",
                "不用了", "先这样吧", "就这样吧", "结束吧", "结束对话",
                "你先休息吧", "可以休息了", "再见", "拜拜", "今天先到这里", "暂时没别的事"
            ).forEach { check(d.classify(it) == ExitDecision.EXIT) { "expected EXIT: $it -> ${d.classify(it)}" } }
            listOf(
                "退出微信", "退出登录", "退出当前账号", "退出账户", "退出密码", "退出界面",
                "怎么退出这个页面", "帮我关闭高德地图", "关闭微信"
            ).forEach { check(d.classify(it) == ExitDecision.CONTINUE) { "expected CONTINUE: $it -> ${d.classify(it)}" } }
            listOf("好了今天就这样", "你可以先忙你的了").forEach {
                check(d.classify(it) == ExitDecision.AMBIGUOUS) { "expected AMBIGUOUS: $it -> ${d.classify(it)}" }
            }
            check(d.classify("打开微信") == ExitDecision.CONTINUE)
            println("PASS: intelligent local exit classification")
        }
    '''), encoding='utf-8')
    jar = td / 'exit.jar'
    subprocess.run(['kotlinc', str(detector), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
source = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt').read_text(encoding='utf-8')
assert 'fun classifyExitIntent(' in source
method = source.split('fun classifyExitIntent(', 1)[1]
method = method.split('\n    private fun', 1)[0]
assert 'emptyList()' in method, 'exit classifier must not send tool definitions'
assert 'EXIT' in method and 'CONTINUE' in method
assert 'toolDefinitions' not in method
assert 'ExitDecision.EXIT' in method and 'ExitDecision.CONTINUE' in method
print('PASS: AI exit classifier is no-tools and binary')
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text(encoding='utf-8')
for token in ['ConversationExitDetector', 'ExitDecision.EXIT', 'ExitDecision.CONTINUE', 'ExitDecision.AMBIGUOUS', 'classifyExitIntent', 'requestConversationExit']:
    assert token in wake, f'WakeService missing exit integration: {token}'
assert 'containsConversationExit' not in wake, 'old flat exit list must be removed'
print('PASS: WakeService integrates local + AI exit classification')
