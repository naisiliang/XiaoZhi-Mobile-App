from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COORDINATOR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging/MessagingCoordinator.kt"
STATE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging/MessagingState.kt"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    coordinator = COORDINATOR.read_text(encoding="utf-8")
    state = STATE.read_text(encoding="utf-8")

    for marker in (
        "class MessagingCoordinator",
        "fun start(",
        "fun selectContact(",
        "fun onChatOpened(",
        "SensitiveContentDetector",
        "MessageConfirmationToken.issue",
        "MessagingState.CONTACT_RESOLVED",
        "MessagingState.PREPARING_MESSAGE",
        "MessagingState.WAITING_CONFIRMATION",
        "messageInputCandidate",
        "sendButtonCandidate",
    ):
        require(marker in coordinator, f"missing coordinator safety/state contract: {marker}")

    require("CONTACT_RESOLVED" in state, "state machine must record resolved contact")
    require("NEEDS_CONTACT_SELECTION" in state, "state machine must expose contact clarification")
    require("MESSAGE_CONTACT_AMBIGUOUS" in coordinator, "ambiguous contacts must fail closed")
    require("singleOrNull" in coordinator, "contact selection must not guess a duplicate")

    for forbidden in (
        "GenericAccessibilityExecutor",
        "AccessibilityNodeInfo",
        "android.view.accessibility",
        "performAction(",
        "dispatchGesture",
        "getBoundsInScreen(",
        "Rect(",
        "visibleBounds",
        "ACTION_SET_TEXT",
        "setText(",
        "sendText(",
        "ToolDispatcher",
    ):
        require(forbidden not in coordinator,
                f"coordinator must prepare only and never execute UI/send side effects: {forbidden}")

    require("UiActionType.CLICK" in coordinator,
            "opening a selected contact must remain a semantic CLICK proposal")
    require("openChatProposal" in coordinator and "confirmationCard" in coordinator,
            "coordinator result must distinguish navigation from confirmation")
    print("PASS: v0.7 messaging coordinator prepare-before-confirm contract")


if __name__ == "__main__":
    main()
