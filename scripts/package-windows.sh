#!/usr/bin/env bash
#
# Windows packaging with pinned, verified prerequisites.
#
# This wraps scripts/package.sh (the process that produced the working
# Windows release) and adds the pre-flight checks that script does NOT do
# itself, because they were discovered the hard way:
#
#   1. The packaging JDK must be OpenJDK 26.0.2 -- the exact release the
#      WiX template packaging/windows/main.wxs was authored against
#      (see main.wxs's own header). A JDK 21 build reaches light.exe and
#      dies with
#         error LGHT0094 : Unresolved reference to symbol
#         'WixComponentGroup:FragmentOsCondition'
#      and a 26.0.x that is not 26.0.2 trips the template-drift WARNING.
#      So the JDK is pinned here instead of letting scripts/package.sh
#      pick the newest jpackage on the machine -- which is how a JDK 21
#      got chosen on the developer machine in the first place.
#   2. The WiX Toolset must be 3.14.1 (the last v3.x release line). Both
#      candle.exe and light.exe are required and version-checked.
#   3. Everything the MSI step reads -- the WiX template, the icon source,
#      the output directory -- must be in place BEFORE the minutes-long
#      staging, jpackage and portable-zip phases run.
#
# Everything after these checks -- staging, jpackage, the MSI itself, the
# portable zip, the checksums and release-manifest.json -- stays in
# scripts/package.sh, so this file adds no packaging logic of its own and
# cannot drift away from the working process. Call package.sh directly only
# if you know why; this script exists so nobody has to rediscover the
# LGHT0094 story above by reading WiX error codes.
#
# Usage: the arguments are exactly scripts/package.sh's:
#   scripts/package-windows.sh --server-url https://signflow.example.com
#   scripts/package-windows.sh --local --server-url http://localhost:8080
#
# Optional overrides (otherwise the discovery below applies):
#   PACKAGING_JAVA_HOME  absolute path of the OpenJDK 26.0.2 to package with
#   WIX_BIN              directory holding candle.exe and light.exe
#
# Requires: Windows, Git Bash, OpenJDK 26.0.2 with jpackage, WiX 3.14.1.
# docs/windows-packaging.md documents where each prerequisite comes from
# and how to verify the finished installer.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "ERROR:" "$*" >&2; exit 1; }

# First line of "java -version", e.g.
#   openjdk version "26.0.2" 2026-01-20 LTS
java_version_line() { "$1" -version 2>&1 | head -n 1; }

# ---------------------------------------------------------------------------
# 1. The packaging JDK: OpenJDK 26.0.2, with jpackage. PACKAGING_JAVA_HOME
#    pins it; discovery otherwise scans the layout roots for a 26.0.2 only.
# ---------------------------------------------------------------------------

# Each candidate is either an exact directory or a glob root to expand.
# $JAVA_HOME comes first so a machine (or a CI setup-java step) whose
# environment already points at the pinned JDK gets it recognized: on
# GitHub's Windows runners setup-java installs into a hosted toolcache
# path that no glob below matches.
jdk_candidate_roots=(
    "${JAVA_HOME:-}"
    "$HOME/.jdks/openjdk-26.0.2"
    /c/Program\ Files/Java/openjdk-26.0.2*
    /c/Program\ Files/Eclipse\ Adoptium/openjdk-26.0.2*
    /c/Program\ Files/Eclipse\ Adoptium/jdk-26.0.2*
    /c/Program\ Files/Microsoft/jdk-26.0.2*
)

PACKAGING_JAVA_HOME=""
if [[ -n "${PACKAGING_JAVA_HOME:-}" ]]; then
    jdk_dirs=("$PACKAGING_JAVA_HOME")
else
    shopt -s nullglob
    jdk_dirs=()
    for root in "${jdk_candidate_roots[@]}"; do
        # shellcheck disable=SC2086 -- the roots above quote themselves.
        jdk_dirs+=("$root")
    done
    shopt -u nullglob
fi

for dir in "${jdk_dirs[@]}"; do
    [[ -d "$dir" && -x "$dir/bin/java.exe" ]] || continue
    java_version_line "$dir/bin/java.exe" | grep -q 'version "26\.0\.2' && {
        PACKAGING_JAVA_HOME="$dir"
        break
    }
    echo "  note: $dir is not OpenJDK 26.0.2 ($(java_version_line "$dir/bin/java.exe")) - skipped" >&2
done

[[ -n "$PACKAGING_JAVA_HOME" ]] || die "OpenJDK 26.0.2 was not found.

The MSI's WiX template (packaging/windows/main.wxs) was authored against
JDK 26.0.2's jpackage. Packaging with a different JDK breaks WiX link time:
on JDK 21 the failure is a LGHT0094 unresolved reference, and a 26.0.x other
than 26.0.2 trips the template drift warning. Do not substitute another
version -- install the real thing and either:

  * keep it in \$HOME/.jdks/openjdk-26.0.2 (an IDE's JDK manager writes to
    \$HOME/.jdks -- IntelliJ offers exactly this path), or
  * install under C:\\Program Files\\Java, \\Eclipse\\Adoptium or \\Microsoft
    with 26.0.2 in the directory name, or
  * point \$PACKAGING_JAVA_HOME at its root wherever it lives.

The build JDK that compiles the jar (Java 21 in CI, 21 on the dev machines)
is a SEPARATE role: scripts/package.sh compiles the jar with what maven
needs, and this script only pins the jpackage/jlink stage."
echo "== Packaging JDK: $PACKAGING_JAVA_HOME (OpenJDK 26.0.2 pinned) =="

export JAVA_HOME="$PACKAGING_JAVA_HOME"
[[ -x "$JAVA_HOME/bin/jpackage.exe" || -x "$JAVA_HOME/bin/jpackage" ]] \
    || die "jpackage is missing from \$JAVA_HOME/bin in $JAVA_HOME.

A JRE or an incomplete JDK stands where a full JDK is required. Install
OpenJDK 26.0.2 from https://jdk.java.net/26/ -- it ships jpackage."
echo "== jpackage: \$JAVA_HOME/bin verified =="

# ---------------------------------------------------------------------------
# 2. WiX Toolset 3.14.1: candle.exe AND light.exe, version-checked by name.
#    WIX_BIN pins it; discovery otherwise looks in the installed layout
#    roots and the portable layout documented in docs/windows-packaging.md.
# ---------------------------------------------------------------------------
wix_version_ok() { "$1" -? 2>&1 | grep -oq "version 3\.14\.1"; }

wix_candidate_roots=(
    /c/Program\ Files\ \(x86\)/WiX\ Toolset*/bin
    /c/Program\ Files/WiX\ Toolset*/bin
    "$HOME/tools/wix314"
)

if [[ -n "${WIX_BIN:-}" ]]; then
    wix_dirs=("$WIX_BIN")
else
    shopt -s nullglob
    wix_dirs=(); for root in "${wix_candidate_roots[@]}"; do wix_dirs+=("$root"); done
    shopt -u nullglob
fi

WIX_BIN=""
for dir in "${wix_dirs[@]}"; do
    [[ -d "$dir" && -x "$dir/candle.exe" && -x "$dir/light.exe" ]] || continue
    if wix_version_ok "$dir/candle.exe" && wix_version_ok "$dir/light.exe"; then
        WIX_BIN="$dir"
        break
    fi
    echo "  note: WiX at $dir is not 3.14.1" \
        "($( "$dir/candle.exe" -? 2>&1 | head -n 1 )) - skipped" >&2
done

[[ -n "$WIX_BIN" ]] || die "WiX Toolset 3.14.1 (candle.exe AND light.exe) was not found.

Paths searched: the 'WiX Toolset v3.x' directories under Program Files (the
32-bit Program Files first -- the installer puts it there) and the portable
layout \$HOME/tools/wix314, or point \$WIX_BIN at its bin directory.

Install WiX 3.14.1 one of two ways:
  installer  https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314.exe
  portable   https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip
             (unzip into \$HOME/tools/wix314 -- candle.exe sits at its top)

WiX v3 runs the MSI stage; WiX v4 is a different toolchain and is NOT
compatible with jpackage's template hook, so anything else is refused."
export PATH="$WIX_BIN:$PATH"
echo "== WiX: $WIX_BIN (3.14.1 verified) =="

# ---------------------------------------------------------------------------
# 3. Files the MSI stage reads, and the output directory it writes -- before
#    the minutes-long staging/jpackage/zip phases are spent.
# ---------------------------------------------------------------------------
main_wxs="packaging/windows/main.wxs"
main_wxs_base="packaging/windows/main.wxs.jdk-base"
icon_source="docs/icon-source.png"
icon_tool="tools/BuildIcon.java"

[[ -f "$main_wxs" ]] \
    || die "$main_wxs is missing. It carries the signflow:// protocol registration (HKCU, component-of-product, clean-uninstalled). It is tracked -- restore it from git; DO NOT drop it to 'simplify' the build: without it the invitation page's Review & Sign flow dies."
[[ -f "$main_wxs_base" ]] \
    || die "$main_wxs_base is missing. The template drift check compares the JDK's own jpackage template against it."
[[ -f "$icon_source" ]] \
    || die "$icon_source is missing. The installer icon is generated from it at build time by tools/BuildIcon.java."
[[ -f "$icon_tool" ]] \
    || die "$icon_tool is missing. The icon step of scripts/package.sh fails without it."

grep -q 'SourceFile="msica.dll"' "$main_wxs" \
    || die "$main_wxs does not look like the JDK 26.0.2 template (no msica.dll custom-action binary). The template must track main.wxs.jdk-base -- see the drift warning scripts/package.sh prints."

mkdir -p build/dist 2>/dev/null \
    || die "build/dist could not be created -- the working directory must be writable."

# The coordination-server address has its own dedicated validation inside
# scripts/package.sh (with its own message); passing nothing here to it
# would fail there. Nothing is added to that message.

# ---------------------------------------------------------------------------
# 4. Run the working packaging process, then verify what it owes us.
# ---------------------------------------------------------------------------
./scripts/package.sh "$@"
rc=$?

# scripts/package.sh already writes one .sha256 per artefact and the
# release-manifest.json. This block only PROVES the promised outputs exist
# and agree, so a half-written artifact cannot look like a success.
shopt -s nullglob
mis=(build/dist/*.msi)
zis=(build/dist/*-windows.zip)
shopt -u nullglob
(( ${#mis[@]} >= 1 )) || die "packaging exited $rc but no MSI was produced in build/dist."
(( ${#zis[@]} >= 1 )) || die "packaging exited $rc but no portable zip was produced in build/dist."
for artefact in "${mis[@]}" "${zis[@]}"; do
    [[ -f "$artefact.sha256" ]] \
        || die "the .sha256 file is missing for $artefact."
    (cd build/dist && sha256sum -c "$(basename "$artefact").sha256" > /dev/null 2>&1) \
        || die "checksum mismatch for $artefact -- the artefact and its .sha256 file disagree. Rebuild; do not hand-edit the .sha256 to force a pass."
    echo "  verified: $(basename "$artefact") and its checksum agree"
done

echo "== Done: MSI + zip in build/dist, checksums verified =="
exit "$rc"
