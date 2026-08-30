from pathlib import Path
import os
import subprocess
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
notifier_source = root / "app/src/main/java/com/lchuang/xiaozhimobile/CommandResultNotifier.kt"

assert notifier_source.exists(), "missing CommandResultNotifier.kt"
source = notifier_source.read_text(encoding="utf-8")
assert "import android." not in source, "CommandResultNotifier must remain Android-independent"

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    harness = td / "CommandResultNotifierHarness.kt"
    harness.write_text(
        textwrap.dedent(
            """
            import com.lchuang.xiaozhimobile.CommandResultNotifier

            class FakeClock(var nowMs: Long = 0L) {
                fun now(): Long = nowMs
                fun advance(deltaMs: Long) {
                    nowMs += deltaMs
                }
            }

            fun main() {
                val published = mutableListOf<String>()
                val clock = FakeClock(1_000L)
                val notifier = CommandResultNotifier(
                    publish = { published += it },
                    clockMs = { clock.now() },
                    holdMs = 4_000L
                )

                notifier.running("打开微信正在执行")
                check(published == listOf("打开微信正在执行")) {
                    "running should publish immediately without retention: $published"
                }
                check(notifier.retainedText() == null) {
                    "running must not retain transient execution text"
                }

                notifier.success("✅ 已成功执行：打开微信")
                check(
                    published == listOf(
                        "打开微信正在执行",
                        "✅ 已成功执行：打开微信"
                    )
                ) {
                    "success should publish after running: $published"
                }
                check(notifier.retainedText() == "✅ 已成功执行：打开微信") {
                    "success text should be retained immediately"
                }

                notifier.publishTransient("连续会话中...")
                check(published.last() == "✅ 已成功执行：打开微信") {
                    "active retention must suppress transient overwrite: $published"
                }

                clock.advance(3_999L)
                check(notifier.retainedText() == "✅ 已成功执行：打开微信") {
                    "retained success must survive through t+3999"
                }
                notifier.publishTransient("连续会话中...")
                check(published.last() == "✅ 已成功执行：打开微信") {
                    "t+3999 should still republish retained success: $published"
                }

                clock.advance(1L)
                check(notifier.retainedText() == null) {
                    "retained success must expire exactly at t+4000"
                }
                notifier.publishTransient("连续会话中...")
                check(published.last() == "连续会话中...") {
                    "expired retention must stop suppressing transient text: $published"
                }

                notifier.failure("❌ 执行失败：打开微信")
                check(published.last() == "❌ 执行失败：打开微信") {
                    "failure should publish its exact text"
                }
                check(notifier.retainedText() == "❌ 执行失败：打开微信") {
                    "failure text should also be retained"
                }
                notifier.clearRetention()
                check(notifier.retainedText() == null) {
                    "clearRetention must remove retained text immediately"
                }
                notifier.publishTransient("全离线语音已开启...")
                check(published.last() == "全离线语音已开启...") {
                    "cleared retention must allow wake-idle text to win immediately: $published"
                }

                println("PASS: retained command-result notifier behavior")
            }
            """
        ),
        encoding="utf-8",
    )
    jar = td / "command-result-notifier.jar"
    compiler = os.environ.get("KOTLINC", "kotlinc")
    compiler_command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
    kotlin_home = os.environ.get("KOTLIN_HOME")
    kotlin_runtime_cp = []
    compiler_args = [*compiler_command, str(notifier_source), str(harness)]
    if kotlin_home:
        lib_dir = Path(kotlin_home) / "lib"
        kotlin_runtime_cp = [
            str(lib_dir / "kotlin-stdlib.jar"),
            str(lib_dir / "kotlin-stdlib-jdk7.jar"),
            str(lib_dir / "kotlin-stdlib-jdk8.jar"),
        ]
        for runtime_jar in kotlin_runtime_cp:
            assert Path(runtime_jar).exists(), f"missing Kotlin runtime jar: {runtime_jar}"
        compiler_args.extend(["-no-stdlib", "-no-reflect", "-cp", os.pathsep.join(kotlin_runtime_cp)])
    else:
        compiler_args.append("-include-runtime")
    compiler_args.extend(["-d", str(jar)])
    subprocess.run(
        compiler_args,
        cwd=root,
        check=True,
    )
    if kotlin_runtime_cp:
        subprocess.run(
            ["java", "-cp", os.pathsep.join([str(jar), *kotlin_runtime_cp]), "CommandResultNotifierHarnessKt"],
            cwd=root,
            check=True,
        )
    else:
        subprocess.run(["java", "-jar", str(jar)], cwd=root, check=True)
