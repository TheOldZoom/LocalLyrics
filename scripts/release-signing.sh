#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
keystore="$repo_root/release-signing.p12"
credentials="$repo_root/key.properties"

case "${1:-}" in
  generate)
    if [[ -e "$keystore" || -e "$credentials" ]]; then
      echo "Signing files already exist. Keep using the same key for future updates." >&2
      exit 1
    fi
    umask 077
    password="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
    keytool -genkeypair -noprompt \
      -keystore "$keystore" -storetype PKCS12 \
      -storepass "$password" -keypass "$password" \
      -alias lyrics -keyalg RSA -keysize 3072 -validity 10000 \
      -dname "CN=Lyrics Release, O=LocalLyrics"
    printf 'ANDROID_KEYSTORE_PASSWORD=%s\n' "$password" > "$credentials"
    echo "Created $keystore and $credentials. Back up both files securely."
    ;;
  upload)
    if [[ ! -s "$keystore" || ! -s "$credentials" ]]; then
      echo "Run '$0 generate' first." >&2
      exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
      echo "Sign in with 'gh auth login -h github.com' before uploading the key." >&2
      exit 1
    fi
    # The generated password contains only URL-safe characters. Never print it.
    source "$credentials"
    base64 -w 0 "$keystore" | gh secret set ANDROID_KEYSTORE_BASE64 -R TheOldZoom/LocalLyrics
    printf '%s' "$ANDROID_KEYSTORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD -R TheOldZoom/LocalLyrics
    echo "Uploaded signing secrets to TheOldZoom/LocalLyrics."
    ;;
  *)
    echo "Usage: $0 generate|upload" >&2
    exit 2
    ;;
esac
