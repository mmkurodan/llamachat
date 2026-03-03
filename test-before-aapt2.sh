#!/usr/bin/env bash
set -euo pipefail

# Pre-AAPT2 test runner
# - Runs Gradle "test" while excluding resource tasks that invoke AAPT2
# - Captures logs to a timestamped directory
# - Cleans project build artifacts and generated local.properties after run

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$REPO_ROOT"

JDK_DIR="$HOME/.local/jdk-17"
GRADLE_DIR="$HOME/.local/gradle/gradle-8.10.2"

# Prefer local toolchain if available
if [ -x "$JDK_DIR/bin/java" ]; then
  export JAVA_HOME="$JDK_DIR"
  export PATH="$JDK_DIR/bin:$PATH"
fi
if [ -x "$GRADLE_DIR/bin/gradle" ]; then
  export PATH="$GRADLE_DIR/bin:$PATH"
fi

# Android SDK fallback
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
if [ -d "$ANDROID_SDK_ROOT" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

echo "Repository: $REPO_ROOT"

echo "Java:"
java -version 2>&1 | sed -n '1,2p' || true

echo "Gradle:"
gradle -v 2>/dev/null | sed -n '1,5p' || true

LOG_DIR="$REPO_ROOT/pre_aapt2_test_logs_$(date +%Y%m%d%H%M%S)"
mkdir -p "$LOG_DIR"

# Gradle command excluding AAPT2-triggering resource tasks
GRADLE_CMD=("./gradlew" "test" "--no-daemon" "--console=plain"
  "-x" ":app:processDebugResources"
  "-x" ":app:mergeDebugResources"
  "-x" ":app:packageDebugResources"
  "-x" ":app:processReleaseResources"
  "-x" ":app:mergeReleaseResources"
  "-x" ":app:packageReleaseResources"
  "-x" ":app:parseDebugLocalResources"
  "-x" ":app:generateDebugResources"
  "-x" ":app:generateReleaseResources")

echo "Running: ${GRADLE_CMD[*]}"
# Run and capture logs
if "${GRADLE_CMD[@]}" >"$LOG_DIR/gradle-output.log" 2>&1; then
  RC=0
else
  RC=$?
fi

echo "Gradle exit code: $RC"
echo "Logs saved to: $LOG_DIR/gradle-output.log"

# Also try to run the unit-test task directly (may still fail if R is required)
echo "Attempting :app:testDebugUnitTest (logs also saved)"
./gradlew :app:testDebugUnitTest --no-daemon --console=plain >"$LOG_DIR/testDebugUnitTest.log" 2>&1 || true

# Cleanup function to remove build artifacts and generated local.properties
cleanup() {
  echo "Cleaning up project build artifacts..."
  rm -rf "$REPO_ROOT/app/build" "$REPO_ROOT/build" "$REPO_ROOT/.gradle" || true
  if [ -f "$REPO_ROOT/local.properties" ]; then
    echo "Removing local.properties"
    rm -f "$REPO_ROOT/local.properties" || true
  fi
  echo "Cleanup complete."
}

trap cleanup EXIT

# Print summary
echo "Pre-AAPT2 test run complete. Logs: $LOG_DIR"
exit $RC
