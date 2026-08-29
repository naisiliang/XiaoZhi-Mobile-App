from pathlib import Path
import subprocess, tempfile, textwrap

root = Path(__file__).resolve().parents[1]
compiler = root / 'app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt'

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'Wake063Harness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*

        fun main() {
            // pinyin4j 2.5.1 can emit a BREVE (ă) for 3rd-tone a,
            // while sherpa tokens use the standard CARON (ǎ).
            val provider = object : PronunciationProvider {
                override fun syllables(ch: Char): List<String> = when (ch) {
                    '小' -> listOf("xiăo")  // buggy pinyin4j spelling seen in upstream issue #53
                    '白' -> listOf("bái")
                    else -> emptyList()
                }
            }
            val tokens = setOf("x", "iǎo", "b", "ái")
            val result = WakePhraseCompiler().compile("小白小白", tokens, provider)
            check(result is CompileResult.Success) { "expected success, got $result" }
            result as CompileResult.Success
            check(result.runtimeKeyword == "x iǎo b ái x iǎo b ái @小白小白") {
                "unexpected runtime keyword: ${result.runtimeKeyword}"
            }
            println("PASS: v0.6.3 normalizes pinyin4j tone marks and emits official ppinyin tokens")
        }
    '''), encoding='utf-8')
    jar = td / 'wake063.jar'
    subprocess.run(['kotlinc', str(compiler), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
