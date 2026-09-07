from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
COORDINATOR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging/MessagingCoordinator.kt"
ADAPTER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation/ConversationAdapter.kt"
LAYOUT = ROOT / "app/src/main/res/layout/item_message_confirmation.xml"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    coordinator = COORDINATOR.read_text(encoding="utf-8")
    adapter = ADAPTER.read_text(encoding="utf-8")
    require(LAYOUT.is_file(), "confirmation row layout is missing")
    layout = LAYOUT.read_text(encoding="utf-8")
    ET.fromstring(layout)

    for marker in (
        "fun confirm(",
        "fun cancel(",
        "MessagingState.REVALIDATING",
        "MESSAGE_CONFIRMATION_EXPIRED",
        "MESSAGE_BODY_CHANGED",
        "SCREEN_CONTEXT_STALE",
        "matchesPendingContext",
        "currentIfMatches",
        "MessageSendRequest",
    ):
        require(marker in coordinator, f"missing live confirmation revalidation: {marker}")

    for marker in (
        "data class Confirmation",
        "submitConfirmation",
        "removeConfirmation",
        "R.layout.item_message_confirmation",
        "ConfirmationAction.CONFIRM",
        "ConfirmationAction.CANCEL",
        "confirmationListener",
    ):
        require(marker in adapter, f"missing confirmation row wiring: {marker}")

    for view_id in (
        "message_confirmation_contact",
        "message_confirmation_title",
        "message_text",
        "message_confirmation_confirm",
        "message_confirmation_cancel",
    ):
        require(f"@+id/{view_id}" in layout, f"missing confirmation card view: {view_id}")

    require('android:layout_width="match_parent"' in layout,
            "confirmation card must remain width-constrained")
    for forbidden in (
        "performAction(",
        "dispatchGesture",
        "getBoundsInScreen(",
        "AccessibilityNodeInfo",
        "GenericAccessibilityExecutor",
        "ToolDispatcher",
    ):
        require(forbidden not in coordinator and forbidden not in adapter,
                f"confirmation UI/coordinator must not directly execute UI actions: {forbidden}")
    print("PASS: v0.7 live message confirmation UI/revalidation contract")


if __name__ == "__main__":
    main()
