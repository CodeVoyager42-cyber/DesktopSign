@echo off
rem ============================================================
rem  Removes the per-user signflow:// URL protocol registration
rem  created by register-signflow-protocol.cmd. No admin rights
rem  needed; other users of this machine are unaffected.
rem ============================================================
reg delete "HKCU\Software\Classes\signflow" /f >nul 2>&1
if errorlevel 1 (
    echo signflow:// was not registered for this user.
    exit /b 1
)
echo Removed the signflow:// protocol registration.
exit /b 0
