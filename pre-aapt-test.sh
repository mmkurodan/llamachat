#!/usr/bin/env bash
set -euo pipefail

# pre-aapt-test.sh
# Run a sequence of Gradle checks up to AAPT2/resource processing and then clean build artifacts and caches.
# Useful on CI or local runners to catch dependency/compile/unit-test issues before AAPT2 runs.

JDK_DIR="${JAVA_HOME:-$HOME/.local/jdk-17}"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
GRADLE_WRAPPER="./gradlew"

export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -f "$GRADLE_WRAPPER" ]; then
  echo "Error: gradlew not found in repo root ($PWD). Run this script from the repository root." >&2
  exit 1
fi
chmod +x "$GRADLE_WRAPPER" || true

echo "Environment:"
echo "  JAVA_HOME=$JAVA_HOME"
echo "  ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"

action() {
  echo
  echo "=== gradle $* ==="
  # Exclude resource tasks that invoke AAPT2 which may fail on some hosts
  "$GRADLE_WRAPPER" "$@" -x :app:processDebugResources -x :app:mergeDebugResources --no-daemon --console=plain
}

# Tasks to attempt in order (some projects may fail on compile if R is missing).
TASKS=(
  ":app:dependencies"
  ":app:javaPreCompileDebug"
  ":app:compileDebugJavaWithJavac"
  ":app:compileDebugUnitTestJavaWithJavac"
  ":app:testDebugUnitTest"
)

any_failed=0
for t in "${TASKS[@]}"; do
  echo
  echo "--- Running task: $t ---"
  if ! action "$t"; then
    echo "*** Task failed: $t" >&2
    any_failed=1
    break
  fi
done

# Cleanup: remove build outputs and Gradle caches that often cause AAPT2/daemon issues
echo
echo "Cleaning build outputs and Gradle caches..."
"$GRADLE_WRAPPER" clean || true

# Remove AAPT2/transform caches and daemon caches which are safe to clear and can fix repeated aapt2 startup failures
rm -rf "$HOME/.gradle/caches/transforms-3" "$HOME/.gradle/caches/transforms-2" "$HOME/.gradle/daemon" 2>/dev/null || true

# Remove all module build directories to free space and ensure a clean next run
find . -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true

if [ "$any_failed" -eq 0 ]; then
  echo "Pre-AAPT checks completed; tasks succeeded (or were skipped)."
  exit 0
else
  echo "Pre-AAPT checks completed with failures; see logs above." >&2
  exit 2
fi
