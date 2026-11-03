@echo off
rem ============================================================
rem  Development runtime discovery, shared by the launchers:
rem    run-app.cmd, run-server.cmd, register-signflow-protocol.cmd
rem
rem  Usage:  call "<root>\scripts\dev-runtime.cmd" "<repo-root>"
rem
rem  Sets after it returns:
rem    JAVA_EXE   absolute path to a java.exe of JDK 21+ (the version
rem               the build targets, see pom.xml maven.compiler.source).
rem               Discovery order - never a hardcoded per-machine path:
rem                 1. %JAVA_HOME%  (set it to pin a JDK)
rem                 2. java.exe on PATH (falls back further when too old)
rem                 3. well-known system install roots (newest match wins)
rem               Exits 1 with an actionable message when none is found.
rem    APP_JAR    absolute path of the NEWEST target\pdf-signer-desktop-*.jar
rem               under the root, or EMPTY when none was built. Not an
rem               error by itself: the callers decide what applies.
rem
rem  Only the DEVELOPMENT launchers need a JDK. The installed application
rem  carries its own runtime, bundled by jpackage in scripts/package.sh
rem  (see docs/desktop-download.md), so this file never has to guess at a
rem  per-installation path.
rem ============================================================
setlocal EnableExtensions
set "JAVA_EXE="
set "APP_JAR="
set "RC=0"
set "ROOT="

rem ---- resolve the root argument (arg 1) to a clean absolute path ----
if not "%~1"=="" for %%I in ("%~1") do set "ROOT=%%~fI"
if not defined ROOT call :noRoot

rem ---- the application jar: newest target\pdf-signer-desktop-*.jar ----
rem dir /b /o-d lists newest first, so the first hit is the newest jar.
rem The *.jar wildcard is what keeps the launchers working when the pom
rem version changes - a hardcoded 0.1.0-SNAPSHOT would not.
if defined ROOT call :findJar

rem ---- the Java runtime ----
call :findJava
if "%RC%"=="1" goto :finish

:finish
endlocal & set "APP_JAR=%APP_JAR%" & set "JAVA_EXE=%JAVA_EXE%" & exit /b %RC%

rem ---- helpers -------------------------------------------------------

:noRoot
echo dev-runtime.cmd: no repository root was passed to it.
set "APP_JAR="
set "JAVA_EXE="
exit /b 0

:findJar
if not exist "%ROOT%\target\" exit /b 0
for /f "delims=" %%f in ('dir /b /o-d "%ROOT%\target\pdf-signer-desktop-*.jar" 2^>nul') do (
    if not defined APP_JAR set "APP_JAR=%ROOT%\target\%%f"
)
exit /b 0

:findJava
rem 1) JAVA_HOME, when it points at a real JDK.
rem    It is the user's explicit pin, so a wrongly set value earns a
rem    note, but the search continues rather than hard-failing.
if not defined JAVA_HOME goto :fromPath
if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE goto :javaHomeUnusable
call :checkJava 0
if "%RC%"=="0" exit /b 0
rem The pin exists but did not pass the 21+ check (checkJava printed why
rem and cleared JAVA_EXE). Do what the header promises - keep searching
rem the standard install roots rather than hard-failing on a bad pin.
goto :fromRoots
:javaHomeUnusable
echo Note: JAVA_HOME is set to "%JAVA_HOME%", but it holds no bin\java.exe.
echo Searching the standard locations instead ...
goto :fromRoots

:fromPath
rem 2) java.exe on PATH.
where java.exe >nul 2>&1
if errorlevel 1 goto :fromRoots
for /f "delims=" %%e in ('where java.exe') do (
    if not defined JAVA_EXE if exist "%%e" set "JAVA_EXE=%%e"
)
if not defined JAVA_EXE goto :fromRoots
call :checkJava 1
if "%RC%"=="0" exit /b 0
rem A PATH java can be old (some tools install Java 8 there); a too-old
rem or unusable one only earns a fallback attempt at the standard roots.
echo Trying the standard install locations ...
set "JAVA_EXE="

:fromRoots
rem 3) well-known system install roots (newest match wins). Wildcards,
rem    never absolute per-machine paths.
call :bestFromRoot "%ProgramFiles%\Java\jdk*"
call :bestFromRoot "%ProgramFiles%\Eclipse Adoptium\jdk*"
call :bestFromRoot "%ProgramFiles%\Microsoft\jdk*"
if defined JAVA_EXE (
    rem A rejected java on PATH may have left RC=1 behind; the standard
    rem install roots just supplied a working java.exe, so report success.
    set "RC=0"
    exit /b 0
)

echo Java (JDK 21+) was not found. The launchers need it to run the
echo application from source - the installed application does not, it
echo carries its own runtime. Do one of:
echo   * set JAVA_HOME to a JDK 21+ directory, or
echo   * put its bin directory on PATH, or
echo   * install a JDK under C:\Program Files
echo     (Java, Eclipse Adoptium or Microsoft).
set "RC=1"
exit /b 0

rem :checkJava <mode: 0 = JAVA_HOME pin, 1 = opportunistically found>
rem   Validates %JAVA_EXE% ("did it report something?" and "is it 21+?").
rem   Sets RC=0 when good. When mode=1, an unusable java just clears
rem   JAVA_EXE so candidate roots can take over; mode=0 (the user's pin)
rem   also clears it - the search continues, the note above explains.
rem   Exit code: 0 if JAVA_EXE survived, 1 when it was cleared.
:checkJava
call :majorOf "%JAVA_EXE%" VER_MAJOR
if not defined VER_MAJOR (
    echo "%JAVA_EXE%" did not report a parseable version. Is it a real java.exe?
    set "JAVA_EXE="
    set "RC=1"
    exit /b 1
)
if %VER_MAJOR% geq 21 exit /b 0
echo "%JAVA_EXE%" is Java %VER_MAJOR%, but SignFlow needs Java 21 or newer.
set "JAVA_EXE="
set "RC=1"
exit /b 1

rem :bestFromRoot <dir-spec-with-wildcard>
rem   Keeps the candidate with the highest JDK major version seen so far.
:bestFromRoot
rem The pattern is re-quoted here: %~1 arrives unquoted (the ~ strips the
rem caller's quotes) and an unquoted "Program Files" pattern is split at
rem the space, so it matches nothing. re-quoting makes it a wildcard again.
for /d %%d in ("%~1") do call :consider "%%d\bin\java.exe"
exit /b 0

rem :consider <candidate java.exe>
:consider
if not exist "%~1" exit /b 0
call :majorOf "%~1" CAND_MAJOR
if not defined CAND_MAJOR exit /b 0
if %CAND_MAJOR% lss 21 exit /b 0
if defined BEST_MAJOR if %CAND_MAJOR% leq %BEST_MAJOR% exit /b 0
set "BEST_MAJOR=%CAND_MAJOR%"
set "JAVA_EXE=%~1"
exit /b 0

rem :majorOf <java.exe> <outVar>
rem   Parses the major version from "java -version" (JEP 223 form:
rem   java version "26.0.1" / openjdk version "17.0.10").
rem   The output goes to a temp file because a quoted executable path at
rem   the start of a for /f command string is mangled by cmd's quoting,
rem   and the digit check reads a file too (a pipe leaves a trailing
rem   space that would break the ^...$ regex).
rem   Exit code: 0 and %2=major when parsed; 1 and %2 untouched otherwise.
:majorOf
set "MAJOR="
set "%~2="
set "VMJV=%TEMP%\signflow-java-ver-%RANDOM%.tmp"
set "VMJN=%TEMP%\signflow-java-num-%RANDOM%.tmp"
"%~1" -version > "%VMJV%" 2>&1
for /f "tokens=3" %%v in ('findstr /i "version" "%VMJV%"') do (
    if not defined MAJOR set "MAJOR=%%~v"
)
del "%VMJV%" 2>nul
if not defined MAJOR exit /b 1
for /f "tokens=1 delims=._" %%m in ("%MAJOR%") do set "MAJOR=%%m"
(echo %MAJOR%) > "%VMJN%"
findstr /r "^ *[0-9][0-9]* *$" "%VMJN%" >nul 2>&1
set "GOOD=%errorlevel%"
del "%VMJN%" 2>nul
if not "%GOOD%"=="0" exit /b 1
set "%~2=%MAJOR%"
exit /b 0
