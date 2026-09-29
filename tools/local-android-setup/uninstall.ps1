# ConnectOnion Android - remove everything install.bat created (Windows).

[CmdletBinding()]
param([switch]$Yes)

$ErrorActionPreference = 'Stop'

$SetupDir = $PSScriptRoot
$LocalDir = Join-Path $SetupDir '.local'
$EnvFile  = Join-Path $LocalDir 'env.ps1'

function Write-Bold($text) { Write-Host $text -ForegroundColor White }
function Write-Info($text) { Write-Host "  $text" }
function Write-Step($text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

if (-not (Test-Path $LocalDir)) {
    Write-Host "Nothing to remove - $LocalDir does not exist."
    Write-Host 'install.bat has either not been run, or this has already been undone.'
    exit 0
}

$AppId     = 'ai.openonion.oochat'
$AvdName   = 'openonion-android'
$SdkReused = $false
if (Test-Path $EnvFile) { . $EnvFile }

$sizeMb = 0
try {
    $sizeMb = [math]::Round(
        ((Get-ChildItem -Path $LocalDir -Recurse -File -ErrorAction SilentlyContinue |
          Measure-Object -Property Length -Sum).Sum / 1MB), 0)
} catch { }

Clear-Host
Write-Bold '================================================================'
Write-Bold '  ConnectOnion Android - remove the local setup'
Write-Bold '================================================================'
Write-Host ''
Write-Bold 'WHAT WILL BE DELETED'
Write-Info "$LocalDir  (~$sizeMb MB)"
if ($SdkReused) {
    Write-Info ''
    Write-Info 'This includes the virtual device only. The Android SDK at'
    Write-Info "  $env:ANDROID_SDK_ROOT"
    Write-Info 'was already on this machine and will be left alone - but the packages'
    Write-Info 'install added to it (platform, system image, emulator) stay behind.'
    Write-Info 'Remove those with the SDK Manager in Android Studio if you want them gone.'
} else {
    Write-Info '  -> the private Android SDK, the emulator, the system image,'
    Write-Info "     and the '$AvdName' virtual device"
}
Write-Info ''
Write-Info "The app '$AppId' will be uninstalled from any connected device."
Write-Host ''
Write-Bold 'WHAT WAS NEVER TOUCHED, AND SO NEEDS NO UNDOING'
Write-Info 'Your PATH and every system or user environment variable.'
Write-Info '%USERPROFILE%\.android, .gradle, and any Android Studio installation of'
Write-Info '  your own - the scripts pointed the SDK tools and Gradle at .local\'
Write-Info '  instead, so there is nothing of yours to clean up.'
Write-Info 'Anything outside this folder.'
Write-Host ''

if (-not $Yes) {
    $reply = Read-Host 'Delete it all? [y/N]'
    if ($reply -notmatch '^(y|yes)$') {
        Write-Host 'Cancelled. Nothing was removed.'
        exit 0
    }
}

$adb = if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' } else { $null }

Write-Step 'Removing the app from any connected device'
if ($adb -and (Test-Path $adb)) {
    $attached = @((& $adb devices) | Select-Object -Skip 1 |
                 Where-Object { $_ -match '^\S+\s+device\s*$' })
    if ($attached.Count -gt 0) {
        & $adb uninstall $AppId 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Info "Uninstalled $AppId." }
        else { Write-Info 'Not installed on the connected device - nothing to do.' }
    } else {
        Write-Info 'No device connected - skipping.'
    }
} else {
    Write-Info 'adb not available - skipping.'
}

Write-Step 'Shutting down the emulator, if it is running'
if ($adb -and (Test-Path $adb)) {
    & $adb emu kill 2>&1 | Out-Null
    & $adb kill-server 2>&1 | Out-Null
    # `emu kill` returns before the emulator process has actually exited, and
    # Windows will not delete a file that still has an open handle. Give it a
    # moment rather than failing the delete below on a race.
    Start-Sleep -Seconds 2
    Write-Info 'Done.'
} else {
    Write-Info 'adb not available - skipping.'
}

Write-Step "Deleting $LocalDir"
if ($SdkReused -and $env:ANDROID_SDK_ROOT -and -not $env:ANDROID_SDK_ROOT.StartsWith($LocalDir)) {
    Write-Info "Leaving the pre-existing SDK at $env:ANDROID_SDK_ROOT untouched."
}
$removed = $false
for ($attempt = 1; $attempt -le 5; $attempt++) {
    try {
        Remove-Item -Recurse -Force $LocalDir -ErrorAction Stop
        $removed = $true
        break
    } catch {
        if ($attempt -lt 5) { Start-Sleep -Seconds 2 }
    }
}
if (-not $removed) {
    Write-Host ''
    Write-Host '[!] Some files could not be deleted, usually because the emulator is' -ForegroundColor Yellow
    Write-Host '    still running. Close it and run uninstall.bat again.' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}
Write-Info 'Removed.'

Write-Host ''
Write-Bold 'DONE'
Write-Info 'This folder now holds only the scripts themselves.'
Write-Info 'Delete tools\local-android-setup\ to remove those too.'
Write-Host ''
