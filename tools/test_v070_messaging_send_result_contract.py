from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging/SendResultVerifier.kt"
COORDINATOR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging/MessagingCoordinator.kt"
STATE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging/MessagingState.kt"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    verifier = VERIFIER.read_text(encoding="utf-8")
    coordinator = COORDINATOR.read_text(encoding="utf-8")
    state = STATE.read_text(encoding="utf-8")

    for marker in (
        "enum class SendVerificationState",
        "SENT",
        "SEND_FAILED",
        "SEND_UNVERIFIED",
        "fun verify(",
        "inputNodes.size != 1",
        "MESSAGE_INPUT_NOT_CLEARED",
        "MESSAGE_OUTGOING_BUBBLE_MISSING",
        "node.text == expectedBody",
        "retryCount: Int = 0",
    ):
        require(marker in verifier, f"missing send proof contract: {marker}")

    for marker in (
        "fun verifySendResult(",
        "MessagingState.VERIFYING_SEND_RESULT",
        "SendVerificationState.SENT -> MessagingState.SENT",
        "SendVerificationState.SEND_FAILED -> MessagingState.SEND_FAILED",
        "SendVerificationState.SEND_UNVERIFIED -> MessagingState.SEND_UNVERIFIED",
        "activeSendRequest = null",
    ):
        require(marker in coordinator, f"missing terminal send wiring: {marker}")
    require("VERIFYING_SEND_RESULT" in state, "send verification state is missing")

    for forbidden in (
        "retrySend",
        "retryCount++",
        "performAction(",
        "dispatchGesture",
        "getBoundsInScreen(",
        "AccessibilityNodeInfo",
        "GenericAccessibilityExecutor",
        "ToolDispatcher",
        "setText(",
        "sendText(",
    ):
        require(forbidden not in verifier and forbidden not in coordinator,
                f"send verification must not retry or execute UI actions: {forbidden}")
    print("PASS: v0.7 send-result proof and zero-retry contract")


if __name__ == "__main__":
    main()
