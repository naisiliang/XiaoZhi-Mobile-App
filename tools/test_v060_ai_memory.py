from pathlib import Path
import subprocess, tempfile, textwrap
root = Path(__file__).resolve().parents[1]
memory = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiConversationMemory.kt'
models = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AiModels.kt'
if not memory.exists():
    raise SystemExit('AiConversationMemory.kt missing')
with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / 'MemoryHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*
        fun main() {
            val m = AiConversationMemory(maxTurns = 8)
            m.startSession()
            for (i in 1..10) m.addTurn("u$i", "a$i")
            val all = m.messages()
            check(all.size == 16)
            check(all.first().content == "u3")
            check(all.last().content == "a10")
            m.clear()
            check(m.messages().isEmpty())
            println("PASS: AI conversation memory")
        }
    '''), encoding='utf-8')
    jar = td / 'memory.jar'
    subprocess.run(['kotlinc', str(models), str(memory), str(harness), '-include-runtime', '-d', str(jar)], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
