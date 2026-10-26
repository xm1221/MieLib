# HexCLI one-click installer - Windows bootstrap (PowerShell 5.1+).
#
# Downloads a private Temurin JRE 17 into .\runtime (never touching the system Java or PATH),
# then runs hexcli-installer.jar to download and verify the Fabric server launcher and mods.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File install.ps1 [-y] [-Dir <server dir>]
#              [-Manifest <file|url>] [-ModOverride name=path] [-Java <java.exe|JAVA_HOME dir>]
#              [-Mirror official|bmclapi] [-UrlRewrite from=to] [-GithubProxy <url>]
#              [-AdoptiumApi <url>] [-NoFallback]
#   (install.bat forwards the same arguments)
#
# Java (first match wins; must be Java 17+):
#   -Java <path>               java.exe or a JDK/JRE home directory
#   HEXCLI_JAVA=<path>         same as -Java
#   JAVA_HOME                  used when it holds Java 17+ (otherwise ignored)
#   .\runtime                  the private runtime from an earlier run
#   (otherwise)                download Temurin JRE 17 into .\runtime (sha256-checked)
#
# Download source:
#   -Mirror <name>             official (default) | bmclapi (aliases cn, mcim): Modrinth via
#                              mod.mcimirror.top, Fabric meta/maven via BMCLAPI; official URLs are
#                              still tried if the mirror fails (-DownloadSource is an alias)
#   -UrlRewrite from=to        custom mirror: replace URL prefix "from" with "to" (';'-separated
#                              for several)
#   -GithubProxy <url>         prefix GitHub downloads (MieLib jar and the Temurin JRE),
#                              e.g. https://ghfast.top/
#   -AdoptiumApi <url>         Adoptium API base for the JRE (default https://api.adoptium.net/v3)
#   -NoFallback                never fall back to the official URLs
#   Every mod is sha512-checked against the manifest, whatever the source.
#
# Environment:
#   HEXCLI_ACCEPT_EULA=1       same as -y
#   HEXCLI_MIRROR=<name>       same as -Mirror
#   HEXCLI_URL_REWRITE=a=b;c=d same as -UrlRewrite
#   HEXCLI_GITHUB_PROXY=<url>  same as -GithubProxy
#   HEXCLI_ADOPTIUM_API=<url>  same as -AdoptiumApi
#   HEXCLI_MIELIB_JAR=<path>   shorthand for -ModOverride mielib=<path>
#   HEXCLI_PROGRESS=0          hide download progress bars

[CmdletBinding()]
param(
    [switch]$y,
    [string]$Dir = "server",
    [string]$Manifest = "",
    [string]$ModOverride = "",
    [string]$Java = "",
    [Alias("DownloadSource")]
    [string]$Mirror = "",
    [string[]]$UrlRewrite = @(),
    [string]$GithubProxy = "",
    [string]$AdoptiumApi = "",
    [switch]$NoFallback
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is much faster without the progress bar
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$Adoptium = if ($AdoptiumApi) { $AdoptiumApi } elseif ($env:HEXCLI_ADOPTIUM_API) { $env:HEXCLI_ADOPTIUM_API } else { "https://api.adoptium.net/v3" }
$Adoptium = $Adoptium.TrimEnd("/")
$Runtime = Join-Path $Root "runtime"
if (-not $Java -and $env:HEXCLI_JAVA) { $Java = $env:HEXCLI_JAVA }
if (-not $Mirror -and $env:HEXCLI_MIRROR) { $Mirror = $env:HEXCLI_MIRROR }
if (-not $GithubProxy -and $env:HEXCLI_GITHUB_PROXY) { $GithubProxy = $env:HEXCLI_GITHUB_PROXY }
$Rewrites = @()
foreach ($item in $UrlRewrite) { $Rewrites += ($item -split '[;,]' | Where-Object { $_.Trim() }) }

$Accept = $y.IsPresent -or $env:HEXCLI_ACCEPT_EULA -eq "1" -or $env:HEXCLI_ACCEPT_EULA -eq "true"

function Get-JavaMajor([string]$Exe) {
    # java -version prints to stderr. Under Windows PowerShell 5.1 with
    # $ErrorActionPreference = "Stop", redirected native stderr becomes a terminating
    # NativeCommandError, so relax it locally and stringify every line.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $text = (& $Exe -version 2>&1 | ForEach-Object { "$_" }) -join "`n"
        if ($text -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch {
        return 0
    } finally {
        $ErrorActionPreference = $previous
    }
    return 0
}

# Java 17+? Accepts java.exe or a JDK/JRE home directory; returns the full java.exe path or $null.
function Resolve-UsableJava([string]$Path) {
    if (-not $Path -or -not (Test-Path $Path)) { return $null }
    $exe = $Path
    if (Test-Path $Path -PathType Container) {
        $exe = Join-Path $Path "bin\java.exe"
        if (-not (Test-Path $exe)) { return $null }
    }
    $exe = (Resolve-Path $exe).Path
    if ((Get-JavaMajor $exe) -ge 17) { return $exe }
    return $null
}

# Streams a URL to a file with a console progress bar (and Write-Progress).
function Save-WithProgress([string]$Url, [string]$OutFile) {
    $request = [System.Net.HttpWebRequest]::Create($Url)
    $request.UserAgent = "xm1221-MieLib-hexcli-installer/1.0"
    $request.Timeout = 30000
    $request.ReadWriteTimeout = 60000
    $response = $request.GetResponse()
    try {
        $total = $response.ContentLength
        $in = $response.GetResponseStream()
        $out = [System.IO.File]::Create($OutFile)
        try {
            $buffer = New-Object byte[] 65536
            $done = [long]0
            $last = [DateTime]::MinValue
            $start = [DateTime]::UtcNow
            $show = $env:HEXCLI_PROGRESS -ne "0"
            while (($n = $in.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $out.Write($buffer, 0, $n)
                $done += $n
                if ($show -and ([DateTime]::UtcNow - $last).TotalMilliseconds -ge 200) {
                    $last = [DateTime]::UtcNow
                    $mb = "{0:N1}" -f ($done / 1MB)
                    if ($total -gt 0) {
                        $pct = [int](100 * $done / $total)
                        $filled = [int](30 * $done / $total)
                        $bar = ("=" * $filled) + (" " * (30 - $filled))
                        $totalMb = "{0:N1}" -f ($total / 1MB)
                        Write-Host -NoNewline ("`r  [{0}] {1,3}%  {2}/{3} MB  " -f $bar, $pct, $mb, $totalMb)
                    } else {
                        Write-Host -NoNewline ("`r  {0} MB  " -f $mb)
                    }
                }
            }
            if ($show) {
                $secs = [Math]::Max(([DateTime]::UtcNow - $start).TotalSeconds, 0.001)
                $summary = "`r  done {0:N1} MB in {1:N1} s ({2:N1} MB/s)" -f ($done / 1MB), $secs, ($done / 1MB / $secs)
                Write-Host ($summary + (" " * 30))
            }
        } finally {
            $out.Close()
            $in.Close()
        }
    } finally {
        $response.Close()
    }
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
    $JavaExe = Resolve-UsableJava $Java
    if (-not $JavaExe) { throw "-Java / HEXCLI_JAVA is not a Java 17+ java.exe or JDK/JRE home: $Java" }
    Write-Host "using Java from -Java / HEXCLI_JAVA: $JavaExe"
}
if (-not $JavaExe -and $env:JAVA_HOME) {
    $JavaExe = Resolve-UsableJava $env:JAVA_HOME
    if ($JavaExe) { Write-Host "using Java from JAVA_HOME: $JavaExe" }
    else { Write-Host "JAVA_HOME ($env:JAVA_HOME) is not Java 17+; ignoring it" }
}
if (-not $JavaExe) {
    $JavaExe = Find-RuntimeJava
    if ($JavaExe) { Write-Host "using the private runtime: $JavaExe" }
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
        $urls = @()
        if ($GithubProxy -and $package.link -and $package.link.StartsWith("https://github.com/")) {
            $urls += ($GithubProxy.TrimEnd("/") + "/" + $package.link)
        }
        if ($urls.Count -eq 0 -or -not $NoFallback) {
            $urls += "$Adoptium/binary/latest/17/ga/windows/$arch/jre/hotspot/normal/eclipse"
        }
        if (@("bmclapi", "cn", "china", "mcim") -contains $Mirror.ToLowerInvariant() -and -not $GithubProxy -and -not $AdoptiumApi -and -not $env:HEXCLI_ADOPTIUM_API) {
            Write-Host "note: BMCLAPI has no Java mirror; for a faster JRE download use -GithubProxy <url>, -AdoptiumApi <url> or -Java"
        }
        $got = $false
        foreach ($url in $urls) {
            Write-Host "downloading $url"
            try {
                Save-WithProgress $url $archive
                $hash = (Get-FileHash -Algorithm SHA256 -Path $archive).Hash.ToLowerInvariant()
                if ($hash -eq $package.checksum.ToLowerInvariant()) { $got = $true; break }
                Write-Host "  checksum mismatch; trying the next source"
            } catch {
                Write-Host "  failed: $($_.Exception.Message); trying the next source"
            }
        }
        if (-not $got) { throw "could not download a verified Temurin JRE" }

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
if ($Mirror) { $installerArgs += @("--mirror", $Mirror) }
if ($GithubProxy) { $installerArgs += @("--github-proxy", $GithubProxy) }
if ($NoFallback) { $installerArgs += "--no-fallback" }
foreach ($rw in $Rewrites) { $installerArgs += @("--url-rewrite", $rw.Trim()) }

Write-Host ""
Write-Host "running the HexCLI installer..."
# The installer reports errors on stderr; keep PowerShell 5.1 from turning them into a
# terminating error before we can read the exit code.
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $JavaExe -jar $installerJar @installerArgs
$installerExit = $LASTEXITCODE
$ErrorActionPreference = $previous
if ($installerExit -ne 0) { throw "the HexCLI installer failed (exit code $installerExit)" }

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
