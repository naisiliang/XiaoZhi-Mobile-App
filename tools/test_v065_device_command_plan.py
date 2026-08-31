from pathlib import Path
import os
import subprocess
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
sources = [
    root / 'app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt',
    root / 'app/src/main/java/com/lchuang/xiaozhimobile/VolumeCommandParser.kt',
    root / 'app/src/main/java/com/lchuang/xiaozhimobile/VoiceCommandNormalizer.kt',
    root / 'app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt',
]

for source in sources:
    assert source.exists(), f'missing planner source: {source.name}'

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    stubs = td / 'PhoneControllerStubs.kt'
    stubs.write_text(textwrap.dedent('''
        package com.lchuang.xiaozhimobile

        enum class MapAppPreference { AUTO, AMAP, BAIDU }

        data class MapResult(val success: Boolean = true, val message: String = "ok")

        object AppLauncher {
            enum class AppLaunchError {
                PACKAGE_NOT_VISIBLE, PACKAGE_NOT_INSTALLED, NO_LAUNCH_ACTIVITY, START_ACTIVITY_FAILED
            }

            sealed class AppLaunchResult {
                data class Success(val label: String) : AppLaunchResult()
                data class Failure(val error: AppLaunchError) : AppLaunchResult()
            }
        }

        class PhoneController {
            data class MediaVolumeResult(val success: Boolean = true, val actualPercent: Int = 50)
            var sideEffects = 0
            val flashlightValues = mutableListOf<Boolean>()
            fun setMediaVolumePercent(percent: Int): MediaVolumeResult { sideEffects++; return MediaVolumeResult(actualPercent = percent) }
            fun volumeUpVerified(): MediaVolumeResult { sideEffects++; return MediaVolumeResult() }
            fun volumeDownVerified(): MediaVolumeResult { sideEffects++; return MediaVolumeResult() }
            fun mediaPlay() { sideEffects++ }
            fun mediaPause() { sideEffects++ }
            fun mediaStop() { sideEffects++ }
            fun mediaNext() { sideEffects++ }
            fun mediaPrevious() { sideEffects++ }
            fun setFlashlight(enabled: Boolean): Boolean { sideEffects++; flashlightValues += enabled; return true }
            fun openApp(name: String): AppLauncher.AppLaunchResult { sideEffects++; return AppLauncher.AppLaunchResult.Success(name) }
            fun openMap(preference: MapAppPreference): MapResult { sideEffects++; return MapResult() }
            fun searchNearby(keyword: String, preference: MapAppPreference, onComplete: () -> Unit) { sideEffects++; onComplete() }
            fun navigate(destination: String, preference: MapAppPreference): MapResult { sideEffects++; return MapResult() }
            fun openBrowser(target: String): Boolean { sideEffects++; return true }
        }
    '''), encoding='utf-8')
    harness = td / 'DeviceCommandPlanHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*

        fun main() {
            val phone = PhoneController()
            val router = CommandRouter(phone)
            val cases = listOf(
                "退出微信" to DeviceAction.GoHome("微信"),
                "关闭微信" to DeviceAction.GoHome("微信"),
                "离开微信" to DeviceAction.GoHome("微信"),
                "退出抖音" to DeviceAction.GoHome("抖音"),
                "退出淘宝" to DeviceAction.GoHome("淘宝"),
                "退出浏览器" to DeviceAction.GoHome("浏览器"),
                "关闭抖音" to DeviceAction.GoHome("抖音"),
                "离开抖音" to DeviceAction.GoHome("抖音"),
                "把微信退了" to DeviceAction.GoHome("微信"),
                "退一下微信" to DeviceAction.GoHome("微信"),
                "微信先关掉" to DeviceAction.GoHome("微信"),
                "回到桌面" to DeviceAction.GoHome(null),
                "回桌面" to DeviceAction.GoHome(null),
                "打开微信" to DeviceAction.OpenApp("微信"),
                "音量70" to DeviceAction.SetMediaVolume(70),
                "音量大一点" to DeviceAction.MediaVolumeUp,
                "打开手电筒" to DeviceAction.SetFlashlight(true),
                "关闭手电筒" to DeviceAction.SetFlashlight(false),
                "导航到广州南站" to DeviceAction.Navigate("广州南站", MapAppPreference.AUTO),
            )
            cases.forEach { (raw, expected) ->
                val plan = router.plan(raw)
                check(plan == DeviceCommandPlan.Planned(expected, VoiceCommandNormalizer.normalize(raw))) {
                    "$raw -> $plan, expected $expected"
                }
            }
            check(router.plan("退出") == DeviceCommandPlan.Unhandled)
            check(router.plan("退出登录") == DeviceCommandPlan.Unhandled)
            check(router.plan("关闭这个页面") == DeviceCommandPlan.Unhandled)
            check(phone.sideEffects == 0) { "plan must not execute device commands" }
            check(router.handle("关闭手电筒") == CommandRouter.Result(true, "手电筒已关闭", true))
            check(phone.flashlightValues == listOf(false)) { "handle must turn the flashlight off" }
            check(DeviceExecutionResult(true, "ok", "已完成", "已完成").failureKind == null)
            check(DeviceExecutionResult(false, "no_speech", "", "", CommandFailureKind.NO_SPEECH).failureKind == CommandFailureKind.NO_SPEECH)
            println("PASS: structured local device command plan")
        }
    '''), encoding='utf-8')
    jar = td / 'device-command-plan.jar'
    compiler = os.environ.get('KOTLINC', 'kotlinc')
    compiler_command = ['cmd', '/c', compiler] if compiler.lower().endswith(('.bat', '.cmd')) else [compiler]
    subprocess.run([
        *compiler_command, *(str(source) for source in sources), str(stubs), str(harness),
        '-include-runtime', '-d', str(jar)
    ], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
