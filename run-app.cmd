@echo off
rem ============================================================
rem  SignFlow desktop launcher (avoids Maven + AV fork issues)
rem  Usage: double-click, or run from a terminal:  run-app.cmd
rem  If the app is already running, this brings its window to
rem  the front instead of starting a second copy.
rem ============================================================
setlocal
rem Pick a java.exe: respect JAVA_HOME/JAVA if the caller set one, otherwise
rem fall back to whatever "java" resolves to on PATH (no machine-specific
rem path baked in here - that broke on any machine other than the one it
rem was written on).
set JAVA=java
if defined JAVA_HOME set JAVA=%JAVA_HOME%\bin\java.exe
set JAR=%~dp0target\pdf-signer-desktop-0.1.0-SNAPSHOT.jar

rem NOTE: deliberately avoiding "if ... ( ... )" blocks below - when this
rem project lives in a folder whose name contains parentheses (e.g. a
rem browser-downloaded "DesktopSign-master (1)"), %JAR% expands to a path
rem containing "(" / ")" and breaks cmd's block parser. goto keeps this
rem robust regardless of the folder name.
if exist "%JAR%" goto haveJar
echo The application jar was not found:
echo   %JAR%
echo Run:  mvnw.cmd package -DskipTests
pause
exit /b 1
:haveJar

rem Development launcher: the 'dev' profile enables the per-installation
rem mock signing token and plain HTTP to a local server. For a real token
rem set SIGNFLOW_DEV=0 plus SIGNATURE_PKCS11_ENABLED=true and PKCS11_LIBRARY.
rem Smart cards: SignFlow reads the card's certificates through the JDK's
rem PKCS#11 wrapper, which Java only opens up with this flag.
set JAVA_OPTS=--add-exports jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED --enable-native-access=ALL-UNNAMED
if /i not "%SIGNATURE_PKCS11_ENABLED%"=="true" goto notPkcs11
echo Smart card mode: PKCS#11 driver %PKCS11_LIBRARY%
:notPkcs11
if not defined SIGNFLOW_DEV set SIGNFLOW_DEV=1
if not "%SIGNFLOW_DEV%"=="1" goto runProd
"%JAVA%" %JAVA_OPTS% -jar "%JAR%" --spring.profiles.active=dev
goto afterRun
:runProd
"%JAVA%" %JAVA_OPTS% -jar "%JAR%"
:afterRun

echo.
echo The application exited. Scroll up for any error, then press a key to close.
pause >nul
endlocal
