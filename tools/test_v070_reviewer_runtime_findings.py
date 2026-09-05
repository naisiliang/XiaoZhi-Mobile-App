from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt").read_text("utf-8")
WAKE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt").read_text("utf-8")
OVERLAY = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt").read_text("utf-8")
PROVIDER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/AssistantStateStoreProvider.kt").read_text("utf-8")
DISPATCHER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/tools/ToolDispatcher.kt").read_text("utf-8")


def function_body(source, name):
    match = re.search(rf"(?:private |public |internal |protected )?fun {re.escape(name)}\b[^{{]*\{{", source)
    assert match, f"missing function {name}"
    opening = source.find("{", match.start())
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
    raise AssertionError(f"unbalanced function {name}")


main_create = function_body(MAIN, "onCreate")
main_destroy = function_body(MAIN, "onDestroy")
wake_create = function_body(WAKE, "onCreate")
tool_branch = function_body(WAKE, "processNonExitUtterance")
executor_factory = function_body(WAKE, "createAiToolExecutors")

assert "private val store = AssistantStateStore()" in PROVIDER
assert "AssistantStateStoreProvider.instance()" in main_create
assert "stateStore.addObserver(stateObserver)" in main_create
assert "removeStateObserver?.invoke()" in main_destroy
assert "stateStore = AssistantStateStore(" not in MAIN
assert "AssistantStateStoreProvider.instance()" in wake_create
assert "AssistantOverlayController(this, assistantStateStore)" in wake_create
assert "stateStore: AssistantStateStore = AssistantStateStoreProvider.instance()" in OVERLAY
assert "stateStore.addObserver(stateObserver)" in OVERLAY
assert "stateStore.removeObserver(stateObserver)" in OVERLAY

dispatch_at = tool_branch.find("toolDispatcher.dispatch(")
invocation_at = tool_branch.find("ToolInvocation(outcome.call.tool, outcome.call.args)")
handler_at = tool_branch.find("handleAiToolResult(rawText, normalized, heard, result)")
assert 0 <= dispatch_at < invocation_at < handler_at
assert "safeToolExecutor.plan" not in tool_branch
assert "safeToolExecutor.execute" not in tool_branch
assert "safeToolExecutor.execute" in executor_factory
assert "PermissionBroker" in wake_create
assert "CentralSafetyPolicyEngine" in wake_create

assert "permissionBroker.check(invocation)" in DISPATCHER
assert "policyEvaluator(invocation)" in DISPATCHER
assert "ToolDecision.CONFIRM" in DISPATCHER
assert "resultExecutors[name]" in DISPATCHER
assert "onResult" in DISPATCHER

print("PASS: v0.7 reviewer runtime findings are wired through shared state and production safety dispatch")
