from pathlib import Path
import subprocess, tempfile, textwrap

root = Path(__file__).resolve().parents[1]
compiler = root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt'
manager = root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt'
wake = (root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt').read_text('utf-8')
if not compiler.exists() or not manager.exists():
    raise SystemExit('wake phrase classes missing')
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'WakeHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*
        fun main() {
            val provider = object : PronunciationProvider {
                override fun syllables(ch: Char): List<String> = mapOf(
                    '小' to listOf("xiǎo"), '智' to listOf("zhì"),
                    '白' to listOf("bái"), '在' to listOf("zài"), '吗' to listOf("ma")
                )[ch] ?: emptyList()
            }
            val tokens = setOf("x", "iǎo", "zh", "ì", "b", "ái", "z", "ài", "m", "a")
            val c = WakePhraseCompiler()
            val a = c.compile("小智小智", tokens, provider) as CompileResult.Success
            check(a.runtimeKeyword.endsWith("@小智小智"))
            val b = c.compile("小白小白", tokens, provider) as CompileResult.Success
            check(b.runtimeKeyword.contains("b ái"))
            check(c.compile("白", tokens, provider) is CompileResult.Failure)
            println("PASS: wake phrase compiler")
        }
    '''), encoding='utf-8')
    jar = td / 'wake.jar'
    subprocess.run(['kotlinc', str(compiler), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
for value in ['createStream(', 'settings.wakePhrase', 'activePhrase']:
    if value not in wake and value not in manager.read_text('utf-8'):
        raise SystemExit('runtime KWS integration missing: ' + value)
print('PASS: v0.6 runtime wake phrase source')
