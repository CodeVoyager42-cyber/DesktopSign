@echo off
rem ============================================================
rem  DEV-ONLY: registers the signflow:// URL protocol against a
rem  jar built from this source checkout, for testing deep links
rem  without packaging the app. Packaged installs (built with
rem  package.sh) register themselves automatically on first run
rem  (see ProtocolRegistration.java) - coworkers never need this
rem  script.
rem
rem  Registers for the CURRENT Windows user (HKCU - no admin
rem  rights needed). After this, clicking an emailed
rem  signflow://invitation/... link opens this checkout's jar
rem  with the invitation pre-loaded. If SignFlow is already
rem  running, the link is forwarded to the running window instead.
rem
rem  Undo at any time:  unregister-signflow-protocol.cmd
rem ============================================================
setlocal
rem Respect JAVA_HOME if set, otherwise fall back to "java" on PATH - no
rem machine-specific path baked in here.
set JAVA=java
if defined JAVA_HOME set JAVA=%JAVA_HOME%\bin\java.exe
set JAR=%~dp0target\pdf-signer-desktop-0.1.0-SNAPSHOT.jar

rem NOTE: deliberately avoiding "if ... ( ... )" blocks below - see
rem run-app.cmd for why (a folder name containing parentheses breaks
rem cmd's block parser once %JAR% is substituted in).
if exist "%JAR%" goto haveJar
echo WARNING: the application jar was not found:
echo   %JAR%
echo The protocol will still be registered; build the jar first with
echo   mvnw.cmd package -DskipTests
echo before clicking a signflow:// link.
:haveJar

reg add "HKCU\Software\Classes\signflow" /ve /d "url:SignFlow Invitation" /f >nul
if errorlevel 1 goto :failed
reg add "HKCU\Software\Classes\signflow" /v "URL Protocol" /d "" /f >nul
reg add "HKCU\Software\Classes\signflow\DefaultIcon" /ve /d "\"%JAVA%\",0" /f >nul
rem NOTE: %%1 (doubled) so the LITERAL %1 is stored in the registry -
rem a single %1 would expand to this script's own first argument.
reg add "HKCU\Software\Classes\signflow\shell\open\command" /ve /d "\"%JAVA%\" -jar \"%JAR%\" \"%%1\"" /f >nul

echo.
echo Registered signflow:// for the current user.
echo Handler: "%JAVA%" -jar "%JAR%" "link"
echo.
echo Try it:  press Win+R and run   signflow://invitation/test
exit /b 0

:failed
echo Failed to write the registry keys under HKCU\Software\Classes\signflow.
exit /b 1
