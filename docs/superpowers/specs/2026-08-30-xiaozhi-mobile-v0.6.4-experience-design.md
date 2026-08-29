# XiaoZhi Mobile v0.6.4 Experience Upgrade Design

## Status

Approved design inputs were collected from the user on 2026-08-30. This document is the implementation contract for v0.6.4.

Implementation must preserve the already working v0.6.3 custom wake phrase path. The custom wake/KWS subsystem is a frozen compatibility boundary for this release.

## Primary Goals

v0.6.4 focuses on post-wake interaction quality rather than wake-word technology. It must deliver four outcomes:

1. Every successful phone command verbally confirms completion and then clearly transitions back into listening.
2. The transparent assistant overlay supports safe manual and spoken exit.
3. Media-volume control understands natural Chinese such as “最大”, “百分之七十”, “一半”, and “静音”, then verifies actual Android volume after setting it.
4. The conversation becomes faster and more predictable through an explicit state model, self-listening protection, and duplicate-command protection.

## 1. Frozen Area: Custom Wake Phrase

The following v0.6.3 behavior MUST NOT be changed:

- `WakePhraseCompiler`
- `WakePhraseManager`
- `Pinyin4jProvider`
- runtime custom wake-word compilation
- ppinyin Unicode normalization added in v0.6.3
- KWS model paths/files
- KWS keyword score/threshold/trailing blank parameters
- runtime stream creation rules
- saving/applying a custom wake phrase
- fallback behavior if custom wake fails
- active KWS phrase persistence/diagnostics

Required regression:

```text
助手名字：小白
唤醒词：小白小白
保存并应用
→ 当前实际 KWS 唤醒短语：小白小白
→ 回到桌面说“小白小白”
→ 正常唤醒
```

Existing v0.6.3 custom-wake regression tests must stay green.

## 2. Conversation State Model

Introduce one explicit state enum:

```text
IDLE_WAKE
LISTENING
RECOGNIZING
EXECUTING
SPEAKING
READY_TO_LISTEN
EXITING
```

Meaning:

- `IDLE_WAKE`: background KWS active; no conversation overlay session.
- `LISTENING`: microphone actively capturing the next user utterance.
- `RECOGNIZING`: audio is being converted to text.
- `EXECUTING`: local router, AI reply, or safe tool is processing.
- `SPEAKING`: TTS is talking; ASR must not listen.
- `READY_TO_LISTEN`: TTS ended; short guard delay before reopening mic.
- `EXITING`: session is closing; no next-listen may be scheduled.

Successful phone command:

```text
LISTENING
→ RECOGNIZING
→ EXECUTING
→ SPEAKING
→ READY_TO_LISTEN
→ LISTENING
```

Exit:

```text
LISTENING
→ RECOGNIZING
→ EXITING
→ SPEAKING
→ IDLE_WAKE
```

While `SPEAKING`, command ASR and KWS must not restart. After TTS finishes, wait about 120–180 ms, then enter `LISTENING`, start real microphone capture, and only then show that XiaoZhi is listening.

## 3. Command Completion and Next-Prompt Behavior

Every successful local device command and successful AI safe-tool command must speak a short completion result.

First successful device action after wake:

```text
“<结果>，你有什么需求请说？”
```

Examples:

```text
“微信已经打开，你有什么需求请说？”
“高德地图已经打开，你有什么需求请说？”
“媒体音量已经调整到60%，你有什么需求请说？”
“手电筒已经打开，你有什么需求请说？”
```

Later successful device actions in the same session may shorten to:

```text
“<结果>，请继续说。”
```

Normal AI conversation must not say “指令已经执行完成”. It should end naturally with a short continuation prompt such as “你还需要什么？”.

If an action fails, never claim success. Speak the actual failure and return to listening if the session stays active.

## 4. Transparent Overlay Manual Exit

Confirmed design: **B — close button + double-tap panel**.

The overlay panel must provide:

- visible `×` in the top-right;
- double-tap on panel background to exit;
- outside-panel area remains touch-through to the Android app/desktop.

The current full-screen non-touchable overlay must become a panel-sized touchable `WindowManager` overlay. The whole transparent screen must not intercept touches.

Manual close flow:

1. stop current command capture if active;
2. cancel pending re-listen callbacks;
3. enter `EXITING`;
4. clear session conversation memory;
5. speak `好的，有需要再叫我`;
6. hide overlay;
7. return to `IDLE_WAKE`;
8. resume the already-active custom KWS wake phrase.

Manual exit must not stop the background wake service.

Single tap on empty panel does not exit. Double-tap should use Android gesture timing. Repeated exit requests are idempotent.

## 5. Intelligent Voice Exit

Add focused `ConversationExitDetector` returning:

```text
EXIT
CONTINUE
AMBIGUOUS
```

Strong local exit examples:

```text
退出
退出吧
退下
你退下吧
没什么事了
没事了
没事你先退下
不用了
先这样吧
就这样吧
结束吧
结束对话
你先休息吧
可以休息了
再见
拜拜
今天先到这里
暂时没别的事
```

These must NOT exit XiaoZhi:

```text
退出微信
退出登录
退出当前账号
怎么退出这个页面
帮我关闭高德地图
关闭微信
```

If local detection is `AMBIGUOUS` and AI is configured, AI may classify only session intent:

```text
EXIT
CONTINUE
```

It must not execute tools or expand permissions. If AI classification fails, uncertain input stays `CONTINUE`.

Preferred voice-exit response:

```text
“好的，我先退下了，有需要再叫我。”
```

Then close overlay and resume custom KWS.

## 6. Natural Chinese Media-Volume Control

Confirmed option A: generic volume commands control only Android `STREAM_MUSIC`.

They must not automatically change ringtone, notification, or alarm volume.

Add `VolumeCommandParser` with structured actions:

```text
SetPercent(percent)
StepUp
StepDown
Unhandled
```

Required interpretations:

```text
把手机音量调到最大     -> 100
声音开满               -> 100
音量调到最高           -> 100
音量调到百分之七十     -> 70
音量调到70%            -> 70
音量调到70             -> 70
音量调到一半           -> 50
静音                   -> 0
声音关掉               -> 0
音量大一点             -> StepUp
音量小一点             -> StepDown
```

Chinese percentage support at minimum:

```text
零 一 二 三 四 五 六 七 八 九 十
二十 三十 四十 五十 六十 七十 八十 九十
一百
```

`PhoneController` must return structured results, e.g.:

```text
MediaVolumeResult(
    requestedPercent: Int?,
    actualPercent: Int,
    success: Boolean
)
```

Set flow:

1. read media max volume;
2. map requested percent to a valid stream step;
3. call `setStreamVolume(STREAM_MUSIC, step, FLAG_SHOW_UI)`;
4. read `getStreamVolume(STREAM_MUSIC)` again;
5. calculate actual percent;
6. speak based on actual value.

Step-up/down also re-read actual media volume.

Examples:

```text
“媒体音量已经调整到100%”
“媒体音量现在是53%”
“媒体音量已经静音”
```

If OEM policy prevents the exact requested value, report actual value rather than falsely claiming success.

## 7. Responsiveness Improvements

### Duplicate-command guard

For device commands only, suppress an identical normalized command repeated within about 1.2–1.8 seconds because of ASR/session timing. Allow intentional repetition after the window.

### Session timeout semantics

Refresh the continuous-session timeout when real `LISTENING` begins after TTS. Long TTS must not consume most of the user’s speaking window.

### Truthful overlay state

```text
LISTENING       -> 正在听你说…
RECOGNIZING     -> 正在识别…
EXECUTING       -> 正在执行…
SPEAKING        -> 正在回复…
READY_TO_LISTEN -> 准备继续监听…
EXITING         -> 正在退出…
```

Never display “我在听…” while TTS is speaking.

### Animation

- listening: waveform/ring reacts to microphone level;
- recognizing/executing: reduced/fixed animation;
- speaking: gentle non-mic-reactive animation;
- exiting: stop waveform before hiding.

Preserve the current transparent dark-tech visual style.

### Local-first behavior

Local deterministic commands remain ahead of AI:

```text
open app
media controls
volume
flashlight
navigation
nearby map search
```

## 8. Component Boundaries

### New files

`ConversationState.kt`
- state enum and UI-state mapping.

`ConversationExitDetector.kt`
- local exit/continue/ambiguous classification.

`VolumeCommandParser.kt`
- Chinese volume intent and percentage parsing; no Android APIs.

### Modified files

`PhoneController.kt`
- structured media-volume set/step/read; `STREAM_MUSIC` only.

`CommandRouter.kt`
- delegates volume parsing and returns verified actual-volume reply.

`AssistantOverlayView.kt`
- close target, double-tap gesture, state-aware rendering.

`AssistantOverlayController.kt`
- panel-sized touchable overlay and `setOnExitRequested` callback.

`WakeService.kt`
- explicit post-wake conversation state;
- overlay manual exit hookup;
- exit detector;
- optional AI exit-classifier fallback;
- stale re-listen cancellation;
- duplicate guard;
- improved prompt flow;
- frozen KWS code untouched.

`AiOrchestrator.kt` or a narrow helper
- optional `classifyExitIntent(text)` returning EXIT/CONTINUE only;
- no Android tool execution.

## 9. Safety and Error Handling

- Manual and voice exits are idempotent.
- Pending `startLocalCommandRecognition()` callbacks are canceled on exit.
- Old TTS callbacks cannot reopen listening after exit.
- AudioRecord release remains exception-safe.
- If TTS is unavailable, exit still completes and KWS resumes.
- If overlay permission is unavailable, voice/session behavior still works.
- If AI exit classification fails, conversation stays active unless local strong exit matched.
- Generic volume never touches non-media streams.

## 10. Testing Strategy

Every behavior change is test-first.

New regression tests:

- `tools/test_v064_conversation_state.py`
- `tools/test_v064_overlay_exit.py`
- `tools/test_v064_exit_intent.py`
- `tools/test_v064_volume_parser.py`
- `tools/test_v064_volume_execution.py`
- `tools/test_v064_command_prompt_flow.py`
- `tools/test_v064_duplicate_guard.py`
- `tools/test_v064_wake_regression.py`

Required coverage:

- state transitions and truthful UI;
- close button + double-tap + outside touch-through;
- exit phrases and false-exit protection;
- max/percent/half/mute/step volume parsing;
- `STREAM_MUSIC` only and actual-volume re-read;
- completion prompt followed by guarded re-listen;
- duplicate device command suppression;
- v0.6.3 custom wake path unchanged.

Existing v0.3.1 through v0.6.3 regression suites must remain green, especially:

- v0.6.1 KWS startup/stop behavior;
- v0.6.2 wake apply/command confirmation;
- v0.6.3 custom wake ppinyin/error diagnostics.

## 11. Real-Device Acceptance

### Frozen wake

```text
小白小白
→ 正常唤醒
```

### Command completion

```text
打开微信
→ 微信打开
→ “微信已经打开，你有什么需求请说？”
→ TTS ends
→ overlay actually enters listening
→ next command works without re-wake
```

### Manual exit

- tap `×` -> exits session, custom KWS stays active;
- wake again;
- double-tap panel -> exits session, custom KWS stays active;
- outside panel still accepts normal phone taps.

### Voice exit

These exit:

```text
退出
退下
没什么事了
先这样吧
今天先到这里
```

These do not exit XiaoZhi:

```text
退出微信
退出登录
怎么退出这个页面
```

### Media volume

Verify using Android system media-volume UI:

```text
把手机音量调到最大       -> actual media volume ~100%
音量调到百分之七十       -> actual media volume ~70%
音量调到一半             -> ~50%
静音                     -> 0%
音量大一点               -> one media step up
音量小一点               -> one media step down
```

Spoken confirmation must match the actual re-read value.

## 12. Release Boundary

Target:

```text
versionName = 0.6.4
versionCode = 11
artifact = XiaoZhi-Mobile-v0.6.4-debug.apk
```

Not included in this release:

- new KWS models or wake algorithms;
- cloud TTS;
- generic ringtone/notification/alarm volume changes;
- new dangerous AI tools;
- long-term memory;
- full visual redesign.

v0.6.4 is successful when v0.6.3 custom wake remains stable and post-wake interaction becomes deterministic, visibly truthful, manually/verbally closable, and natural Chinese media-volume commands work on the real device.
