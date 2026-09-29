# ConnectOnion Android - boot a device, install the app, launch it (Windows).
#
# Uses a physical phone if one is attached, otherwise the virtual device that
# install.bat created. Run install.bat first.

[CmdletBinding()]
param(
    [switch]$Yes,
    [switch]$Build
)

$ErrorActionPreference = 'Stop'

$SetupDir = $PSScriptRoot
$LocalDir = Join-Path $SetupDir '.local'
$EnvFile  = Join-Path $LocalDir 'env.ps1'

function Write-Bold($text) { Write-Host $text -ForegroundColor White }
function Write-Info($text) { Write-Host "  $text" }
function Write-Step($text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

function Stop-WithHelp {
    param([string]$Message, [string[]]$Hints = @())
    Write-Host ''
    Write-Host "X $Message" -ForegroundColor Red
    Write-Host ''
    foreach ($h in $Hints) { Write-Host "    $h" }
    Write-Host ''
    exit 1
}

if (-not (Test-Path $EnvFile)) {
    Stop-WithHelp 'Setup has not been run yet.' @(
        'Run this first, in the same folder:'
        '  install.bat'
    )
}
. $EnvFile

$adb      = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
$emulator = Join-Path $env:ANDROID_SDK_ROOT 'emulator\emulator.exe'

if (-not (Test-Path $adb)) {
    Stop-WithHelp 'adb is missing from the SDK.' @('Re-run install.bat to repair it.')
}

# --- Which APK -------------------------------------------------------------------
$apk = $null
if (-not $Build) {
    $shipped = Get-ChildItem -Path (Join-Path $RepoRoot 'apk') -Filter '*.apk' -ErrorAction SilentlyContinue |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($shipped) {
        $apk = $shipped.FullName
    } else {
        $built = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (Test-Path $built) { $apk = $built }
    }
}

# --- Say what is about to happen --------------------------------------------------
Clear-Host
Write-Bold '================================================================'
Write-Bold '  ConnectOnion Android - install and launch'
Write-Bold '================================================================'
Write-Host ''
Write-Bold 'WHAT THIS DOES'
if ($apk) {
    Write-Info '1. Uses the pre-built APK:'
    Write-Info "     $apk"
} else {
    Write-Info '1. Builds the APK from source with Gradle (needs JDK 17).'
    Write-Info '     This takes about four minutes the first time.'
}
Write-Info '2. Uses an attached phone if there is one, otherwise boots the'
Write-Info "     virtual device '$AvdName' created by install.bat."
Write-Info '3. Installs the app onto it and opens it.'
Write-Host ''
Write-Bold 'WHAT IS NOT TOUCHED'
Write-Info "Only the app '$AppId' is installed on the device."
Write-Info "Nothing on this computer changes outside $LocalDir."
Write-Host ''

if (-not $Yes) {
    $reply = Read-Host 'Proceed? [y/N]'
    if ($reply -notmatch '^(y|yes)$') { Write-Host 'Cancelled.'; exit 0 }
}

# --- Build, if that is the route ---------------------------------------------------
if (-not $apk) {
    Write-Step 'Building the APK from source'
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Stop-WithHelp 'No Java runtime found, and the build needs JDK 17.' @(
            'Install it from:  https://adoptium.net/temurin/releases/?version=17'
            "Or drop a pre-built APK into $RepoRoot\apk\ and re-run."
        )
    }
    $raw = (& java -version 2>&1 | Select-Object -First 1) -join ''
    if ($raw -notmatch 'version "17') {
        Stop-WithHelp "The build needs exactly JDK 17; this machine reports: $raw" @(
            'Get JDK 17 from:  https://adoptium.net/temurin/releases/?version=17'
            'Then point JAVA_HOME at it before re-running.'
        )
    }

    Push-Location $RepoRoot
    try {
        $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
        # Without GRADLE_USER_HOME, Gradle puts its distribution and the whole
        # dependency graph — well over a gigabyte — in %USERPROFILE%\.gradle,
        # which uninstall.bat does not touch and has no business deleting.
        $env:GRADLE_USER_HOME = Join-Path $LocalDir 'gradle-home'
        & (Join-Path $RepoRoot 'gradlew.bat') --console=plain :app:assembleDebug
        $gradleRc = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($gradleRc -ne 0) {
        Stop-WithHelp 'The Gradle build failed.' @(
            'The output above says why. Common causes are in INSTALL.md section 6.'
            'You can skip the build entirely by using the pre-built APK in apk\.'
        )
    }
    $apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk)) {
        Stop-WithHelp "Gradle reported success but the APK is not at $apk"
    }
}

# --- A device to install onto --------------------------------------------------------
Write-Step 'Looking for a device'
& $adb start-server 2>&1 | Out-Null
$attached = @((& $adb devices) | Select-Object -Skip 1 |
             Where-Object { $_ -match '^\S+\s+device\s*$' })

if ($attached.Count -gt 1) {
    # Matches run.sh: adb refuses to act with two targets, and finding that out
    # halfway through an install produces a misleading error.
    $serials = ($attached | ForEach-Object { ($_ -split '\s+')[0] }) -join ' '
    Stop-WithHelp 'More than one device is connected, so adb cannot tell which to use.' @(
        "Attached: $serials"
        'Unplug all but one, or close any running emulator, and try again.'
    )
}

$serial = $null
$proc   = $null
$log    = Join-Path $LocalDir 'emulator.log'

if ($attached.Count -eq 1) {
    $serial = ($attached[0] -split '\s+')[0]
    Write-Info "Found one already connected - using it ($serial)."
} else {
    Write-Info "None attached. Booting the virtual device '$AvdName'."
    if (-not (Test-Path $emulator)) {
        Stop-WithHelp 'The emulator is missing from the SDK.' @('Re-run install.bat to repair it.')
    }
    $proc = Start-Process -FilePath $emulator `
        -ArgumentList @('-avd', $AvdName, '-no-snapshot-save', '-no-boot-anim') `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
        -PassThru -WindowStyle Minimized
    Write-Info "Started (pid $($proc.Id)). Log: $log"
}

# Every adb call from here targets one device explicitly, so a phone plus a
# leftover emulator cannot make adb refuse mid-install.
function Invoke-Adb {
    if ($serial) { & $adb -s $serial @args } else { & $adb @args }
}

# Runs whichever branch we came from. adb reports a device as `device` well
# before the OS has finished starting, so an emulator left half-booted by an
# interrupted earlier run looks ready here and is not.
Write-Step 'Waiting for the device to be ready'
$waited = 0
while ($true) {
    $booted = (Invoke-Adb shell getprop sys.boot_completed 2>$null) -join '' -replace '\s', ''
    if ($booted -eq '1') { break }
    if ($proc -and $proc.HasExited) {
        Stop-WithHelp 'The emulator exited before finishing its boot.' @(
            "The reason is at the end of:  $log"
            'Hardware acceleration is the usual cause on Windows. Check that'
            'Windows Hypervisor Platform is enabled in "Turn Windows features on or off".'
            'Troubleshooting: https://developer.android.com/studio/run/emulator-troubleshooting'
        )
    }
    if ($waited -ge 420) {
        Stop-WithHelp 'The device has not finished booting after seven minutes.' @(
            "Check $log, or start it by hand with:"
            "  & '$emulator' -avd $AvdName"
        )
    }
    Start-Sleep -Seconds 3
    $waited += 3
    Write-Host '.' -NoNewline
}
if ($waited -gt 0) { Write-Host '' }
if (-not $serial) {
    # The emulator branch had no serial to pin until adb could see it.
    $now = @((& $adb devices) | Select-Object -Skip 1 |
             Where-Object { $_ -match '^\S+\s+device\s*$' })
    if ($now.Count -ge 1) { $serial = ($now[0] -split '\s+')[0] }
}
Write-Info "Ready$(if ($waited) { " after ${waited}s" })$(if ($serial) { " ($serial)" })."

# --- Install -------------------------------------------------------------------------
Write-Step 'Installing the app'
Invoke-Adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Info 'Streamed install failed - retrying by pushing the file first.'
    Invoke-Adb push $apk /data/local/tmp/connectonion.apk 2>&1 | Out-Null
    Invoke-Adb shell pm install -r -t /data/local/tmp/connectonion.apk
    if ($LASTEXITCODE -ne 0) {
        Stop-WithHelp 'Could not install the APK onto the device.' @(
            'If it is a physical phone, unlock it and accept the USB-debugging prompt.'
            "Check it is visible with:  & '$adb' devices"
            'adb reference: https://developer.android.com/tools/adb'
        )
    }
}
Write-Info 'Installed.'

Write-Step 'Launching'
Invoke-Adb shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    # Matches run.sh, which exits non-zero here: the same failure must not look
    # like success on one platform and failure on the other.
    Stop-WithHelp 'Installed, but the app would not start.' @(
        "Open 'ConnectOnion' from the device's app list by hand."
    )
}

# --- What to do next ------------------------------------------------------------------
Write-Step 'Running'
Write-Host ''
Write-Bold 'TO CONFIRM IT WORKS END TO END'
Write-Info '1. On the Onboarding screen, paste this agent address:'
Write-Info ''
Write-Info '   0xb7062bd7d3938a320956fde55c59cb0436f132df8bd60f129d8ab10d159cc207'
Write-Info ''
Write-Info '2. If it asks for an invite code, enter:  OpenOnion'
Write-Info '3. Send any message. A reply confirms install, identity, relay'
Write-Info '   connection and round trip are all working.'
Write-Host ''
Write-Info 'A second agent with many more tools, for seeing tool cards and'
Write-Info 'approval prompts do something real:'
Write-Info '   0xb73ccb0e7132d84971fcee6d797eaaddc8a029608ed880b074ef64fb8680d124'
Write-Host ''
Write-Bold 'NOTES'
Write-Info "The '/' command palette is empty against both - they publish no skills."
Write-Info 'An emulator has no camera or microphone, so photo capture and dictation'
Write-Info 'cannot be exercised on one. Everything else works.'
Write-Host ''
Write-Bold 'WHEN YOU ARE FINISHED'
Write-Info 'uninstall.bat   - removes the app and everything install.bat downloaded.'
Write-Host ''
