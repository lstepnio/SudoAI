#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${NOTARY_PROFILE:?Set NOTARY_PROFILE to an existing notarytool Keychain profile}"
APP="$PWD/dist/SudoAI.app"
# package.sh must first be run with SIGN_IDENTITY set to an installed Developer ID Application identity.
codesign --verify --deep --strict "$APP"
if ! codesign -dv "$APP" 2>&1 | /usr/bin/grep -q 'Authority=Developer ID Application'; then
  printf 'Build with a Developer ID Application identity before notarization.\n' >&2
  exit 1
fi
ZIP="$PWD/dist/SudoAI-macos-$(uname -m).zip"
xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" --wait
xcrun stapler staple "$APP"
xcrun stapler validate "$APP"
spctl --assess --type execute "$APP"
ditto -c -k --keepParent "$APP" "$ZIP"
