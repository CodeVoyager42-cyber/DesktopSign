@echo off
rem ============================================================
rem  SignFlow desktop launcher (avoids Maven + AV fork issues)
rem  Usage: double-click, or run from a terminal:  run-app.cmd
rem  If the app is already running, this brings its window to
rem  the front instead of starting a second copy.
rem ============================================================
setlocal
set JAVA=C:\Users\Mnl\.jdks\openjdk-26.0.2\bin\java.exe
set JAR=%~dp0target\pdf-signer-desktop-0.1.0-SNAPSHOT.jar

if not exist "%JAR%" (
    echo The application jar was not found:
    echo   %JAR%
    echo Run:  mvnw.cmd package -DskipTests
    pause
    exit /b 1
)

rem Development launcher: the 'dev' profile enables the per-installation
rem mock signing token and plain HTTP to a local server. For a real token
rem set SIGNFLOW_DEV=0 plus SIGNATURE_PKCS11_ENABLED=true and PKCS11_LIBRARY.
rem Smart cards: SignFlow reads the card's certificates through the JDK's
rem PKCS#11 wrapper, which Java only opens up with this flag.
set JAVA_OPTS=--add-exports jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED --enable-native-access=ALL-UNNAMED
if /i "%SIGNATURE_PKCS11_ENABLED%"=="true" (
    echo Smart card mode: PKCS#11 driver %PKCS11_LIBRARY%
)
if not defined SIGNFLOW_DEV set SIGNFLOW_DEV=1
if "%SIGNFLOW_DEV%"=="1" (
    "%JAVA%" %JAVA_OPTS% -jar "%JAR%" --spring.profiles.active=dev
) else (
    "%JAVA%" %JAVA_OPTS% -jar "%JAR%"
)
endlocal
