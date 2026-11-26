# SudoAI Android pilot

Native Kotlin / Jetpack Compose client for Pixel phones running Android 13 or later. The default server is `https://sudoai.example.com`; change it on the signed-out screen. Uses the repository's `docs/capture-contract.md` API.

## Build

Install JDK 17 and Android SDK platform 36. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir=...`, then run:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The debug pilot APK is `app/build/outputs/apk/debug/app-debug.apk`. It uses Android's development signing identity and is not a production store release. Do not install it onto a user's phone without their coordination.

For a separately signed private release, supply an external keystore and environment variables `SUDOAI_KEYSTORE`, `SUDOAI_STORE_PASSWORD`, `SUDOAI_KEY_ALIAS`, and `SUDOAI_KEY_PASSWORD`, then run `./gradlew assembleRelease`. No signing credentials or private keystore are checked in. Without those variables, the release output is unsigned. Keep the same pilot key for subsequent upgrade installs.

## Capture

1. Sign in through the system browser. The app validates a time-limited PKCE state, exchanges the code and stores the bearer token with Android Keystore AES-GCM. Browser redirects use `sudoai://auth/callback`.
2. Choose microphone, Omi, eligible playback and/or screen moments. Start creates a session bound to the current account and server. Screen sharing requests fresh Android consent for each session.
3. For Omi, tap **Connect Omi** after starting. The scan connects to the first device advertising the Omi service. The device name and negotiated codec appear in source status. Use one advertising Omi nearby during the pilot. Disconnect and reconnect are explicit controls.
4. Pause, resume, bookmark or stop using the app or, when notifications are allowed, the foreground notification. If notification permission is denied, stop from the app. The microphone is room audio, including audible speakerphone sound. This app cannot directly capture remote cellular or Teams call audio. Android may silence the microphone during a call. Silencing callbacks create gaps and a source warning.
5. Screen moments use bundled, local ML Kit OCR, sample at most once per 15 seconds, deduplicate normalized text, and save a resized JPEG with changed text. A bookmark requests an immediate moment. Locked screens are skipped. If Android revokes sharing, stop and start a new session to grant consent again; unlocking or resuming does not restore it. Protected content and source apps may prevent screen/playback capture; silence alone cannot establish why playback is unavailable.

Omi reads codec characteristic `19b10002`, subscribes to audio `19b10001`, reassembles fragment-indexed packets and tracks packet sequence discontinuities. Codec 20 is DevKit Opus 16k mono / 10ms and codec 21 is CV1 Opus 16k mono / 20ms. Android MediaCodec decodes Opus; 48k decoder output is reduced to 16k mono. PCM16 codec 0 is also supported. Unknown codecs fail closed. Hardware GATT interoperability remains a qualification item.

## Offline data and imports

PCM is written incrementally in private app storage; 20-second independently playable WAV chunks receive deterministic identities, SHA-256 hashes and durable retry metadata. Pauses leave gaps in the monotonic timeline. A process interruption is recovered when the app reopens, retaining the partial PCM and marking an interruption. It does not restart capture automatically. Finalization follows acknowledged audio, moments and gaps with exact track counts. Acknowledgments are durably renamed and synced before their audio, image, text, or import payload is unlinked. Startup and retry sweeps remove any acknowledged leftovers. Pending bytes remain available for retry; the history screen can export the first still-pending audio chunk through the Android document picker. History and export access are scoped to the signed-in account and server. Final duration includes every persisted audio, screen moment and gap bound, including recovery after process death.

Retries run while the process lives, with capped exponential delay after failures; a persisted, network-constrained JobScheduler job also retries periodically. Android may defer background jobs. A 401 retains the queue and prompts sign-in through the visible status. Use **Sign in again**. Pending sessions never move to another signed-in account or server. **Revoke sign-in** requires a successful server revoke; **Forget local sign-in** clears only this device's credentials and keeps pending recordings. A 410 marks the server-deleted session locally, removes its payloads and continues syncing unrelated sessions. App backup and device transfer are excluded.

Android Share or the document picker accepts `.vtt` transcripts and available `audio/*` files, copied before upload. Pilot import size is capped at 64 MiB. Imports omit `session_id` and rely on server owner/content-hash deduplication for lost acknowledgments. The successful importer response, including a deduplicated response, saves the canonical meeting UUID for **Open meeting** in history. The pilot buffers multipart import data in memory, so split large files. Uninstalling or clearing app data deletes the private local queue.

## Verification and limits

See [QA.md](QA.md). Android microphone Stop/restart and offline audio recovery remain unqualified after an unexpected capture during testing. Tests do not establish real meeting accuracy, Pixel call contention, physical Omi interoperability, protected-app capture or long-session endurance. Six-hour wake-lock timeout and OS battery restrictions mean this is not qualified for unattended all-day recording. Acknowledged payloads are automatically removed; pending recordings can still fill storage, so monitor available space. Process death is covered by incremental files; sudden power loss may lose the current filesystem page-cache tail.

## Provenance

The Android implementation is original code written for this project, using Android/AndroidX and Google ML Kit dependencies under their respective licenses. Omi UUIDs, header framing and codec mapping were independently implemented from the upstream protocol and firmware:

- https://github.com/BasedHardware/omi/blob/main/sdks/device/PROTOCOL.md
- https://github.com/BasedHardware/omi/blob/main/omi/firmware/omi/src/lib/core/transport.c
- https://github.com/BasedHardware/omi/blob/main/sdks/python/omi/decoder.py

No upstream application code, bundled model weights from Omi, firmware or Opus native library has been copied. Android supplies its system Opus decoder. ML Kit's bundled OCR model is delivered by its declared Gradle dependency.

All authored Kotlin sources were formatted with the official ktfmt 0.54 formatter using `--kotlinlang-style`.
