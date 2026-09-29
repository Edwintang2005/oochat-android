@echo off
rem ConnectOnion Android - install the app on a device and launch it (Windows).
rem
rem This is a launcher. The work is in run.ps1 beside it.
rem
rem   run.bat            interactive
rem   run.bat -y         skip the confirmation prompt
rem   run.bat -Build     build from source instead of using the pre-built APK

where powershell >nul 2>&1
if errorlevel 1 (
    echo.
    echo [X] Windows PowerShell was not found on PATH, and these scripts need it.
    echo     It normally lives at:
    echo       %%SystemRoot%%\System32\WindowsPowerShell\v1.0\powershell.exe
    echo.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
exit /b %ERRORLEVEL%
