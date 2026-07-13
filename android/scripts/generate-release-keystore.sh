#!/usr/bin/env bash
# Generates a new production release keystore for RouteBot Android and prints the
# base64 value to paste into the ANDROID_RELEASE_KEYSTORE_BASE64 GitHub secret.
#
# Run this ONCE per app lifetime (losing this keystore means you can never publish
# an update to the same app listing again — back it up somewhere safe, e.g. a
# password manager or secrets vault, in addition to GitHub Secrets).
set -euo pipefail

OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE_PATH="${OUT_DIR}/release.jks"
ALIAS="routebot"

if [[ -f "$KEYSTORE_PATH" ]]; then
  echo "A keystore already exists at $KEYSTORE_PATH — refusing to overwrite it." >&2
  exit 1
fi

echo "Generating release keystore at: $KEYSTORE_PATH"
keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE_PATH" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10950

echo
echo "=== Keystore created ==="
echo "Path:  $KEYSTORE_PATH  (gitignored — do not commit this file)"
echo "Alias: $ALIAS"
echo
echo "Set these GitHub repository secrets (Settings -> Secrets and variables -> Actions):"
echo "  ANDROID_RELEASE_KEYSTORE_BASE64  = output of: base64 -i $KEYSTORE_PATH | tr -d '\\n'"
echo "  ANDROID_KEYSTORE_PASSWORD        = the keystore password you just entered"
echo "  ANDROID_KEY_ALIAS                = $ALIAS"
echo "  ANDROID_KEY_PASSWORD             = the key password you just entered"
echo
echo "Base64-encoded keystore (copy the full output below):"
echo "---"
base64 -i "$KEYSTORE_PATH" | tr -d '\n'
echo
echo "---"
