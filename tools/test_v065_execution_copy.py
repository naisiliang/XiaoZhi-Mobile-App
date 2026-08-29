from pathlib import Path
import os
import subprocess
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
sources = [
    root / "app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt",
    root / "app/src/main/java/com/lchuang/xiaozhimobile/CommandTransaction.kt",
    root / "app/src/main/java/com/lchuang/xiaozhimobile/ExecutionIntentFormatter.kt",
]

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    stubs = td / "MapPreferenceStub.kt"
    stubs.write_text("""
        package com.lchuang.xiaozhimobile
        enum class MapAppPreference { AUTO, AMAP, BAIDU }
    """, encoding="utf-8")
    harness = td / "ExecutionCopyHarness.kt"
    harness.write_text(textwrap.dedent("""
        import com.lchuang.xiaozhimobile.*

        fun main() {
            val formatter = ExecutionIntentFormatter()
            check(formatter.announcement(DeviceAction.OpenApp("微信")) == "打开微信正在执行")
            check(formatter.announcement(DeviceAction.GoHome("微信")) == "退出微信正在执行")
            check(formatter.announcement(DeviceAction.GoHome(null)) == "返回桌面正在执行")
            check(formatter.announcement(DeviceAction.SetMediaVolume(70)) == "调整媒体音量到百分之七十正在执行")
            check(formatter.announcement(DeviceAction.Navigate("泉水村", MapAppPreference.AUTO)) == "导航到泉水村正在执行")
            check(formatter.announcement(DeviceAction.SearchNearby("加油站", MapAppPreference.AUTO)) == "搜索附近加油站正在执行")

            val success = formatter.finalCopy(
                DeviceAction.SetMediaVolume(70),
                DeviceExecutionResult(true, "SET_VOLUME", "媒体音量已经调整到69%", "媒体音量69%"),
                "你有什么需求请说？"
            )
            check(success.successNotification == "✅ 已成功执行：媒体音量69%")
            check(success.finalSpoken == "媒体音量已经调整到69%")
            check(!success.successNotification!!.contains("70"))

            val failure = formatter.finalCopy(
                DeviceAction.OpenApp("微信"),
                DeviceExecutionResult(false, "OPEN_APP_FAILED", "没有成功打开微信", "启动微信失败", CommandFailureKind.EXECUTION_FAILED),
                "请继续说。"
            )
            check(failure.failureNotification == "❌ 执行失败：打开微信")
            check(failure.finalSpoken == "没有成功打开微信。请再试一次。")
            check(failure.successNotification == null)
            check(CommandTransaction("打开微信", "打开微信", DeviceAction.OpenApp("微信"), "打开微信正在执行").result == null)
            println("PASS: v0.6.5 execution copy")
        }
    """), encoding="utf-8")
    jar = td / "execution-copy.jar"
    compiler = os.environ.get("KOTLINC", "kotlinc")
    compiler_command = ["cmd", "/c", compiler] if compiler.lower().endswith((".bat", ".cmd")) else [compiler]
    subprocess.run([*compiler_command, *(str(source) for source in sources), str(stubs), str(harness), "-include-runtime", "-d", str(jar)], check=True)
    subprocess.run(["java", "-jar", str(jar)], check=True)
