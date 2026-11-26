# SudoAI for macOS

Native SwiftUI menu bar app for macOS 14.2 or later. Default service: `https://sudoai.example.com`. Build with Xcode's Swift toolchain; no Electron, virtual audio driver, Docker container, or change to system input/output defaults.

## Build and package

```sh
cd macos
swift test
./scripts/package.sh
open dist/SudoAI.app
```

`package.sh` downloads official libopus **1.5.2**, verifies SHA-256 `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1`, compiles it for the current Mac architecture, embeds its dylib and license, builds the optimized Swift executable, signs the app, checks the signature, and creates `dist/SudoAI-macos-arm64.zip` (or `x86_64`). It does not launch the app or request capture permission. `OPUS_ARCHIVE=/path/opus-1.5.2.tar.gz` supports an already downloaded archive. `BUILD_JOBS=4` controls dependency build parallelism. Release builds omit debug symbols. Build outputs and dependency cache are ignored by Git.

Without `SIGN_IDENTITY`, signing is ad hoc for local development. Stable capture permissions and distribution require a Developer ID Application identity. For distribution:

```sh
SIGN_IDENTITY='Developer ID Application: your identity' ./scripts/package.sh
NOTARY_PROFILE='your-existing-keychain-profile' ./scripts/notarize.sh
```

Notarization is an explicit separate operation. The script submits to Apple, waits, staples, checks Gatekeeper, and recreates the zip. Do not distribute an ad hoc build as a notarized app. This repository contains no signing credentials. The bundle has microphone, process-audio, screen, Bluetooth usage descriptions, the `sudoai` callback scheme, `LSUIElement`, and hardened-runtime audio-input entitlement. The direct-distribution app intentionally does not enable App Sandbox, which would require separate qualification of Core Audio process taps and local spool access.

In a filesystem-restricted shell, use the same writable caches as the packaging script:

```sh
CLANG_MODULE_CACHE_PATH=/private/tmp/sudoai-clang-cache \
SWIFTPM_MODULECACHE_OVERRIDE=/private/tmp/sudoai-swift-cache \
swift test --cache-path /private/tmp/sudoai-spm-cache --disable-sandbox
```

## Use

1. Open the menu bar waveform icon, expand Account & service, and sign in in the default browser. PKCE uses a random verifier, S256 challenge, random state, ten-minute expiry, single-use callback validation, and a Keychain token. Only HTTPS service origins are accepted. Account changes are disabled while recording.
2. Choose microphone/headset, native Microsoft Teams output, optional Omi, and/or optional screen moments. Microphone uses its own AVAudioEngine input and can select a device independently. Teams uses a private per-process Core Audio tap, includes Teams embedded helpers, refreshes process membership every two seconds, and leaves Teams playback unmuted. Browser-hosted Teams is not included.
3. Start. Each audio source has its own meter/status and track (`mic`, `system`, `omi`). Disable a source to mute that capture. Pause suppresses durable recording of all source samples; elapsed session time continues. Omi may remain connected during pause but its audio is discarded. Resume records the pause as explicit gaps. Stop seals local chunks and queues finalization. Add a text bookmark at any time while the session is open.
4. Screen capture is opt-in. On start, or using Choose window or display, the system picker selects one window or display. The stream captures no screen audio. Every two seconds the app examines a small local thumbnail, saves only significant visual changes with at least four seconds between saved moments, runs Vision OCR locally, and writes a JPEG plus text. Full selected content can contain sensitive information. Turn screen moments off to end the stream.
5. Omi is opt-in and requires releasing the stock Omi app or phone connection first. Claiming ownership scans the Omi service; choose the discovered device explicitly. A local advisory lock prevents a second SudoAI process claiming it. Stop or turn Omi off to unsubscribe, disconnect, and release the lock, then reconnect using the other owner. This app cannot forcibly coordinate or disconnect a different phone or the stock app. Automatic reconnect targets only the explicitly selected device.
6. History works offline and shows pending counts. Open goes to the service meeting page; Show local files opens the local archive. Upload retries back off from 5 seconds to 5 minutes; Sync retries immediately. A 401 asks for sign-in while retaining every local byte. Different accounts and different service origins cannot upload each other's queued captures.

## Persistence and protocol

`~/Library/Application Support/SudoAI/Captures` is a private local directory. Each source writes mono PCM16 WAV in independently playable chunks of at most 20 seconds, well under the 8 MiB limit at ordinary hardware sample rates. Sample-rate changes and source discontinuities rotate chunks. Callbacks append samples without rewriting the growing queue index. About once per second, and at chunk or event boundaries, the app repairs WAV headers, synchronizes active files, and atomically checkpoints the active journal. A sudden power loss may lose samples since the last checkpoint; an app crash recovers surviving bytes by file length. On restart, the app reconciles surviving file lengths, seals active chunks, marks interruption, and finalizes interrupted sessions using only durable samples. It never silently switches a recovered capture to the currently signed-in owner.

The queue persists immutable request bytes, sequence numbers, source offsets, server origin, owner ID, and acknowledgement flags. Uploads send sessions before chunks, moments/gaps before finalize, and exact track chunk counts. Only 2xx acknowledges an item. SHA-256 covers the uploaded audio bytes. Redirects outside the original HTTPS origin are rejected. Payload files are unlinked only after their durable acknowledgement is persisted; recovery sweeps acknowledged leftovers. Pending bytes are retained. Local history metadata is shown only for the active account and service origin. HTTP 410 permanently retires the local queue session, preserving its pending bytes without retrying or recreating the deleted server session. Protect the Mac account and disk appropriately. Capture timing is based on the session's monotonic uptime clock and source sample counts; it is not hardware-clock synchronized across independent devices.

Omi uses the stock service and audio/codec characteristics, sequence-aware three-byte notification headers, fragment reassembly, 16 kHz mono PCM16 codec 0, and libopus for DevKit codec 20 (10 ms) and CV1 codec 21 (20 ms). Returned Opus sample counts determine timing. Unsupported codecs are blocked. Missing packets, liveness timeout, disconnection, permission errors, and disabled sources produce visible states/gaps. Final complete notification-frame data is drained on release; an incomplete fragmented tail may be rejected by the decoder.

Protocol sources: [SudoAI service contract](../docs/capture-contract.md), [Omi device protocol](https://github.com/BasedHardware/omi/blob/main/sdks/device/PROTOCOL.md), [official libopus source](https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz). Apple framework APIs are compiled against the installed SDK.

## Qualification boundary

Automated tests use only generated PCM, mock HTTP, synthetic text images, and synthetic Opus packets. They cover playable WAV boundaries, track separation, interrupted capture, durable acknowledgement, closed-session protection, image limits, SHA/PKCE encoding, Omi fragmentation/loss/wraparound, account/origin isolation, 401/409 preservation, actual Opus frame durations, and Vision OCR. The Opus test loads the packaged dylib when available and skips until `package.sh` builds the pinned library. Vision OCR may require running outside a filesystem-restricted automation sandbox because its system runtime initializes local caches. The initial restricted run returned `nilError`; the same generated image passed outside that sandbox.

No test records a real microphone, Teams call, display/window, or Omi device. Physical qualification still needs explicit interactive consent: permission grant/deny/revoke, headset hot-plug and route changes (if capture stops, toggle the microphone off and on), Teams helper restart and a real call, user mute versus silence, selected/hidden/protected screen content, sustained recording and disk-full interruption, sleep/wake, Omi CV1/DevKit packet behavior and ownership handoff, and real browser authentication against the deployed service. No installed Developer ID was available during initial build, so notarization and Gatekeeper acceptance remain unverified. Silence is labelled “Silent or source muted”; the app cannot infer Teams participants' mute controls from zero samples. OCR and scene-change detection are heuristic and can miss subtle updates.
