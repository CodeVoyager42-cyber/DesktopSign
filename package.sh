#!/usr/bin/env bash
#
# Package SignFlow as a self-contained desktop application.
#
# Produces:
#   build/dist/SignFlow-<version>-windows.zip   portable build (app-image + bundled JRE)
#   build/dist/*.msi                            installer — only when WiX 3.x is installed
#   build/jpackage/SignFlow/                    raw app-image (SignFlow.exe inside)
#
# Usage:
#   ./package.sh                build the Spring Boot jar, then package
#   ./package.sh --skip-build   reuse an existing target/*.jar
#
# Requires: JDK 21+ with jpackage (JAVA_HOME or on PATH). WiX 3.x for MSI.
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME=SignFlow
APP_VERSION=0.1.0
JAR="target/pdf-signer-desktop-${APP_VERSION}-SNAPSHOT.jar"

if [[ "${1:-}" != "--skip-build" ]]; then
    echo "== Building Spring Boot jar =="
    ./mvnw -B -q -DskipTests package
fi
if [[ ! -f "$JAR" ]]; then
    echo "ERROR: $JAR not found. Run without --skip-build." >&2
    exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(cd "$(dirname "$(command -v java)")/.." && pwd)"
fi
JPACKAGE="$JAVA_HOME/bin/jpackage"
[[ -x "$JPACKAGE" || -x "$JPACKAGE.exe" ]] || { echo "ERROR: jpackage not found in \$JAVA_HOME/bin" >&2; exit 1; }

# Modules for the bundled jlink runtime. NOTE: jpackage's --add-modules
# REPLACES its default root set (verified empirically: listing only
# jdk.crypto.cryptoki produced a runtime without java.desktop, which Spring's
# java.beans usage needs), so the full required set is spelled out here:
#   java.se               all SE API (java.desktop for Spring/JavaFX, java.sql, …)
#   jdk.unsupported       sun.misc.Unsafe (JavaFX marlin renderer)
#   jdk.crypto.cryptoki   SunPKCS11 — raw wrapper access for token signing
#   jdk.crypto.ec         SunEC — ECDSA (mock EC key, EC certificates)
#   jdk.charsets, jdk.localedata, jdk.zipfs — extended charsets, locales, jar fs
JLINK_MODULES="java.se,jdk.unsupported,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs"

# --sun-misc-unsafe-memory-access exists only on JDK 23+; passing it to a
# JDK 21 runtime would make every launch fail with an unrecognized option.
JAVA_OPTS=(
    "--add-modules=jdk.crypto.cryptoki"
    "--enable-native-access=ALL-UNNAMED"
)
JAVA_MAJOR="$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2; exit}')"
if [[ "${JAVA_MAJOR%%.*}" -ge 23 ]]; then
    JAVA_OPTS+=("--sun-misc-unsafe-memory-access=allow")
fi

# jpackage copies everything from --input next to the main jar; the Boot fat
# jar is self-contained, so stage it alone.
echo "== Staging =="
rm -rf build/jpackage build/dist build/jpkg-input
mkdir -p build/jpkg-input build/dist
cp "$JAR" build/jpkg-input/

echo "== Icons =="
# Regenerates src/main/resources/ui/app-icon.png + build/SignFlow.ico from
# docs/icon-source.png (no-op content-wise when the source is unchanged).
"$JAVA_HOME/bin/java" tools/BuildIcon.java

echo "== jpackage app-image =="
"$JPACKAGE" \
    --type app-image \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "SignFlow" \
    --add-modules "$JLINK_MODULES" \
    --input build/jpkg-input \
    --main-jar "$(basename "$JAR")" \
    --icon build/SignFlow.ico \
    $(for opt in "${JAVA_OPTS[@]}"; do echo --java-options "\"$opt\""; done) \
    --dest build/jpackage

echo "== Portable zip =="
"$JAVA_HOME/bin/jar" --create --file "build/dist/${APP_NAME}-${APP_VERSION}-windows.zip" -C build/jpackage "$APP_NAME"

echo "== MSI (optional, needs WiX 3.x) =="
# Find candle.exe: on PATH, or in a Chocolatey/standard install dir. The WiX
# toolset installs to "WiX Toolset v3.14" (versioned dir), so a fixed path
# check misses it (verified on a GitHub windows-latest runner).
if ! command -v candle >/dev/null 2>&1; then
    for d in "/c/Program Files (x86)/WiX Toolset"*/bin "/c/Program Files/WiX Toolset"*/bin; do
        if [[ -f "$d/candle.exe" ]]; then export PATH="$d:$PATH"; break; fi
    done
fi
if command -v candle >/dev/null 2>&1; then
    "$JPACKAGE" \
        --type msi \
        --name "$APP_NAME" \
        --app-version "$APP_VERSION" \
        --vendor "SignFlow" \
        --add-modules "$JLINK_MODULES" \
        --input build/jpkg-input \
        --main-jar "$(basename "$JAR")" \
        --icon build/SignFlow.ico \
        $(for opt in "${JAVA_OPTS[@]}"; do echo --java-options "\"$opt\""; done) \
        --dest build/dist
    echo "MSI built."
else
    echo "WiX not found (candle.exe) — MSI skipped. Zip + app-image are ready in build/dist."
fi

echo "== Done =="
ls -la build/dist/
