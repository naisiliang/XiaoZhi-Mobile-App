from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


profile = read("app/src/main/java/com/lchuang/xiaozhimobile/providers/ProviderCapabilityProfile.kt")
probe = read("app/src/main/java/com/lchuang/xiaozhimobile/providers/ProviderCapabilityProbe.kt")
health = read("app/src/main/java/com/lchuang/xiaozhimobile/providers/health/CapabilityHealthMonitor.kt")
settings = read("app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt")
layout = read("app/src/main/res/layout/activity_settings.xml")

for marker in (
    "enum class ProviderCapability",
    "TEXT",
    "RESPONSES_API",
    "CHAT_COMPLETIONS",
    "FUNCTION_CALLING",
    "STRUCTURED_OUTPUT",
    "VISION",
    "IMAGE_GENERATION",
    "FILE_INPUT",
    "CODE_INTERPRETER",
    "MCP",
    "NATIVE_SKILLS",
    "val localSkills",
    "val nativeSkills",
):
    require(marker in profile, f"profile missing {marker}")

for marker in (
    "ProviderProbeOperation",
    "ProviderCapabilityTransport",
    "ProviderCapabilityProbe",
    "CacheKey",
    "apiMode",
    "buildRequest",
    "HttpProviderCapabilityTransport",
    "MAX_RESPONSE_BYTES",
    "Authorization",
):
    require(marker in probe, f"probe missing {marker}")

for marker in (
    "CapabilityHealthState",
    "DEGRADED",
    "UNHEALTHY",
    "SUSPENDED",
    "SECURITY_VIOLATION",
    "recordFailure",
    "recheck",
):
    require(marker in health, f"health monitor missing {marker}")

require("org.json" not in probe, "provider probe must use testable bounded JSON construction")
require("ProviderCapabilityProbe" in settings, "settings must expose provider capability probe")
require("detectProviderCapabilities" in settings, "settings must provide a capability detection action")
require("provider_capability_status" in layout, "settings layout must show capability status")
require("detect_provider_capabilities" in layout, "settings layout must provide capability detection button")
print("PASS: v0.7 provider capability and health contract")
