from pathlib import Path
import subprocess
import tempfile
import textwrap

root = Path(__file__).resolve().parents[1]
ai = root / "app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt"
src = ai.read_text(encoding="utf-8")

assert "$identity当前这一次对话" not in src, (
    "unsafe Kotlin string interpolation remains: $identity当前这一次对话"
)
assert "${identity}当前这一次对话" in src, (
    "expected braced identity interpolation is missing"
)

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    kt = td / "InterpolationHarness.kt"
    kt.write_text(textwrap.dedent("""
        fun main() {
            val identity = "小智"
            val instruction = "你只判断用户是否明确想结束与${identity}当前这一次对话。"
            check(instruction == "你只判断用户是否明确想结束与小智当前这一次对话。")
            println("PASS: braced Chinese Kotlin interpolation compiles")
        }
    """), encoding="utf-8")
    jar = td / "test.jar"
    subprocess.run(
        ["kotlinc", str(kt), "-include-runtime", "-d", str(jar)],
        check=True
    )
    subprocess.run(["java", "-jar", str(jar)], check=True)

print("PASS: AiOrchestrator exit classifier uses safe Kotlin interpolation")
