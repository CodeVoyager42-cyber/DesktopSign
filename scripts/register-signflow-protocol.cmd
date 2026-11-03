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
rem Java + jar discovery lives in one shared place (JAVA_HOME, PATH,
rem standard install roots) - see dev-runtime.cmd in this folder. The
rem version the build targets is 21+; set JAVA_HOME to pin a JDK.
call "%~dp0dev-runtime.cmd" "%~dp0.."
rem Smart cards: SignFlow reads the card's certificates through the JDK's
rem PKCS#11 wrapper, which Java only opens up with these flags (the same ones
rem run-app.cmd passes). A link-launched copy that becomes the FIRST instance
rem needs them to use a real card; one that only hands the link over does not.
set JAVA_OPTS=--add-exports jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED --enable-native-access=ALL-UNNAMED

if "%APP_JAR%"=="" (
    echo WARNING: the application jar was not found under the project's
    echo   target folder.
    echo The protocol will still be registered; build the jar first with
    echo   mvnw.cmd package -DskipTests
    echo before clicking a signflow:// link.
)

reg add "HKCU\Software\Classes\signflow" /ve /d "url:SignFlow Invitation" /f >nul
if errorlevel 1 goto :failed
reg add "HKCU\Software\Classes\signflow" /v "URL Protocol" /d "" /f >nul
reg add "HKCU\Software\Classes\signflow\DefaultIcon" /ve /d "\"%JAVA_EXE%\",0" /f >nul
rem NOTE: %%1 (doubled) so the LITERAL %1 is stored in the registry -
rem a single %1 would expand to this script's own first argument.
rem This is a DEVELOPMENT script, so the handler starts the app in the 'dev'
rem profile. Without it a link-launched SignFlow has no profile, and
rem ProductionSafetyValidator refuses to start it ("No signing token is
rem configured") whenever the link cannot simply be handed to a running copy.
reg add "HKCU\Software\Classes\signflow\shell\open\command" /ve /d "\"%JAVA_EXE%\" %JAVA_OPTS% -jar \"%APP_JAR%\" --spring.profiles.active=dev \"%%1\"" /f >nul

echo.
echo Registered signflow:// for the current user.
echo Handler: "%JAVA_EXE%" %JAVA_OPTS% -jar "%APP_JAR%" --spring.profiles.active=dev "link"
echo.
echo Try it:  press Win+R and run   signflow://invitation/test
exit /b 0

:failed
echo Failed to write the registry keys under HKCU\Software\Classes\signflow.
exit /b 1
