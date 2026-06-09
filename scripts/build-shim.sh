#!/usr/bin/env bash
# Rebuilds app/libs/sourceversion-shim.jar from scripts/shim/.
#
# GraphHopper calls javax.lang.model.SourceVersion to validate encoded-value
# names, but that class (part of the JDK's java.compiler module) is absent on
# Android, so the first route() crashes with NoClassDefFoundError. We ship a
# tiny app-provided implementation. It can't live in app/src/main/java because
# javac refuses to compile a class into a package owned by a system module, so
# we precompile it here with `--limit-modules java.base` and package a jar that
# D8 dexes into the APK.
set -euo pipefail

cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
OUT="app/libs/sourceversion-shim.jar"

rm -rf build/shim-classes
mkdir -p build/shim-classes app/libs

"$JAVAC" --limit-modules java.base -d build/shim-classes \
    scripts/shim/javax/lang/model/SourceVersion.java
"$JAR" cf "$OUT" -C build/shim-classes .

echo "✓ Built $OUT"
"$JAR" tf "$OUT" | grep '\.class$'
