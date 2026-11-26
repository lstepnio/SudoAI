# Android single-app screen fixture

This isolated app displays invented qualification facts. It requests no permissions, has no network or storage code, and contains no credentials or user data. Its application ID is `com.majjix.sudoai.fixture`; display name is **SudoAI Test Slide**.

The **Show offline fact** button changes the visible text to a second invented fact set with codeword `cobalt meadow 83`. Change it only after verifying the test device has no active default network, so the resulting changed screen moment must enter the offline queue.

Build with the existing Android wrapper and local SDK/JDK:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./android/gradlew -p qa/android-slide-fixture :app:assembleDebug --offline --no-daemon
```

Install only onto the explicitly coordinated test device. Select **Share one app** and this fixture in SudoAI's MediaProjection picker, with microphone/playback/Omi OFF. Stop capture before cleanup. Remove with `adb -s DEVICE uninstall com.majjix.sudoai.fixture`; this leaves the separate SudoAI app, sign-in and recordings intact.

The fixture was installed on the physical Pixel for the 2026-11-25 screen-only test, then uninstalled after the captured meeting uploaded. See [lane3-pixel.md](../results/lane3-pixel.md).
