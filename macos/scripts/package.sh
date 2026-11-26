#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export CLANG_MODULE_CACHE_PATH="${TMPDIR:-/tmp}/sudoai-clang-cache"
export SWIFTPM_MODULECACHE_OVERRIDE="${TMPDIR:-/tmp}/sudoai-swift-cache"
CACHE="${TMPDIR:-/tmp}/sudoai-spm-cache"
VERSION=1.5.2
SHA=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
mkdir -p .cache dist
ARCHIVE="${OPUS_ARCHIVE:-$PWD/.cache/opus-$VERSION.tar.gz}"
if [[ ! -f "$ARCHIVE" ]]; then
  curl --fail --location --retry 3 --output "$ARCHIVE" "https://downloads.xiph.org/releases/opus/opus-$VERSION.tar.gz"
fi
printf '%s  %s\n' "$SHA" "$ARCHIVE" | shasum -a 256 -c -
if [[ ! -f ".cache/opus-$VERSION/.libs/libopus.0.dylib" ]]; then
  tar -xzf "$ARCHIVE" -C .cache
  (
    cd ".cache/opus-$VERSION"
    export MACOSX_DEPLOYMENT_TARGET=14.2
    lt_cv_sys_max_cmd_len=262144 ./configure --disable-static --enable-shared --disable-doc --disable-extra-programs
    make -j "${BUILD_JOBS:-4}"
  )
fi
swift build -c release -debug-info-format none --cache-path "$CACHE" --disable-sandbox
BIN="$(swift build -c release -debug-info-format none --show-bin-path --cache-path "$CACHE" --disable-sandbox)"
APP="$PWD/dist/SudoAI.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Frameworks" "$APP/Contents/Resources"
cp "$BIN/SudoAI" "$APP/Contents/MacOS/"
cp Info.plist "$APP/Contents/"
cp ".cache/opus-$VERSION/.libs/libopus.0.dylib" "$APP/Contents/Frameworks/"
cp ".cache/opus-$VERSION/COPYING" "$APP/Contents/Resources/libopus-LICENSE.txt"
install_name_tool -id @rpath/libopus.0.dylib "$APP/Contents/Frameworks/libopus.0.dylib"
IDENTITY="${SIGN_IDENTITY:--}"
SIGN_ARGS=(--force --sign "$IDENTITY")
if [[ "$IDENTITY" != '-' ]]; then SIGN_ARGS+=(--options runtime --timestamp); fi
codesign "${SIGN_ARGS[@]}" "$APP/Contents/Frameworks/libopus.0.dylib"
codesign "${SIGN_ARGS[@]}" --entitlements SudoAI.entitlements "$APP"
codesign --verify --deep --strict "$APP"
plutil -lint "$APP/Contents/Info.plist"
ditto -c -k --keepParent "$APP" "$PWD/dist/SudoAI-macos-$(uname -m).zip"
printf 'Packaged %s\n' "$APP"
