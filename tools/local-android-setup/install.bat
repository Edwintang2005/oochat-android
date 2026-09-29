@echo off
rem ConnectOnion Android - local environment setup (Windows).
rem
rem This is a launcher. The work is in install.ps1 beside it, which prints what
rem it is about to do and waits for confirmation before changing anything.
rem
rem   install.bat            interactive
rem   install.bat -y         skip the confirmation prompt
rem   install.bat -AndroidApi 35    use a different platform level

where powershell >nul 2>&1
if errorlevel 1 (
    echo.
    echo [X] Windows PowerShell was not found on PATH, and these scripts need it.
    echo.
    echo     PowerShell ships with Windows 7 and later; a missing one usually means
    echo     PATH has been edited. It normally lives at:
    echo       %%SystemRoot%%\System32\WindowsPowerShell\v1.0\powershell.exe
    echo.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
exit /b %ERRORLEVEL%
