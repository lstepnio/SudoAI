# macOS verification

```sh
swift test --package-path macos
macos/scripts/package.sh
```

Tests use generated PCM, Opus packets and text images. The decoder test requires the pinned bundled libopus; Vision may need permission to initialize its local caches. Packaging verifies an ad-hoc signature by default, not Developer ID notarization.

Native sign-in, actual microphone/Teams tracks, headset changes, sleep/wake, Omi handoff and long-session behavior require consented hardware qualification. No build or fixture result is proof of physical capture fidelity.
