@echo off
rem ============================================================
rem  SignFlow coordination server launcher (REMOTE signing mode)
rem
rem  Configuration comes from the environment only - NO interactive
rem  prompts. For real SMTP email, set the SIGNER_EMAIL_MODE=smtp
rem  and SPRING_MAIL_* variables, or keep them in a secrets file
rem  OUTSIDE the project, loaded automatically when present:
rem    %SIGNFLOW_SECRETS_FILE%  when that variable is set, otherwise
rem    %USERPROFILE%\signflow-secrets\mail-secrets.env
rem  A mail-secrets.env inside the project folder is ignored (warning).
rem
rem  Without mail configuration the server starts in console mode:
rem  invitation links are printed in this window (dev mailbox).
rem
rem  The server must stay open while using Remote workflow in the
rem  desktop app. Stop with Ctrl+C or close the window.
rem ============================================================
setlocal
set JAVA=C:\Users\Mnl\.jdks\openjdk-26.0.2\bin\java.exe
set JAR=%~dp0target\pdf-signer-desktop-0.1.0-SNAPSHOT.jar
set SIGNER_SERVER_STORAGE=%USERPROFILE%\signer-server-data
set SIGNER_DB_ENABLED=true
set SIGNER_DB_URL=jdbc:postgresql://localhost:5432/Signflow
set SIGNER_DB_USERNAME=postgres
set SIGNER_DB_PASSWORD=
if not defined APP_BASE_URL set APP_BASE_URL=http://localhost:8080

rem Load optional secrets/overrides from OUTSIDE the project folder:
rem %SIGNFLOW_SECRETS_FILE% when set, else
rem %USERPROFILE%\signflow-secrets\mail-secrets.env. Both plain KEY=VALUE
rem and bash-style "export KEY=VALUE" lines work, so the same file can be
rem sourced in Git Bash and read by this launcher. '#' lines are
rem comments.
rem A mail-secrets.env left in the project folder is never loaded: it
rem only triggers a warning to move it out.
if not exist "%~dp0mail-secrets.env" goto noProjectSecrets
echo.
echo WARNING: a mail-secrets.env file was found in the project folder:
echo   %~dp0mail-secrets.env
echo It is NOT loaded. Move it to
echo   %USERPROFILE%\signflow-secrets\mail-secrets.env
echo or point SIGNFLOW_SECRETS_FILE at its new location.
echo.
:noProjectSecrets
set "__sf=%SIGNFLOW_SECRETS_FILE%"
if not defined __sf set "__sf=%USERPROFILE%\signflow-secrets\mail-secrets.env"
if exist "%__sf%" goto loadSecrets
if defined SIGNFLOW_SECRETS_FILE echo WARNING: SIGNFLOW_SECRETS_FILE points to a missing file: %SIGNFLOW_SECRETS_FILE%
goto noSecrets
:loadSecrets
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%__sf%") do call :applySecret "%%a" "%%b"
goto secretsLoaded

rem Applies one secret line. Strips an optional leading "export " (bash
rem style) and optional surrounding quotes around the value, so
rem "export SPRING_MAIL_PASSWORD=abc" sets SPRING_MAIL_PASSWORD=abc.
:applySecret
set "__sn=%~1"
set "__sv=%~2"
if not defined __sv goto :eof
if /i "%__sn:~0,7%"=="export " set "__sn=%__sn:~7%"
:trimSecretName
if not "%__sn:~-1%"==" " goto secretNameTrimmed
set "__sn=%__sn:~0,-1%"
goto trimSecretName
:secretNameTrimmed
set "__sv=%__sv:"=%"
set "%__sn%=%__sv%"
set "__sn="
set "__sv="
goto :eof

:noSecrets
:secretsLoaded
set "__sf="

if exist "%JAR%" goto haveJar
echo The application jar was not found:
echo   %JAR%
echo Build it first:  set JAVA_HOME=C:\Users\Mnl\.jdks\openjdk-26.0.2
echo                  mvnw.cmd package -DskipTests
echo Stop any running server first - Windows locks the jar.
pause
exit /b 1
:haveJar

rem A server already listening on 8080 would make this one crash right
rem after startup. Detect it up front and say so instead of failing
rem with a stack trace.
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>&1
if errorlevel 1 goto portFree
echo.
echo A server is ALREADY RUNNING on port 8080.
echo Only one coordination server can run at a time.
echo.
echo To use the existing one: just start the desktop app, run-app.cmd
echo To restart it: close the other server window first.
echo.
pause
exit /b 0
:portFree

if /i "%SIGNER_EMAIL_MODE%"=="smtp" goto smtpMode
set SIGNER_EMAIL_MODE=console
echo Mode: DATABASE + CONSOLE email - invitation links are printed in this window.
goto modeDone
:smtpMode
if not defined SPRING_MAIL_HOST goto smtpIncomplete
if not defined SPRING_MAIL_USERNAME goto smtpIncomplete
if not defined SPRING_MAIL_PASSWORD goto smtpIncomplete
echo Mode: DATABASE + SMTP email - sender: %SIGNER_EMAIL_FROM% via %SPRING_MAIL_HOST%:%SPRING_MAIL_PORT%
goto modeDone

rem Never silently fall back to console mode: an incomplete SMTP setup is
rem almost always a typo in the secrets file, and the symptom (no email in
rem the recipient's mailbox) looks like a bug in the app.
:smtpIncomplete
echo.
echo SIGNER_EMAIL_MODE=smtp, but the SMTP settings are incomplete.
echo SPRING_MAIL_HOST, SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD must
echo all be set. Secrets file: %%SIGNFLOW_SECRETS_FILE%% if set, otherwise
echo   %USERPROFILE%\signflow-secrets\mail-secrets.env
echo.
pause
exit /b 1

:modeDone
echo DEVELOPMENT server (profile server,dev): plain HTTP, console mailbox,
echo development accounts and mock-token certificates are accepted.
echo Production: see docs\TRANSPORT-SECURITY.md and .env.example.
echo Server: %APP_BASE_URL%   Dev users: alice / bob / charlie / jane
echo %APP_BASE_URL% | findstr /i /c:"localhost" /c:"127.0.0.1" >nul
if errorlevel 1 goto baseUrlDone
echo.
echo NOTE: APP_BASE_URL is a local address, so invitation emails will use a
echo       LAN address of THIS PC detected at startup instead, e.g.
echo       http://192.168.x.x:8080 - those links open for signers on the
echo       same network only. For a signer anywhere else set
echo       SIGNER_PUBLIC_BASE_URL to a public URL or a tunnel to this port
echo       (see .env.example). The server prints the address it uses below.
echo.
:baseUrlDone
echo Keep this window open. Stop with Ctrl+C.
echo.
"%JAVA%" -Dloader.main=com.example.signer.server.WorkflowServerApplication ^
    -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT ^
    -cp "%JAR%" org.springframework.boot.loader.launch.PropertiesLauncher ^
    --spring.profiles.active=server,dev --server.port=8080

rem The window would close instantly on a startup crash - keep it open
rem so the error can actually be read.
echo.
echo The server exited. Scroll up for the error, then press a key to close.
pause >nul
endlocal
