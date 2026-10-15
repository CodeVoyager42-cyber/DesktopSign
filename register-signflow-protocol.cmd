@echo off
rem ============================================================
rem  Registers the signflow:// URL protocol for the CURRENT
rem  Windows user (HKCU - no admin rights needed). After this,
rem  clicking an emailed signflow://invitation/... link opens
rem  the SignFlow desktop app with the invitation pre-loaded.
rem  If SignFlow is already running, the link is forwarded to
rem  the running window instead.
rem
rem  Undo at any time:  unregister-signflow-protocol.cmd
rem ============================================================
setlocal
set JAVA=C:\Users\Mnl\.jdks\openjdk-26.0.2\bin\java.exe
set JAR=%~dp0target\pdf-signer-desktop-0.1.0-SNAPSHOT.jar

if not exist "%JAR%" (
    echo WARNING: the application jar was not found:
    echo   %JAR%
    echo The protocol will still be registered; build the jar first with
    echo   mvnw.cmd package -DskipTests
    echo before clicking a signflow:// link.
)

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
