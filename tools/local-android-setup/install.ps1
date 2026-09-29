# ConnectOnion Android - one-shot local environment setup (Windows).
#
# Everything this installs lands under tools\local-android-setup\.local\. No PATH is
# modified, no environment variable is set outside this process, nothing needs
# an administrator. ANDROID_USER_HOME and (in run.ps1) GRADLE_USER_HOME are
# redirected into .local\ too, so the SDK tools and Gradle do not write to
# %USERPROFILE%\.android or .gradle either — without that, "uninstall removes
# everything" would not be true. uninstall.bat removes the lot.

[CmdletBinding()]
param(
    [switch]$Yes,
    [switch]$UseSystemSdk,
    [int]$AndroidApi = 36
)

$ErrorActionPreference = 'Stop'

$SetupDir = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $SetupDir '..\..')).Path
$LocalDir = Join-Path $SetupDir '.local'
$SdkDir   = Join-Path $LocalDir 'android-sdk'
$AvdDir   = Join-Path $LocalDir 'avd'
$AvdName  = 'openonion-android'
$CmdlineBuild = '13114758'

function Write-Bold($text) { Write-Host $text -ForegroundColor White }
function Write-Info($text) { Write-Host "  $text" }
function Write-Step($text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

# Print why a step failed, where to get the missing piece by hand, and stop.
function Stop-WithHelp {
    param([string]$Message, [string[]]$Hints = @())
    Write-Host ''
    Write-Host "X $Message" -ForegroundColor Red
    Write-Host ''
    if ($Hints.Count -gt 0) {
        Write-Host 'What to do:'
        foreach ($h in $Hints) { Write-Host "    $h" }
        Write-Host ''
    }
    if (Test-Path $LocalDir) {
        Write-Host "Nothing outside $LocalDir was changed."
        Write-Host 'Re-running this script resumes where it stopped; uninstall.bat starts over.'
    } else {
        Write-Host 'Nothing has been downloaded or changed.'
    }
    exit 1
}

# --- Architecture -------------------------------------------------------------
$abi = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64-v8a' } else { 'x86_64' }
$systemImage = "system-images;android-$AndroidApi;google_apis;$abi"
$cmdlineUrl  = "https://dl.google.com/android/repository/commandlinetools-win-${CmdlineBuild}_latest.zip"

# --- Reuse an existing SDK, if asked and one is actually there -----------------
$reusedSdk = $false
if ($UseSystemSdk) {
    $candidates = @(
        $env:ANDROID_SDK_ROOT
        $env:ANDROID_HOME
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')
    ) | Where-Object { $_ }

    $found = $candidates | Where-Object { Test-Path (Join-Path $_ 'platform-tools\adb.exe') } | Select-Object -First 1
    if ($found) {
        $SdkDir = $found
        $reusedSdk = $true
    } else {
        $shownRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { '<ANDROID_SDK_ROOT unset>' }
        $shownHome = if ($env:ANDROID_HOME)     { $env:ANDROID_HOME }     else { '<ANDROID_HOME unset>' }
        Stop-WithHelp '-UseSystemSdk was given, but no Android SDK was found.' @(
            'Looked for platform-tools\adb.exe under:'
            "  $shownRoot"
            "  $shownHome"
            "  $(Join-Path $env:LOCALAPPDATA 'Android\Sdk')   (Android Studio default)"
            'Re-run without -UseSystemSdk to download a private copy instead.'
        )
    }
}

# --- Tell them what is about to happen, then wait ------------------------------
Clear-Host
Write-Bold '================================================================'
Write-Bold '  ConnectOnion Android - environment setup'
Write-Bold '================================================================'
Write-Host ''
Write-Host 'This prepares a machine to install and run the app locally.'
Write-Host 'Read this before answering; nothing has been downloaded or changed yet.'
Write-Host ''
Write-Bold 'WHAT GETS INSTALLED'
if ($reusedSdk) {
    Write-Info 'Reusing the Android SDK already at:'
    Write-Info "  $SdkDir"
    Write-Info 'Only missing packages below are added to it. It is NOT deleted on uninstall.'
} else {
    Write-Info 'Android command-line tools    (~150 MB download)'
}
Write-Info "Platform API $AndroidApi               (~80 MB)"
Write-Info 'Platform-tools / adb          (~15 MB)'
Write-Info 'Android emulator              (~350 MB)'
Write-Info "System image $systemImage"
Write-Info '                              (~1.2 GB - the big one)'
Write-Info "One virtual device named '$AvdName'"
Write-Host ''
Write-Bold 'WHERE IT GOES'
if ($reusedSdk) {
    Write-Info "SDK packages  : $SdkDir  (existing, shared)"
} else {
    Write-Info "SDK           : $SdkDir"
}
Write-Info "Virtual device: $AvdDir"
Write-Host ''
Write-Bold 'WHAT IS NOT TOUCHED'
Write-Info 'No system or user environment variable is set, and PATH is unchanged.'
Write-Info 'No changes to %USERPROFILE%\.android or .gradle - the SDK tools are'
Write-Info '  pointed at a copy inside .local\, so nothing of yours is written'
Write-Info '  to or reused, and it cannot collide with Android Studio.'
Write-Info 'No administrator rights required.'
Write-Host ''
Write-Bold 'REMOVING IT LATER'
Write-Info 'uninstall.bat   - deletes everything above and the app from the device.'
Write-Host ''
Write-Bold 'ROUGH TOTALS'
Write-Info 'Download : ~1.8 GB     Disk after install : ~5.7 GB'
Write-Info 'Building from source instead of using a pre-built APK adds a further'
Write-Info '  ~1.5 GB of Gradle cache under .local\, also removed by .\uninstall.ps1.'
Write-Info 'Time     : 10-25 minutes, mostly the system image download.'
Write-Host ''
Write-Host "Host detected: Windows / $env:PROCESSOR_ARCHITECTURE  ->  emulator ABI $abi"
Write-Host ''

if (-not $Yes) {
    $reply = Read-Host 'Proceed? [y/N]'
    if ($reply -notmatch '^(y|yes)$') {
        Write-Host 'Cancelled. Nothing was downloaded or changed.'
        exit 0
    }
}

New-Item -ItemType Directory -Force -Path $LocalDir, $AvdDir | Out-Null

# --- Java (only needed to build from source, not to install the APK) -----------
Write-Step 'Checking for a Java runtime'
$javaOk = $false
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    $raw = (& java -version 2>&1 | Select-Object -First 1) -join ''
    Write-Info "Found: $raw"
    if ($raw -match 'version "17') { $javaOk = $true }
    else { Write-Info 'The Gradle build needs exactly JDK 17.' }
} else {
    Write-Info 'No java on PATH.'
}
if (-not $javaOk) {
    Write-Info ''
    Write-Info 'This is not fatal. JDK 17 is needed only to build from source or to'
    Write-Info 'run the test suite. Installing the pre-built APK does not need it.'
    Write-Info 'If you do want to build:'
    Write-Info '  https://adoptium.net/temurin/releases/?version=17'
    Write-Info ''
}

# --- Command-line tools ---------------------------------------------------------
$sdkManager = Join-Path $SdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
$avdManager = Join-Path $SdkDir 'cmdline-tools\latest\bin\avdmanager.bat'

if (Test-Path $sdkManager) {
    Write-Step 'Command-line tools already present - skipping download'
} else {
    Write-Step 'Downloading the Android command-line tools'
    $zip = Join-Path $LocalDir 'cmdline-tools.zip'
    try {
        $ProgressPreference = 'SilentlyContinue'
        # Windows PowerShell 5.1 runs on .NET Framework, whose default protocol
        # list does not always include TLS 1.2. dl.google.com requires it, and
        # without this the download fails with an opaque "could not create
        # SSL/TLS secure channel" on older or unpatched Windows images.
        [Net.ServicePointManager]::SecurityProtocol =
            [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $cmdlineUrl -OutFile $zip -UseBasicParsing
    } catch {
        Stop-WithHelp "Download failed: $cmdlineUrl" @(
            'Download that file by hand, then unzip it so this path exists:'
            "  $sdkManager"
            'All builds: https://developer.android.com/studio#command-line-tools-only'
        )
    }

    Write-Step 'Unpacking the command-line tools'
    $unpack = Join-Path $LocalDir 'unpack'
    if (Test-Path $unpack) { Remove-Item -Recurse -Force $unpack }
    try {
        Expand-Archive -Path $zip -DestinationPath $unpack -Force
    } catch {
        Stop-WithHelp 'The downloaded archive could not be unpacked.' @(
            "Delete $zip and run this script again."
        )
    }
    $cltParent = Join-Path $SdkDir 'cmdline-tools'
    New-Item -ItemType Directory -Force -Path $cltParent | Out-Null
    $latest = Join-Path $cltParent 'latest'
    if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
    Move-Item (Join-Path $unpack 'cmdline-tools') $latest
    Remove-Item -Recurse -Force $unpack -ErrorAction SilentlyContinue
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    Write-Info "Installed to $latest"
}

if (-not (Test-Path $sdkManager)) {
    Stop-WithHelp 'sdkmanager is missing after unpacking, which should not happen.' @(
        "Delete $LocalDir and run this script again."
    )
}

$env:ANDROID_SDK_ROOT = $SdkDir
$env:ANDROID_HOME     = $SdkDir
$env:ANDROID_AVD_HOME = $AvdDir
# Keeps the tools' own per-user state — repositories.cfg, adb's keypair, caches —
# inside .local\ rather than %USERPROFILE%\.android, which is what makes the
# "nothing outside this directory" promise true.
#
# ANDROID_USER_HOME only. Setting ANDROID_SDK_HOME as well — on the reasoning
# that it is the deprecated predecessor and older tooling still reads it — breaks
# AVD creation outright, reproduced on macOS with these same pinned tools:
#
#   Error: An error occurred while creating AVD: Can't locate Android SDK
#   installation directory for the AVD .ini file.
#
# avdmanager resolves the SDK through ANDROID_SDK_HOME when it is set, and any
# value fails — the variable no longer means what its name suggests. Unsetting it
# is the fix.
$env:ANDROID_USER_HOME = Join-Path $LocalDir 'android-home'
Remove-Item Env:\ANDROID_SDK_HOME -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

# --- Licences and packages -------------------------------------------------------
Write-Step 'Accepting SDK licences'
$yesFeed = (1..100 | ForEach-Object { 'y' }) -join "`r`n"
$yesFeed | & $sdkManager "--sdk_root=$SdkDir" --licenses 2>&1 | Out-Null
# Out-Null is a cmdlet and does not disturb $LASTEXITCODE, so this still reads
# sdkmanager's own status. Without the check, a licence failure surfaces later
# as "could not install platform-tools", which points at the wrong thing.
if ($LASTEXITCODE -ne 0) {
    Stop-WithHelp "Could not accept the SDK licences - sdkmanager exited $LASTEXITCODE." @(
        'Nothing can be installed until they are accepted, so this stops here'
        'rather than failing on the first package and blaming that instead.'
        'Accept them by hand, answering y to each prompt:'
        "  & '$sdkManager' --sdk_root='$SdkDir' --licenses"
        'Reference: https://developer.android.com/tools/sdkmanager'
    )
}
Write-Info 'Done.'

Write-Step 'Installing SDK packages (this is the long part)'
$packages = @('platform-tools', "platforms;android-$AndroidApi", 'emulator', $systemImage)
foreach ($pkg in $packages) {
    Write-Info "-> $pkg"
    & $sdkManager "--sdk_root=$SdkDir" $pkg 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        if ($pkg -eq $systemImage) {
            Stop-WithHelp "Could not install the system image: $pkg" @(
                "API $AndroidApi may not have an image for $abi yet."
                'Try another level, for example:'
                '  powershell -ExecutionPolicy Bypass -File install.ps1 -AndroidApi 35'
                'Or list what exists:'
                "  & '$sdkManager' --sdk_root='$SdkDir' --list | Select-String system-images"
            )
        } else {
            Stop-WithHelp "Could not install: $pkg" @(
                'Retry, or install it by hand with:'
                "  & '$sdkManager' --sdk_root='$SdkDir' '$pkg'"
                'Reference: https://developer.android.com/tools/sdkmanager'
            )
        }
    }
}
Write-Info 'All packages installed.'

# --- Virtual device ---------------------------------------------------------------
Write-Step "Creating the virtual device '$AvdName'"
$avdIni = Join-Path $AvdDir "$AvdName.ini"
if (Test-Path $avdIni) {
    Write-Info 'Already exists - leaving it alone.'
} else {
    $avdPath = Join-Path $AvdDir "$AvdName.avd"
    'no' | & $avdManager create avd --name $AvdName --package $systemImage --device 'pixel_6' --path $avdPath 2>&1 | Out-Null
    if (-not (Test-Path $avdIni)) {
        'no' | & $avdManager create avd --name $AvdName --package $systemImage --path $avdPath 2>&1 | Out-Null
    }
    if (-not (Test-Path $avdIni)) {
        Stop-WithHelp 'Could not create the virtual device.' @(
            'Create one by hand with:'
            "  `$env:ANDROID_AVD_HOME = '$AvdDir'"
            "  & '$avdManager' create avd -n $AvdName -k '$systemImage'"
            'Reference: https://developer.android.com/tools/avdmanager'
        )
    }
    Write-Info "Created at $avdPath"
}

# --- Record the environment for run.ps1 / uninstall.ps1 ----------------------------
$envFile = Join-Path $LocalDir 'env.ps1'
# Every path below is interpolated into a single-quoted PowerShell literal, so
# an apostrophe in it — a Windows account named O'Brien is all it takes — would
# close the string early and emit a file that cannot be dot-sourced. Doubling
# is PowerShell's escape for a quote inside a single-quoted string.
$eSdk   = $SdkDir   -replace "'", "''"
$eAvd   = $AvdDir   -replace "'", "''"
$eRepo  = $RepoRoot -replace "'", "''"
$eLocal = $LocalDir -replace "'", "''"
$eUser  = (Join-Path $LocalDir 'android-home') -replace "'", "''"
@"
# Written by install.ps1 - read by run.ps1 and uninstall.ps1. Safe to delete.
`$env:ANDROID_SDK_ROOT  = '$eSdk'
`$env:ANDROID_HOME      = '$eSdk'
`$env:ANDROID_AVD_HOME  = '$eAvd'
`$env:ANDROID_USER_HOME = '$eUser'
# Deliberately no ANDROID_SDK_HOME - see the note in install.ps1 where it is removed.
`$AvdName   = '$AvdName'
`$RepoRoot  = '$eRepo'
`$SdkReused = `$$($reusedSdk.ToString().ToLower())
`$AppId     = 'ai.openonion.oochat'
"@ | Set-Content -Path $envFile -Encoding UTF8

Write-Step 'Setup complete'
Write-Host ''
Write-Bold 'NEXT STEP'
Write-Info 'run.bat    - boots the virtual device, installs the app, and launches it.'
Write-Info '             Attach a physical phone first and it uses that instead.'
Write-Host ''
Write-Bold 'WHEN YOU ARE FINISHED'
Write-Info 'uninstall.bat   - removes everything this script created.'
Write-Host ''
