# Messaging Task 3 report: WeChat and QQ semantic adapters

## Scope

- Base: `7fa443f`
- Feature commit: `cbd4988 feat: add wechat qq messaging adapters`
- Hardening commit: `bf9dfc7 fix: recognize messaging search result pages`
- Hardening commit: `96e2ba7 fix: reject ambiguous current chats`
- Branch: `recovery/v0.7.0-golden-first-full`
- Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## TDD evidence

The adapter fixture tests were added before the production adapters. The RED
command failed at test compilation because `WeChatMessagingAdapter` and
`QqMessagingAdapter` did not exist. The semantic-only shared adapter and the
two package-scoped implementations then made the unique-contact,
same-name-contact, current-chat, input/send, and package-isolation tests
GREEN.

During the local spec review, the plan's explicit search-page requirement was
covered with a new fixture. That test first failed because `search_results`
was classified as `UNKNOWN`; the minimal page-classification extension made
the search-result test GREEN.

## Implementation

- Added one shared semantic `MessagingAppAdapter` with concrete WeChat
  (`com.tencent.mm`) and QQ (`com.tencent.mobileqq`) adapters.
- Exact contact labels are resolved only from the current snapshot; a unique
  match produces a `UiActionProposal(CLICK, ...)`, while no match or multiple
  matches produces no proposal.
- Extracted current chat, message input, send button, list/search page, and
  context-bound semantic candidates without storing screen coordinates.
- Adapters have no Android UI action entry point. Real execution remains the
  existing centrally guarded generic executor's responsibility.
- Added a static contract rejecting direct Accessibility/UI execution and
  coordinate retention in messaging adapters.

No Level-1 frozen KWS source was changed.

## Review

Two fresh delegated reviewers were requested for the initial Task 3 commit,
and two fresh delegated reviewers were requested again for the final range
`7fa443f..bf9dfc`. All four remained running through bounded waits and were
closed without a verdict. They are recorded as unavailable, not CLEAN.

After the current-chat ambiguity hardening, a third fresh pair was requested
for `7fa443f..96e2ba7`. Both again timed out and were closed without a verdict;
they remain unavailable, not CLEAN.

The controller performed a read-only spec-compliance and code-quality/
security review of the final range. It found no unresolved Critical or
Important issue: package scope and context identity are preserved, ambiguous
contacts and ambiguous current chats fail closed, and no direct UI action or
coordinate cache exists.

## Verification

```text
gradle :app:testDebugUnitTest --tests '*MessagingAppAdapterTest*'  PASS
python tools/test_v070_messaging_adapter_contract.py                 PASS
python tools/test_v060_security.py                                   PASS
python tools/test_v070_phase0_recovery_gate.py                       PASS
gradle :app:testDebugUnitTest                                       PASS
gradle :app:assembleDebug                                           PASS
git diff --check                                                     PASS
```

No Android device or emulator was available; device validation remains
`DEVICE_GATE_PENDING`.
