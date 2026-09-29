@echo off
rem ConnectOnion Android - remove everything install.bat created (Windows).
rem
rem This is a launcher. The work is in uninstall.ps1 beside it, which lists what
rem it will delete and waits for confirmation.
rem
rem   uninstall.bat        interactive
rem   uninstall.bat -y     skip the confirmation prompt

where powershell >nul 2>&1
if errorlevel 1 (
    echo.
    echo [X] Windows PowerShell was not found on PATH, and these scripts need it.
    echo     It normally lives at:
    echo       %%SystemRoot%%\System32\WindowsPowerShell\v1.0\powershell.exe
    echo.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1" %*
exit /b %ERRORLEVEL%
