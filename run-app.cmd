@echo off
rem ============================================================
rem  SignFlow desktop launcher (avoids Maven + AV fork issues)
rem  Usage: double-click, or run from a terminal:  run-app.cmd
rem  If the app is already running, this brings its window to
rem  the front instead of starting a second copy.
rem ============================================================
setlocal
rem Java + jar discovery lives in one shared place (JAVA_HOME, PATH,
rem standard install roots) - see scripts\dev-runtime.cmd. The version
rem the build targets is 21+; set JAVA_HOME to pin a specific JDK.
call "%~dp0scripts\dev-runtime.cmd" "%~dp0"
if errorlevel 1 goto runtimeFailed

rem NOTE: deliberately avoiding "if ... ( ... )" blocks wherever a path is
rem substituted in - when this project lives in a folder whose name contains
rem parentheses (e.g. a browser-downloaded "DesktopSign-master (1)"), or when
rem PKCS11_LIBRARY points under "C:\Program Files (x86)\...", the expansion
rem contains "(" / ")" and breaks cmd's block parser. goto keeps this robust
rem regardless of the folder or driver path.
if not "%APP_JAR%"=="" goto haveJar
echo The application jar was not found under "%~dp0target".
echo Run:  mvnw.cmd package -DskipTests
echo.
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
"%JAVA_EXE%" %JAVA_OPTS% -jar "%APP_JAR%" --spring.profiles.active=dev
goto afterRun
:runProd
"%JAVA_EXE%" %JAVA_OPTS% -jar "%APP_JAR%"
:afterRun

echo.
echo The application exited. Scroll up for any error, then press a key to close.
pause >nul
endlocal
exit /b 0

:runtimeFailed
echo.
pause
exit /b 1
