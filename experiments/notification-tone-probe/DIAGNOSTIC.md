# Bundled notification tone diagnostic — 2026-10-10

## Scope and build

- This is a standalone diagnostic app at package `com.example.notificationtoneprobe`, separate from the learning-monitor experiment and product app.
- It targets SDK 37 and requests only `POST_NOTIFICATIONS`. It has no internet permission, Accessibility service, learning data, login, or external API calls.
- The `bundled-tone-diagnostic-v1` channel is high importance and uses an app-resource WAV with `USAGE_NOTIFICATION`. The app does not change device volume, DND, global notification sound, or other system settings.
- The WAV is a 0.46-second, mono 44.1 kHz, 16-bit tone at modest amplitude.
- On commit `853caa4`, the user directly tapped **Post one bundled-tone test** and then reported: “헤드업 알림, 소리 확인했어. 내버려두면 알림 목록에 추가되네” (heads-up and sound were confirmed; it stays in the notification list if left alone).
- A Standards/Spec review then found that a secondary intent-extra route could repost after activity recreation. The follow-up removes that route and leaves the explicit button as the only post path. The tested button behavior is unchanged; the hardening build was installed with `adb install -r` without posting again.
- After the hardening install, `POST_NOTIFICATIONS` remained granted. The notification dump listed the configured channel at importance 4 with sound URI `android.resource://com.example.notificationtoneprobe/2130771968`; no active ID 9100 remained.
- After route removal, `:app:assembleDebug` and `:app:lintDebug` passed (43 actionable Gradle tasks). There are no unit tests because this diagnostic is only Android notification UI/platform plumbing.

## Observed result

| Observation | Result | Evidence |
| --- | --- | --- |
| Notification posted | Pass | The user invoked the explicit app button; system log `notification_enqueue` recorded package `com.example.notificationtoneprobe`, ID `9100`, and channel `bundled-tone-diagnostic-v1` at 22:02:42.614. |
| Heads-up | User-confirmed pass | The user confirmed the heads-up. SystemUI logged this notification's heads-up state as visible at 22:02:42.913 and hidden at 22:02:48.611. This is a transient display interval, not a claim about later visibility. |
| Sound | User-confirmed pass | The user confirmed hearing the bundled tone. The notification channel points at the app's bundled resource and uses notification audio attributes. The device does not expose a reliable per-notification hardware playback measurement in this evidence. |
| Notification list | User-confirmed pass | The user confirmed the notification remained in the notification list when left alone. |

## Relation to the original probe

The monitoring probe's manual-call notification used Android's default URI `content://settings/system/notification_sound`. Device logs recorded an `Unable to create media player` / `setDataSource failed` error for that URI, and the global default URI query returned `null`. This supports a sound-delivery failure for that original manual call; it does not show that Accessibility-hosted execution failed, and it does not justify changing the product's default-sound policy. The bundled-resource test confirms that a separate controlled channel can produce user-confirmed audio, heads-up, and drawer behavior without changing global settings.

The user observation is specific to this standalone bundled-tone app and its explicit button test. It does not retroactively establish sound or heads-up delivery for the original monitoring-probe notification.
