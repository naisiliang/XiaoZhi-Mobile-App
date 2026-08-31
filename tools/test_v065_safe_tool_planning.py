from pathlib import Path
import os
import re
import subprocess
import tempfile
import textwrap


root = Path(__file__).resolve().parents[1]
safe_source = root / 'app/src/main/java/com/lchuang/xiaozhimobile/SafeToolExecutor.kt'
action_source = root / 'app/src/main/java/com/lchuang/xiaozhimobile/DeviceAction.kt'
executor_source = root / 'app/src/main/java/com/lchuang/xiaozhimobile/DeviceActionExecutor.kt'

for source in (safe_source, action_source, executor_source):
    assert source.exists(), f'missing source: {source.name}'

safe_text = safe_source.read_text(encoding='utf-8')
allowlist = [
    'open_app', 'navigate', 'search_nearby', 'open_web', 'media_play', 'media_pause',
    'media_next', 'media_previous', 'volume_up', 'volume_down', 'set_volume',
    'flashlight_on', 'flashlight_off',
]
plan_block = re.search(
    r'fun plan\(call: AiToolCall\): SafeToolPlan = when \(call\.tool\) \{(.*?)\n    \}\n\n    private fun allowed',
    safe_text,
    re.S,
)
assert plan_block, 'SafeToolPlan.plan() is missing'
planned_tools = re.findall(r'^        "([^"]+)" ->', plan_block.group(1), re.M)
assert planned_tools == allowlist, f'allowlist changed: {planned_tools}'
assert 'class SafeToolExecutor(private val deviceActionExecutor: DeviceActionExecutor)' in safe_text
execute_block = safe_text[safe_text.index('fun execute(call: AiToolCall'):safe_text.index('fun plan(call: AiToolCall')]
assert 'deviceActionExecutor.execute' in execute_block, 'compatibility execution must delegate to DeviceActionExecutor'
assert 'phone.' not in execute_block, 'SafeToolExecutor.execute must not directly call PhoneController'
for forbidden in ('go_home', 'delete_all_files', 'send_message', 'transfer_money', 'install_app', 'shell_command'):
    assert f'"{forbidden}"' not in safe_text, f'authority expanded with: {forbidden}'

with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    uri_stub = td / 'Uri.kt'
    uri_stub.write_text(textwrap.dedent('''
        package android.net

        class Uri private constructor(private val value: String) {
            val scheme: String?
                get() = value.substringBefore(':', "").takeIf { it.isNotEmpty() }

            companion object {
                fun parse(value: String): Uri = Uri(value)
            }
        }
    '''), encoding='utf-8')
    stubs = td / 'SafeToolStubs.kt'
    stubs.write_text(textwrap.dedent('''
        package com.lchuang.xiaozhimobile

        enum class MapAppPreference { AUTO, AMAP, BAIDU, SYSTEM }

        data class AiToolCall(val tool: String, val args: Map<String, Any?> = emptyMap())
        data class ToolExecutionResult(val success: Boolean, val message: String, val code: String)

        class AppExitController {
            data class HomeResult(val success: Boolean = true, val code: String = "GO_HOME_OK")
            fun goHome() = HomeResult()
        }

        object AppLauncher {
            enum class AppLaunchError { PACKAGE_NOT_VISIBLE, PACKAGE_NOT_INSTALLED, NO_LAUNCH_ACTIVITY, START_ACTIVITY_FAILED }

            sealed class AppLaunchResult {
                data class Success(val label: String) : AppLaunchResult()
                data class Failure(val error: AppLaunchError) : AppLaunchResult()
            }
        }

        @Suppress("UNUSED_PARAMETER")
        class PhoneController {
            data class MediaVolumeResult(val actualPercent: Int, val success: Boolean)
            var sideEffects = 0
            fun openApp(name: String): AppLauncher.AppLaunchResult { sideEffects++; return AppLauncher.AppLaunchResult.Success(name) }
            fun navigate(destination: String, preference: MapAppPreference) = MapController.MapActionResult()
            fun searchNearby(keyword: String, preference: MapAppPreference, callback: (MapController.MapActionResult) -> Unit) { sideEffects++; callback(MapController.MapActionResult()) }
            fun openBrowser(target: String): Boolean { sideEffects++; return true }
            fun mediaPlay() { sideEffects++ }
            fun mediaPause() { sideEffects++ }
            fun mediaNext() { sideEffects++ }
            fun mediaPrevious() { sideEffects++ }
            fun volumeUpVerified() = MediaVolumeResult(60, true).also { sideEffects++ }
            fun volumeDownVerified() = MediaVolumeResult(40, true).also { sideEffects++ }
            fun setMediaVolumePercent(percent: Int) = MediaVolumeResult(percent, true).also { sideEffects++ }
            fun setFlashlight(enabled: Boolean): Boolean { sideEffects++; return true }
        }

        object MapController {
            data class MapActionResult(val success: Boolean = true, val message: String = "ok", val code: String = "MAP_OK")
        }
    '''), encoding='utf-8')
    harness = td / 'SafeToolPlanningHarness.kt'
    harness.write_text(textwrap.dedent('''
        import com.lchuang.xiaozhimobile.*

        fun main() {
            val phone = PhoneController()
            val executor = SafeToolExecutor(DeviceActionExecutor(phone, AppExitController()))
            fun planned(call: AiToolCall, expected: DeviceAction) {
                check(executor.plan(call) == SafeToolPlan.Allowed(expected)) { "$call did not produce $expected" }
            }
            fun rejected(call: AiToolCall, code: String) {
                check(executor.plan(call) == SafeToolPlan.Rejected(ToolExecutionResult(false, if (code == "REJECTED_SCHEME") "不支持该链接类型" else if (code == "REJECTED_NOT_ALLOWED") "该操作不在安全工具白名单中" else "指令参数不完整", code))) { "$call did not reject as $code" }
            }

            planned(AiToolCall("open_app", mapOf("name" to "微信")), DeviceAction.OpenApp("微信"))
            planned(AiToolCall("navigate", mapOf("destination" to "广州南站")), DeviceAction.Navigate("广州南站", MapAppPreference.AUTO))
            planned(AiToolCall("search_nearby", mapOf("keyword" to "咖啡", "mapApp" to "高德")), DeviceAction.SearchNearby("咖啡", MapAppPreference.AMAP))
            planned(AiToolCall("open_web", mapOf("query_or_url" to "https://example.com")), DeviceAction.OpenWeb("https://example.com"))
            planned(AiToolCall("media_play"), DeviceAction.MediaPlay)
            planned(AiToolCall("media_pause"), DeviceAction.MediaPause)
            planned(AiToolCall("media_next"), DeviceAction.MediaNext)
            planned(AiToolCall("media_previous"), DeviceAction.MediaPrevious)
            planned(AiToolCall("volume_up"), DeviceAction.MediaVolumeUp)
            planned(AiToolCall("volume_down"), DeviceAction.MediaVolumeDown)
            planned(AiToolCall("set_volume", mapOf("percent" to 70)), DeviceAction.SetMediaVolume(70))
            planned(AiToolCall("flashlight_on"), DeviceAction.SetFlashlight(true))
            planned(AiToolCall("flashlight_off"), DeviceAction.SetFlashlight(false))
            rejected(AiToolCall("open_web", mapOf("query_or_url" to "javascript:alert(1)")), "REJECTED_SCHEME")
            rejected(AiToolCall("open_web", mapOf("query_or_url" to "ftp://example.com")), "REJECTED_SCHEME")
            rejected(AiToolCall("set_volume", mapOf("percent" to 101)), "INVALID_ARGS_set_volume")
            rejected(AiToolCall("open_app", mapOf("name" to "com.evil.package")), "INVALID_ARGS_open_app")
            rejected(AiToolCall("unknown_tool"), "REJECTED_NOT_ALLOWED")
            check(phone.sideEffects == 0) { "planning must not execute device commands" }

            var execution: ToolExecutionResult? = null
            executor.execute(AiToolCall("flashlight_off")) { execution = it }
            check(execution == ToolExecutionResult(true, "手电筒已关闭", "FLASHLIGHT_OFF"))
            check(phone.sideEffects == 1) { "execute must retain the existing device side effect" }
            println("PASS: v0.6.5 safe tool planning")
        }
    '''), encoding='utf-8')
    jar = td / 'safe-tool-planning.jar'
    compiler = os.environ.get('KOTLINC', 'kotlinc')
    compiler_command = ['cmd', '/c', compiler] if compiler.lower().endswith(('.bat', '.cmd')) else [compiler]
    subprocess.run([
        *compiler_command, str(uri_stub), str(action_source), str(executor_source), str(safe_source), str(stubs), str(harness),
        '-include-runtime', '-d', str(jar)
    ], check=True)
    subprocess.run(['java', '-jar', str(jar)], check=True)
