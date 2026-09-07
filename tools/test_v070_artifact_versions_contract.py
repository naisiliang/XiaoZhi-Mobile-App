from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/artifacts"
CONVERSATION = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation"
RESOURCES = ROOT / "app/src/main/res"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


manager = ARTIFACTS / "ArtifactVersionManager.kt"
card = CONVERSATION / "ArtifactResultCard.kt"
adapter = CONVERSATION / "ConversationAdapter.kt"
layout = RESOURCES / "layout/item_artifact_result.xml"
require(manager.exists(), "missing artifact version manager")
require(card.exists(), "missing artifact conversation result card")
require(adapter.exists(), "missing conversation artifact card adapter integration")
require(layout.exists(), "missing conversation artifact result card layout")
manager_source = manager.read_text(encoding="utf-8")
card_source = card.read_text(encoding="utf-8")
adapter_source = adapter.read_text(encoding="utf-8")
layout_source = layout.read_text(encoding="utf-8")

for marker in (
    "ArtifactEditSession",
    "ArtifactVersionOperationResult",
    "beginEdit",
    "restore",
    "cancel",
    "allocateVersionFile",
    "moveIntoWorkspace",
    "ArtifactDigest.sha256",
    "parentVersion",
):
    require(marker in manager_source, f"version manager marker missing: {marker}")

for marker in ("ArtifactResultCard", "ArtifactCardAction", "OPEN", "EDIT", "RESTORE"):
    require(marker in card_source, f"conversation result card marker missing: {marker}")

for marker in ("Row.Artifact", "submitArtifact", "ArtifactCardListener", "VIEW_TYPE_ARTIFACT"):
    require(marker in adapter_source, f"conversation adapter card marker missing: {marker}")

for marker in ("artifact_meta", "artifact_open", "artifact_save", "artifact_share", "artifact_edit", "artifact_restore"):
    require(marker in layout_source, f"artifact card layout marker missing: {marker}")

for forbidden in ("REPLACE_EXISTING", "deleteArtifactDirectory", "reset --hard"):
    require(forbidden not in manager_source, f"version manager must not overwrite/delete lineage: {forbidden}")

print("PASS: v0.7 artifact version/edit/restore contract")
