# HexCLI one-click installer - Windows bootstrap (PowerShell 5.1+).
#
# Downloads a private Temurin JRE 17 into .\runtime (never touching the system Java or PATH),
# then runs hexcli-installer.jar to download and verify the Fabric server launcher and mods.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File install.ps1 [-y] [-Dir <server dir>]
#              [-Manifest <file|url>] [-ModOverride name=path] [-Java <path>]
#
# Environment:
#   HEXCLI_ACCEPT_EULA=1       same as -y
#   HEXCLI_JAVA=<path>         same as -Java
#   HEXCLI_MIELIB_JAR=<path>   shorthand for -ModOverride mielib=<path>
#   HEXCLI_ADOPTIUM_API=<url>  override the Adoptium API base (mirrors / tests)

[CmdletBinding()]
param(
    [switch]$y,
    [string]$Dir = "server",
    [string]$Manifest = "",
    [string]$ModOverride = "",
    [string]$Java = ""
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is much faster without the progress bar

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$Adoptium = if ($env:HEXCLI_ADOPTIUM_API) { $env:HEXCLI_ADOPTIUM_API } else { "https://api.adoptium.net/v3" }
$Runtime = Join-Path $Root "runtime"
if (-not $Java -and $env:HEXCLI_JAVA) { $Java = $env:HEXCLI_JAVA }

$Accept = $y.IsPresent -or $env:HEXCLI_ACCEPT_EULA -eq "1" -or $env:HEXCLI_ACCEPT_EULA -eq "true"

function Get-JavaMajor([string]$Exe) {
    try {
        $line = (& $Exe -version 2>&1 | Select-Object -First 1) -join ""
        if ($line -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch {
        return 0
    }
    return 0
}

function Find-RuntimeJava {
    $candidate = Join-Path $Runtime "bin\java.exe"
    if ((Test-Path $candidate) -and (Get-JavaMajor $candidate) -ge 17) { return $candidate }
    return $null
}

function Get-Arch {
    $arch = $env:PROCESSOR_ARCHITECTURE
    if (-not $arch) { $arch = (Get-CimInstance Win32_Processor).Architecture }
    switch ($arch) {
        "AMD64" { return "x64" }
        "x86" {
            if ($env:PROCESSOR_ARCHITEW6432 -eq "AMD64") { return "x64" }
            throw "32-bit Windows is not supported; install a 64-bit JRE 17 and pass -Java."
        }
        "ARM64" { return "aarch64" }
        9 { return "x64" }
        12 { return "aarch64" }
        default { throw "Unsupported CPU architecture: $arch" }
    }
}

# ---- 1. private Java 17 ----------------------------------------------------

$JavaExe = $null
if ($Java) {
    if (-not (Test-Path $Java)) { throw "-Java / HEXCLI_JAVA does not exist: $Java" }
    $JavaExe = (Resolve-Path $Java).Path
    Write-Host "using Java from -Java / HEXCLI_JAVA: $JavaExe"
} else {
    $JavaExe = Find-RuntimeJava
}

if (-not $JavaExe) {
    $arch = Get-Arch
    Write-Host "no Java 17+ in .\runtime; downloading Temurin JRE 17 for windows/$arch"
    Write-Host "(this is a private runtime in .\runtime - the system Java and PATH are not touched)"

    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("hexcli-install-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        $assetsUrl = "$Adoptium/assets/latest/17/hotspot?os=windows&architecture=$arch&image_type=jre"
        $assetsFile = Join-Path $tmp "assets.json"
        Invoke-WebRequest -UseBasicParsing -Uri $assetsUrl -OutFile $assetsFile
        $assets = Get-Content -Raw $assetsFile | ConvertFrom-Json
        $package = $assets[0].binary.package
        if (-not $package.checksum) { throw "no checksum in the Adoptium response" }

        $archive = Join-Path $tmp "temurin-jre.zip"
        Invoke-WebRequest -UseBasicParsing -Uri "$Adoptium/binary/latest/17/ga/windows/$arch/jre/hotspot/normal/eclipse" -OutFile $archive

        $actual = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLowerInvariant()
        if ($actual -ne $package.checksum.ToLowerInvariant()) {
            throw "Java runtime checksum mismatch: expected $($package.checksum), got $actual"
        }
        Write-Host "Java runtime checksum OK (sha256 $actual)"

        $extract = Join-Path $tmp "extract"
        Expand-Archive -Path $archive -DestinationPath $extract -Force
        # Replace a stale runtime only after the new one downloaded and verified.
        if (Test-Path $Runtime) { Remove-Item -Recurse -Force $Runtime }
        # The zip holds a single top-level folder (jdk-17...-jre); move its *contents* so Java
        # lands at runtime\bin\java.exe, which is where the generated scripts look for it.
        $inner = Get-ChildItem -Path $extract -Directory | Select-Object -First 1
        New-Item -ItemType Directory -Path $Runtime | Out-Null
        Move-Item -Path (Join-Path $inner.FullName "*") -Destination $Runtime
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }

    $JavaExe = Find-RuntimeJava
    if (-not $JavaExe) { throw "the downloaded runtime has no usable bin\java.exe" }
    Write-Host "Java installed: $JavaExe"
}

# ---- 2. EULA ---------------------------------------------------------------

if (-not $Accept) {
    Write-Host ""
    Write-Host "Minecraft requires you to accept the EULA to run a server:"
    Write-Host "  https://aka.ms/MinecraftEULA"
    $answer = Read-Host "Do you accept the Minecraft EULA? [y/N]"
    if ($answer -notmatch '^(y|yes)$') {
        Write-Host "EULA not accepted; nothing was installed."
        exit 1
    }
    $Accept = $true
}

# ---- 3. run the installer --------------------------------------------------

$installerJar = Join-Path $Root "hexcli-installer.jar"
$clientJar = Join-Path $Root "hexcli-client.jar"
if (-not (Test-Path $installerJar)) { throw "hexcli-installer.jar is missing next to install.ps1" }
if (-not (Test-Path $clientJar)) { throw "hexcli-client.jar is missing next to install.ps1" }

$installerArgs = @(
    "install",
    "--dir", $Dir,
    "--client-jar", $clientJar,
    "--java", $JavaExe,
    "--accept-eula"
)
if ($Manifest) { $installerArgs += @("--manifest", $Manifest) }
if ($ModOverride) { $installerArgs += @("--mod-override", $ModOverride) }

Write-Host ""
Write-Host "running the HexCLI installer..."
& $JavaExe -jar $installerJar @installerArgs
if ($LASTEXITCODE -ne 0) { throw "the HexCLI installer failed (exit code $LASTEXITCODE)" }

$serverAbs = (Resolve-Path $Dir).Path
Write-Host ""
Write-Host "Installation complete."
Write-Host ""
Write-Host "  server directory : $serverAbs"
Write-Host "  start + connect  : cd `"$serverAbs`"; .\start-and-connect.bat"
Write-Host "  server only      : cd `"$serverAbs`"; .\start-server.bat"
Write-Host "  client only      : cd `"$serverAbs`"; .\connect.bat"
Write-Host "  verify files     : `"$JavaExe`" -jar `"$installerJar`" verify --dir `"$serverAbs`""
Write-Host ""
Write-Host "The first server start downloads the vanilla Minecraft server from Mojang."
