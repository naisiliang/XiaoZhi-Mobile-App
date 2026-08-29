# XiaoZhi Mobile v0.5.0 Design

## Goals

1. Continuous session timeout is user configurable, default 20 seconds.
2. Wake acknowledgement is user configurable, default `我在`.
3. Timeout exit phrase is user configurable, default `我先退下了，有问题再唤醒我`.
4. After a local command finishes, the assistant returns to listening immediately without a spoken acknowledgement loop.
5. Unknown device commands use the exact default fallback `抱歉，我还不会这个指令，你可以换一个指令继续服务你` and remain in the active session.
6. App launch supports any installed launchable app via a local installed-app index, fuzzy matching and user aliases.
7. Default APK launcher icon uses the user-provided blue/pink logo. Users can choose an image to create/update a custom pinned desktop shortcut icon and restore the default.

## Architecture

- `SessionController`: pure Kotlin active-session deadline management.
- `SettingsStore`: stores wake reply, timeout reply, timeout seconds and app alias text.
- `InstalledAppRegistry`: scans launchable applications, normalizes labels, resolves aliases and fuzzy matches.
- `PhoneController`: delegates app-name resolution to `InstalledAppRegistry`; actual launches continue through the same controller used by typed local tests.
- `WakeService`: owns session flow; silence loops until deadline, then speaks timeout phrase and hides overlay. Local commands immediately return to listening.
- `DesktopIconManager`: saves selected user image, crops to square, updates a pinned/dynamic shortcut, restores default icon.
- `MainActivity`: exposes session settings, aliases and desktop icon controls.

## Session semantics

- Wake starts an active session.
- After wake TTS completes, the full timeout window begins.
- Detecting user speech refreshes the deadline.
- Local commands refresh the deadline and immediately listen again without TTS confirmation.
- AI replies refresh the deadline when TTS finishes.
- Silence does not speak retry prompts; the service keeps listening in chunks until the configured deadline expires.
- On timeout, speak configured timeout phrase, hide overlay, then resume KWS.

## Unknown command semantics

- If local routing fails and the phrase looks like a phone/device command, speak fallback and continue listening.
- If it does not look like a device command and AI is configured, send it to AI.
- If AI is not configured, speak fallback and continue listening.

## App discovery

- Enumerate activities matching `ACTION_MAIN + CATEGORY_LAUNCHER`.
- Normalize app labels by removing whitespace/punctuation and common suffixes.
- Match order: explicit user alias -> exact label -> contains -> fuzzy edit-distance score.
- Keep known package aliases as a stable fallback for WeChat/QQ/media/maps.
- User alias format is one mapping per line, e.g. `B站=哔哩哔哩`.

## Desktop icon

- APK uses bundled default logo for `android:icon` and `android:roundIcon`.
- User-selected images are not allowed to mutate the installed APK icon at runtime.
- `DesktopIconManager` creates/updates a pinned shortcut using the selected bitmap.
- Restore default updates the custom shortcut back to the bundled default bitmap.
