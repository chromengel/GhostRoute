#!/usr/bin/env bash
# GhostRoute Phase 0 hard rule (plan §3):
#   NO Google Play Services, NO Firebase, NO phone-home analytics — ever.
#
# This is the standalone/CI entry point. The authoritative check lives in the
# Gradle build (`:app:verifyNoGms`, wired into preBuild) so it cannot be skipped;
# this script runs it and additionally prints a human-readable scan of the
# resolved dependency tree for the audit log.
set -euo pipefail

cd "$(dirname "$0")/.."

BANNED_REGEX='com\.google\.android\.gms|com\.google\.firebase|com\.google\.android\.play|com\.crashlytics|io\.fabric|com\.google\.android\.datatransport'

echo "==> Running authoritative Gradle audit task (:app:verifyNoGms)"
./gradlew --no-daemon :app:verifyNoGms

echo
echo "==> Scanning resolved runtime dependency tree for banned coordinates"
TREE="$(./gradlew --no-daemon :app:dependencies --configuration releaseRuntimeClasspath 2>/dev/null || true)"

if echo "$TREE" | grep -Eq "$BANNED_REGEX"; then
    echo "DEPENDENCY AUDIT FAILED — banned coordinates found:" >&2
    echo "$TREE" | grep -E "$BANNED_REGEX" >&2
    exit 1
fi

echo "✓ Clean: no GMS / Firebase / analytics on the runtime classpath."
