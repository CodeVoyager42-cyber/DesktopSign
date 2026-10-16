@echo off
rem ============================================================
rem  SignFlow database console - PostgreSQL database "signer"
rem  Usage:
rem    double-click            opens an interactive psql prompt
rem    db-console.cmd -c "select * from invitations;"
rem                            runs one query and exits
rem  No password is needed (this machine trusts local connections).
rem ============================================================
setlocal
set PGBIN=C:\Program Files\PostgreSQL\16\bin

if not exist "%PGBIN%\psql.exe" (
    echo.
    echo psql was not found at:
    echo   %PGBIN%
    echo Edit PGBIN in this file to match your PostgreSQL install.
    pause
    exit /b 1
)

if "%~1"=="" (
    echo.
    echo Connected to database "Signflow" on localhost:5432 as user postgres.
    echo Handy commands and queries:
    echo   \dt                            list the tables
    echo   \di                            list the indexes
    echo   SELECT * FROM invitations;    invitations and their status
    echo   SELECT username, email, status FROM users;
    echo   SELECT workflow_id, document_name, status FROM workflows;
    echo   SELECT event_type, actor, created_at FROM audit_events
    echo     ORDER BY id DESC LIMIT 10;
    echo   \q                             quit
    echo.
)

"%PGBIN%\psql.exe" -U postgres -d Signflow -p 5432 %*
endlocal