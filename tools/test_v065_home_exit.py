from pathlib import Path
import os
import subprocess
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
exit_source = root / 'app/src/main/java/com/lchuang/xiaozhimobile/AppExitController.kt'
executor_source = root / 'app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt'
action_source = root / 'app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt'

assert exit_source.exists(), 'missing AppExitController.kt'
assert executor_source.exists(), 'missing DeviceActionExecutor.kt'

exit_text = exit_source.read_text(encoding='utf-8')
executor_text = executor_source.read_text(encoding='utf-8')

for token in ('Intent.ACTION_MAIN', 'Intent.CATEGORY_HOME', 'Intent.FLAG_ACTIVITY_NEW_TASK'):
    assert token in exit_text, f'Home Intent missing {token}'
for forbidden in ('forceStopPackage', 'killBackgroundProcesses', 'root', 'shell', 'Accessibility'):
    assert forbidden not in exit_text, f'unsafe exit mechanism present: {forbidden}'
assert 'catch (_: Throwable)' in exit_text
assert 'HomeResult(false, "GO_HOME_FAILED")' in exit_text

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    stubs = td / 'DeviceActionExecutorStubs.kt'
    stubs.write_text(textwrap.dedent('''
        package com.lchuang.xiaozhimobile

        enum class MapAppPreference { AUTO, AMAP, BAIDU, SYSTEM }

        object AppLauncher {
            enum class AppLaunchError {
                PACKAGE_NOT_VISIBLE, PACKAGE_NOT_INSTALLED, NO_LAUNCH_ACTIVITY, START_ACTIVITY_FAILED
            }

            sealed class AppLaunchResult {
                data class Success(val packageName: String, val label: String) : AppLaunchResult()
                data class Failure(val error: AppLaunchError, val detail: String = "") : AppLaunchResult()
            }
        }

        object MapController {
            data class MapActionResult(
                val success: Boolean,
                val usedMap: MapAppPreference = MapAppPreference.AUTO,
                val message: String,
                val code: String
            )
        }

        open class PhoneController {
            var appResult: AppLauncher.AppLaunchResult = AppLauncher.AppLaunchResult.Success("pkg", "微信")
            var mapResult = MapController.MapActionResult(true, message = "地图已经打开", code = "MAP_OK")
            var nearbyCallback: ((MapController.MapActionResult) -> Unit)? = null
            var browserSuccess = true
            var flashlightSuccess = true
            var volumeResult = MediaVolumeResult(80, true)
            val calls = mutableListOf<String>()

            data class MediaVolumeResult(
                val actualPercent: Int,
                val success: Boolean,
                val resultCode: String = if (success) "SUCCESS" else "EXECUTION_FAILED"
            )

            fun openApp(name: String): AppLauncher.AppLaunchResult { calls += "openApp:$name"; return appResult }
            fun openMap(preference: MapAppPreference): MapController.MapActionResult { calls += "openMap:$preference"; return mapResult }
            fun navigate(destination: String, preference: MapAppPreference): MapController.MapActionResult { calls += "navigate:$destination:$preference"; return mapResult }
            fun searchNearby(keyword: String, preference: MapAppPreference, callback: (MapController.MapActionResult) -> Unit) {
                calls += "searchNearby:$keyword:$preference"; nearbyCallback = callback
            }
            fun openBrowser(target: String): Boolean { calls += "openBrowser:$target"; return browserSuccess }
            fun mediaPlay() { calls += "mediaPlay" }
            fun mediaPause() { calls += "mediaPause" }
            fun mediaStop() { calls += "mediaStop" }
            fun mediaNext() { calls += "mediaNext" }
            fun mediaPrevious() { calls += "mediaPrevious" }
            fun setMediaVolumePercent(percent: Int): MediaVolumeResult { calls += "setVolume:$percent"; return volumeResult }
            fun volumeUpVerified(): MediaVolumeResult { calls += "volumeUp"; return volumeResult }
            fun volumeDownVerified(): MediaVolumeResult { calls += "volumeDown"; return volumeResult }
            fun setFlashlight(enabled: Boolean): Boolean { calls += "flashlight:$enabled"; return flashlightSuccess }
        }

        class AppExitController {
            data class HomeResult(val success: Boolean, val code: String)
            var result = HomeResult(true, "GO_HOME_OK")
            fun goHome(): HomeResult = result
        }
    '''), encoding='utf-8')
    harness = td / 'DeviceActionExecutorHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*

        fun main() {
            val phone = PhoneController()
            val home = AppExitController()
            val executor = DeviceActionExecutor(phone, home)
            fun run(action: DeviceAction): DeviceExecutionResult {
                var result: DeviceExecutionResult? = null
                executor.execute(action) { result = it }
                return checkNotNull(result) { "expected synchronous result for $action" }
            }

            check(run(DeviceAction.GoHome("微信")) == DeviceExecutionResult(true, "GO_HOME_OK", "微信已退出", "退出微信"))
            check(run(DeviceAction.GoHome(null)) == DeviceExecutionResult(true, "GO_HOME_OK", "已返回桌面", "返回桌面"))
            home.result = AppExitController.HomeResult(false, "GO_HOME_FAILED")
            val homeFailure = run(DeviceAction.GoHome("微信"))
            check(!homeFailure.success && homeFailure.code == "GO_HOME_FAILED")
            check(homeFailure.failureKind == CommandFailureKind.EXECUTION_FAILED)
            check(!homeFailure.spokenResult.contains("已退出"))

            phone.appResult = AppLauncher.AppLaunchResult.Failure(AppLauncher.AppLaunchError.PACKAGE_NOT_VISIBLE)
            check(run(DeviceAction.OpenApp("微信")).failureKind == CommandFailureKind.APP_NOT_FOUND)
            phone.appResult = AppLauncher.AppLaunchResult.Failure(AppLauncher.AppLaunchError.PACKAGE_NOT_INSTALLED)
            check(run(DeviceAction.OpenApp("微信")).failureKind == CommandFailureKind.APP_NOT_FOUND)
            phone.appResult = AppLauncher.AppLaunchResult.Failure(AppLauncher.AppLaunchError.NO_LAUNCH_ACTIVITY)
            check(run(DeviceAction.OpenApp("微信")).failureKind == CommandFailureKind.EXECUTION_FAILED)
            phone.appResult = AppLauncher.AppLaunchResult.Failure(AppLauncher.AppLaunchError.START_ACTIVITY_FAILED)
            check(run(DeviceAction.OpenApp("微信")).failureKind == CommandFailureKind.EXECUTION_FAILED)

            phone.volumeResult = PhoneController.MediaVolumeResult(63, true)
            val volume = run(DeviceAction.SetMediaVolume(70))
            check(volume.success && volume.spokenResult == "媒体音量已经调整到约63%")
            check(volume.notificationSummary == "媒体音量63%")

            phone.mapResult = MapController.MapActionResult(false, message = "导航没有成功打开", code = "NAVIGATION_FAILED")
            val navigation = run(DeviceAction.Navigate("广州南站", MapAppPreference.AUTO))
            check(!navigation.success && navigation.code == "NAVIGATION_FAILED")
            check(navigation.failureKind == CommandFailureKind.EXECUTION_FAILED)

            var nearbyResults = 0
            executor.execute(DeviceAction.SearchNearby("咖啡", MapAppPreference.AUTO)) { nearbyResults++ }
            check(nearbyResults == 0) { "nearby must wait for map callback" }
            checkNotNull(phone.nearbyCallback).invoke(MapController.MapActionResult(true, message = "已打开地图搜索附近的咖啡", code = "NEARBY_OK"))
            check(nearbyResults == 1) { "each map result must produce exactly one executor callback" }

            phone.flashlightSuccess = true
            check(run(DeviceAction.SetFlashlight(false)) == DeviceExecutionResult(true, "FLASHLIGHT_OFF", "手电筒已关闭", "关闭手电筒"))
            check(run(DeviceAction.MediaStop) == DeviceExecutionResult(true, "MEDIA_STOP", "已停止播放", "停止播放"))
            println("PASS: safe Home navigation and unified device action execution")
        }
    '''), encoding='utf-8')
    jar = td / 'home-exit.jar'
    compiler = os.environ.get('KOTLINC', 'kotlinc')
    compiler_command = ['cmd', '/c', compiler] if compiler.lower().endswith(('.bat', '.cmd')) else [compiler]
    subprocess.run([
        *compiler_command, str(action_source), str(executor_source), str(stubs), str(harness),
        '-include-runtime', '-d', str(jar)
    ], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
