from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ADAPTER_DIR = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging"
ADAPTER_FILES = (
    ADAPTER_DIR / "MessagingAppAdapter.kt",
    ADAPTER_DIR / "WeChatMessagingAdapter.kt",
    ADAPTER_DIR / "QqMessagingAdapter.kt",
)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    sources = {}
    for path in ADAPTER_FILES:
        require(path.is_file(), f"missing messaging adapter source: {path.name}")
        sources[path.name] = path.read_text(encoding="utf-8")

    shared = sources["MessagingAppAdapter.kt"]
    wechat = sources["WeChatMessagingAdapter.kt"]
    qq = sources["QqMessagingAdapter.kt"]

    require("abstract class MessagingAppAdapter : AppAdapter" in shared,
            "messaging semantics must remain an AppAdapter")
    for marker in (
        "resolveContactCandidates",
        "proposeOpenChat",
        "currentChatCandidate",
        "messageInputCandidate",
        "sendButtonCandidate",
        "UiActionProposal",
        "singleOrNull()",
    ):
        require(marker in shared, f"missing semantic messaging adapter contract: {marker}")

    require('messagingPackageName: String = "com.tencent.mm"' in wechat,
            "WeChat adapter package scope is missing")
    require('messagingPackageName: String = "com.tencent.mobileqq"' in qq,
            "QQ adapter package scope is missing")
    require("WeChatMessagingAdapter : MessagingAppAdapter" in wechat,
            "WeChat adapter must use the shared semantic adapter")
    require("QqMessagingAdapter : MessagingAppAdapter" in qq,
            "QQ adapter must use the shared semantic adapter")

    combined = "\n".join(sources.values())
    for forbidden in (
        "AccessibilityNodeInfo",
        "android.view.accessibility",
        "performAction(",
        "dispatchGesture",
        "getBoundsInScreen(",
        "Rect(",
        "visibleBounds",
        "android.graphics",
        "GenericAccessibilityExecutor",
    ):
        require(forbidden not in combined,
                f"messaging adapter must not execute or cache coordinate UI actions: {forbidden}")

    require("UiActionType.CLICK" in shared,
            "opening a unique contact must produce a central CLICK proposal")
    require("return emptyList()" in shared and "if (!canHandle(context.packageName))" in shared,
            "adapter operations must be package-scoped")
    print("PASS: v0.7 WeChat/QQ semantic adapter safety contract")


if __name__ == "__main__":
    main()
