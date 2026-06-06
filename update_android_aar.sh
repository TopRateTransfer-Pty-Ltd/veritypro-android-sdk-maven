#!/usr/bin/env bash
# =============================================================================
# update_android_aar.sh
# -----------------------------------------------------------------------------
# Rebuilds the VerityPro Android SDK AAR from source and copies it into the
# Flutter wrapper (verity_flutter_sdk) so the wrapper — and the app — actually
# pick up Android source changes.
#
# WHY THIS EXISTS:
# The wrapper bundles a *prebuilt* AAR via `implementation(files(...))` in
# verity_flutter_sdk/android/build.gradle. Committing Kotlin source alone does
# NOT update that AAR — the app keeps using the stale binary. This is the
# Android equivalent of rebuilding the iOS XCFramework. Run this after ANY
# change to the Android SDK Kotlin source.
#
# USAGE:
#   ./update_android_aar.sh
#
# Then in verity_flutter_sdk: commit the refreshed AAR and push; finally bump
# the app's pubspec.yaml `verity` git ref to the new wrapper commit.
# =============================================================================
set -euo pipefail

# --- Resolve paths (override WRAPPER_DIR via env if your checkout differs) ---
SDK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER_DIR="${WRAPPER_DIR:-$(cd "$SDK_DIR/../verity_flutter_sdk" 2>/dev/null && pwd || true)}"

AAR_SRC="$SDK_DIR/veritypro-sdk/build/outputs/aar/veritypro-sdk-release.aar"
AAR_DEST_DIR="$WRAPPER_DIR/android/libs"
AAR_DEST="$AAR_DEST_DIR/veritypro-sdk-release.aar"

echo "▶ SDK repo:     $SDK_DIR"
echo "▶ Wrapper repo: ${WRAPPER_DIR:-<not found>}"

if [[ -z "${WRAPPER_DIR:-}" || ! -d "$AAR_DEST_DIR" ]]; then
  echo "✗ Could not locate the Flutter wrapper libs dir at: $AAR_DEST_DIR"
  echo "  Set WRAPPER_DIR=/path/to/verity_flutter_sdk and re-run."
  exit 1
fi

# --- 1. Build the release AAR from source ---
echo "▶ [1/3] Building release AAR…"
"$SDK_DIR/gradlew" -p "$SDK_DIR" :veritypro-sdk:assembleRelease

if [[ ! -f "$AAR_SRC" ]]; then
  echo "✗ Build finished but AAR not found at: $AAR_SRC"
  exit 1
fi

# --- 2. Sanity-check the AAR actually contains the camera classes ---
echo "▶ [2/3] Verifying AAR contents…"
TMP_JAR="$(mktemp -t vp_classes.XXXXXX.jar)"
trap 'rm -f "$TMP_JAR"' EXIT
if unzip -p "$AAR_SRC" classes.jar > "$TMP_JAR" 2>/dev/null; then
  CLASS_COUNT="$(unzip -l "$TMP_JAR" 2>/dev/null \
    | grep -ciE 'document_overlay|document_capture|camera_utils' || true)"
  echo "  camera classes present: $CLASS_COUNT"
  if [[ "$CLASS_COUNT" -eq 0 ]]; then
    echo "✗ AAR has no camera classes — aborting (build may be broken)."
    exit 1
  fi
else
  echo "  (could not introspect classes.jar — continuing)"
fi

# --- 3. Copy into the wrapper ---
echo "▶ [3/3] Copying AAR → wrapper…"
cp "$AAR_SRC" "$AAR_DEST"

echo ""
echo "✅ Done. Refreshed: $AAR_DEST"
echo "   $(ls -la "$AAR_DEST" | awk '{print $5" bytes  "$6" "$7" "$8}')"
echo ""
echo "Next steps:"
echo "  1) cd $WRAPPER_DIR && git add android/libs/veritypro-sdk-release.aar"
echo "     git commit -m 'build: refresh Android AAR' && git push"
echo "  2) Bump the app's pubspec.yaml verity git ref to the new wrapper commit."
