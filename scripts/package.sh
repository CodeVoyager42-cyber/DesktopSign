#!/usr/bin/env bash
#
# Package SignFlow as a self-contained desktop application.
#
# Produces:
#   build/dist/SignFlow-<version>.msi              the installer — the Windows
#                                                  deliverable (needs WiX 3.x)
#   build/dist/SignFlow-<version>.msi.sha256       its checksum
#   build/dist/SignFlow-<version>-windows.zip      portable build, no installer
#   build/dist/SignFlow-<version>-windows.zip.sha256
#   build/jpackage/SignFlow/                       raw app-image (SignFlow.exe)
#
# The MSI is the thing to publish: it is a per-user install (no administrator
# rights), it registers the `signflow://` scheme at the installed executable
# so a link on the website can open this copy, and its uninstaller removes
# that registration again. See packaging/windows/main.wxs for why the
# installer does that rather than the application at first run.
#
# The portable zip stays for anyone who cannot run an installer. It registers
# nothing, so `signflow://` links will not reach it.
#
# THE MSI IS NOT CODE-SIGNED. Windows SmartScreen will warn about it until a
# code-signing certificate is bought and wired in here. Do not try to work
# around the warning; document it (docs/desktop-download.md) and let the
# reader click through "More info → Run anyway".
#
# Usage:
#   scripts/package.sh                build the Spring Boot jar, then package
#   scripts/package.sh --skip-build   reuse an existing target/*.jar
#
# Requires: JDK 21+ with jpackage (JAVA_HOME or on PATH). WiX 3.x for the MSI.
set -euo pipefail
cd "$(dirname "$0")/.."   # run from the project root

APP_NAME=SignFlow
# The version comes from the built jar (the pom's <version>), so a bump in
# pom.xml flows through without editing this script.

# The MSI's UpgradeCode. It must NEVER change: Windows uses it to recognise
# that a new MSI is an upgrade of the installed product rather than a second
# copy of it. Changing this would leave users with two SignFlows.
UPGRADE_UUID="61FE853E-0C89-44B7-850C-46E57F4E3309"

if [[ "${1:-}" != "--skip-build" ]]; then
    echo "== Building Spring Boot jar =="
    ./mvnw -B -q -DskipTests package
fi

# Pick the newest built jar (any version, no hardcoded 0.1.0 here):
shopt -s nullglob
jars=(target/pdf-signer-desktop-*.jar)
shopt -u nullglob
if (( ${#jars[@]} == 0 )); then
    echo "ERROR: no target/pdf-signer-desktop-*.jar. Run without --skip-build." >&2
    exit 1
fi
JAR="${jars[0]}"
for j in "${jars[@]}"; do
    if [[ "$j" -nt "$JAR" ]]; then JAR="$j"; fi
done
APP_VERSION="$(basename "$JAR" .jar)"
APP_VERSION="${APP_VERSION#pdf-signer-desktop-}"
if [[ -z "$APP_VERSION" ]]; then
    echo "ERROR: could not read the version from the jar name '$JAR'." >&2
    exit 1
fi
# jpackage validates --app-version as a numeric dotted version (it refuses
# suffixes), so "0.1.0-SNAPSHOT" is cut to "0.1.0" for the Windows product
# version. Full-length artefact names keep ... -SNAPSHOT too - use
# INSTALL_VERSION (the number part) for the published file names instead.
INSTALL_VERSION="${APP_VERSION%%-*}"
echo "== Version $APP_VERSION (installer product version $INSTALL_VERSION) =="

if [[ -z "${JAVA_HOME:-}" ]]; then
    # `command -v java` is NOT a reliable JDK root: the Oracle updater's
    # "Common Files\Oracle\Java\javapath" directory holds java.exe but no
    # jpackage, and a build JDK can live in none of those well-known roots.
    # So: prefer a jpackage found on PATH, then the standard install roots
    # (the same roots scripts\dev-runtime.cmd scans for the launchers),
    # newest version wins. jpackage below still verifies the result.
    if JPKG_BIN="$(command -v jpackage 2>/dev/null)"; then
        JAVA_HOME="$(cd "$(dirname "$JPKG_BIN")/.." && pwd)"
    else
        shopt -s nullglob
        with_jpackage=()
        for d in "/c/Program Files/Java/"jdk* "/c/Program Files/Eclipse Adoptium/"jdk* \
                 "/c/Program Files/Microsoft/"jdk* "/c/Amazon Corretto/"jdk*; do
            if [[ -x "$d/bin/jpackage.exe" || -x "$d/bin/jpackage" ]]; then
                with_jpackage+=("$d")
            fi
        done
        shopt -u nullglob
        if (( ${#with_jpackage[@]} > 0 )); then
            JAVA_HOME="$(printf '%s\n' "${with_jpackage[@]}" | sort -V | tail -n 1)"
        fi
    fi
fi
JPACKAGE="$JAVA_HOME/bin/jpackage"
[[ -x "$JPACKAGE" || -x "$JPACKAGE.exe" ]] || { echo "ERROR: jpackage not found in \$JAVA_HOME/bin (set JAVA_HOME to a JDK 21+ with jpackage)" >&2; exit 1; }

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
# signflow.packaged: an installed build can never run the 'dev' profile
# (mock token, default accounts, plain HTTP) — ProductionSafetyValidator.
# WINDOWS-ROOT: trust the coordination server's TLS certificate through the
# Windows certificate store (see docs/TRANSPORT-SECURITY.md).
JAVA_OPTS=(
    "--add-modules=jdk.crypto.cryptoki"
    "--enable-native-access=ALL-UNNAMED"
    "-Dsignflow.packaged=true"
    # The packaged desktop always wants the real token: end users install
    # the MSI (or run the portable zip) and plug in their eBarid Sign token
    # with no configuration. The Gemalto/eBarid driver is auto-detected in
    # the standard Windows location (see signature.pkcs11.auto-detect-dirs),
    # so both ends of the check - enabled AND library - hold by themselves.
    # The coordination server, which shares this artifact but is never
    # packaged, runs from source without these options.
    "-Dsignature.pkcs11.enabled=true"
    "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
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

# ---------------------------------------------------------------------------
# Is our WiX template still the JDK's template plus our additions?
#
# packaging/windows/main.wxs REPLACES the one inside jdk.jpackage, because
# jpackage's overrides.wxi hook can only set WiX variables and cannot add a
# registry component. That means a JDK upgrade can change the template under
# us, and the only symptom would be an installer built from a stale copy —
# so the drift is checked here instead of discovered later.
# ---------------------------------------------------------------------------
echo "== WiX template drift check =="
JMOD="$JAVA_HOME/jmods/jdk.jpackage.jmod"
TEMPLATE_IN_JDK="classes/jdk/jpackage/internal/resources/main.wxs"
if [[ -f "$JMOD" ]]; then
    rm -rf build/jdk-wxs && mkdir -p build/jdk-wxs
    (cd build/jdk-wxs && "$JAVA_HOME/bin/jar" --extract --file "$JMOD" "$TEMPLATE_IN_JDK" 2>/dev/null) || true
    if [[ -f "build/jdk-wxs/$TEMPLATE_IN_JDK" ]]; then
        if diff -q "build/jdk-wxs/$TEMPLATE_IN_JDK" packaging/windows/main.wxs.jdk-base >/dev/null 2>&1; then
            echo "  ok — this JDK's main.wxs matches packaging/windows/main.wxs.jdk-base"
        else
            echo "  !! WARNING: this JDK ships a DIFFERENT main.wxs than the one" >&2
            echo "     packaging/windows/main.wxs was derived from." >&2
            echo "     Re-diff it:" >&2
            echo "       diff build/jdk-wxs/$TEMPLATE_IN_JDK packaging/windows/main.wxs.jdk-base" >&2
            echo "     then port the changes into packaging/windows/main.wxs and" >&2
            echo "     refresh the base copy. Continuing with our template." >&2
        fi
    else
        echo "  (could not extract the JDK's template; skipping the check)"
    fi
else
    echo "  (no jmods in this JDK; skipping the check)"
fi

echo "== jpackage app-image =="
"$JPACKAGE" \
    --type app-image \
    --name "$APP_NAME" \
    --app-version "$INSTALL_VERSION" \
    --vendor "SignFlow" \
    --add-modules "$JLINK_MODULES" \
    --input build/jpkg-input \
    --main-jar "$(basename "$JAR")" \
    --icon build/SignFlow.ico \
    $(for opt in "${JAVA_OPTS[@]}"; do echo --java-options "\"$opt\""; done) \
    --dest build/jpackage

echo "== Portable zip =="
"$JAVA_HOME/bin/jar" --create --file "build/dist/${APP_NAME}-${INSTALL_VERSION}-windows.zip" -C build/jpackage "$APP_NAME"

echo "== MSI (needs WiX 3.x) =="
# Find candle.exe: on PATH, or in a Chocolatey/standard install dir. The WiX
# toolset installs to "WiX Toolset v3.14" (versioned dir), so a fixed path
# check misses it (verified on a GitHub windows-latest runner).
if ! command -v candle >/dev/null 2>&1; then
    for d in "/c/Program Files (x86)/WiX Toolset"*/bin "/c/Program Files/WiX Toolset"*/bin; do
        if [[ -f "$d/candle.exe" ]]; then export PATH="$d:$PATH"; break; fi
    done
fi
if command -v candle >/dev/null 2>&1; then
    # --win-per-user-install: installs under the user's own AppData, so no
    #   administrator rights are needed and the `signflow://` keys land in
    #   HKCU, which the user already owns.
    # --resource-dir: our main.wxs, which adds the scheme registration.
    # --win-upgrade-uuid: fixed, so a later MSI upgrades rather than
    #   installing a second copy.
    "$JPACKAGE" \
        --type msi \
        --name "$APP_NAME" \
        --app-version "$INSTALL_VERSION" \
        --vendor "SignFlow" \
        --description "Sign PDF documents with the certificate on your smart card" \
        --add-modules "$JLINK_MODULES" \
        --input build/jpkg-input \
        --main-jar "$(basename "$JAR")" \
        --icon build/SignFlow.ico \
        --resource-dir packaging/windows \
        --win-per-user-install \
        --win-menu \
        --win-menu-group "SignFlow" \
        --win-shortcut \
        --win-upgrade-uuid "$UPGRADE_UUID" \
        $(for opt in "${JAVA_OPTS[@]}"; do echo --java-options "\"$opt\""; done) \
        --dest build/dist
    echo "MSI built."
else
    echo "WiX not found (candle.exe) — MSI SKIPPED." >&2
    echo "The MSI is the Windows deliverable: it is what registers signflow://" >&2
    echo "and what the website offers for download. Install WiX 3.x and re-run." >&2
    echo "The portable zip and app-image are ready in build/dist." >&2
fi

# ---------------------------------------------------------------------------
# Checksums, so somebody who downloaded the file can tell whether they got
# the file. One per artefact, in the format `sha256sum` writes and PowerShell
# can be compared against:
#   Get-FileHash .\SignFlow-<version>.msi -Algorithm SHA256
# ---------------------------------------------------------------------------
echo "== Checksums =="
shopt -s nullglob
for artefact in build/dist/*.msi build/dist/*.zip; do
    base="$(basename "$artefact")"
    if command -v sha256sum >/dev/null 2>&1; then
        (cd build/dist && sha256sum "$base" > "$base.sha256")
    else
        # Git Bash always has one of these; certutil is the fallback.
        hash="$(certutil -hashfile "$(cygpath -w "$artefact")" SHA256 | sed -n '2p' | tr -d ' \r')"
        printf '%s  %s\n' "$hash" "$base" > "$artefact.sha256"
    fi
    echo "  $(cat "$artefact.sha256")"
done
shopt -u nullglob

echo "== Done =="
ls -la build/dist/
