# Android verification

Run from `android/` with JDK 17 and Android SDK 36:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Run instrumentation only on a disposable emulator; its fixtures replace local synthetic credentials and must not be run against a user's recording queue. Use [the contract launcher](../qa/ANDROID.md) for the optional local HTTPS integration test.

**Current limitation:** physical microphone Stop/restart and offline audio recovery are not qualified. An unexpected second capture occurred during a scripted test. Do not rely on the pilot for unattended recording. Unit tests and successful compilation do not establish device safety or real meeting accuracy.

Permission denial/recovery, call contention, Omi interoperability, source routing and long-session endurance require explicit hardware qualification. Signing keys, recordings and operational receipts are excluded from this repository.
