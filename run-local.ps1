# Starts the whole app on this machine with nothing installed but Java and Python.
#
#   .\run-local.ps1
#
# Docker is the documented way to run this (see docs/SETUP.md) and is what you
# should use in production. This script is the other way in, for a laptop that
# has no Docker: it starts a real PostgreSQL as a child process of the backend,
# so the database, the migrations and the encryption all behave exactly as they
# do in the container.
#
# Two windows open — the backend and the voice server. Close either to stop it.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# --- Java 21 -----------------------------------------------------------------
# Spring Boot 3.4 is not tested against newer Java, and Hibernate fails at
# runtime on some of them, so the JDK is pinned here rather than taken from PATH.
$javaCandidates = @(
    "C:\Program Files\Java\jdk-21",
    "C:\Program Files\Eclipse Adoptium\jdk-21",
    $env:JAVA_HOME
)
$jdk = $javaCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) } | Select-Object -First 1
if (-not $jdk) {
    Write-Host "No Java 21 found. Install Temurin 21 and re-run." -ForegroundColor Red
    exit 1
}
Write-Host "Java:   $jdk"

# --- Python ------------------------------------------------------------------
$python = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $python) {
    Write-Host "No python on PATH. Install Python 3.10+ and re-run." -ForegroundColor Red
    exit 1
}
Write-Host "Python: $($python.Source)"

# The voice server is plain Python with no virtualenv, so its packages are
# either already on this machine or they are not. Missing uvicorn is the
# symptom that matters: without it the voice window dies before it prints.
& $python.Source -c "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec('uvicorn') else 1)"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Installing the voice server's Python packages (first run only)..." -ForegroundColor Cyan
    & $python.Source -m pip install -q -r (Join-Path $root "python-voice\requirements.txt")
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Those packages would not install. Fix the error above and re-run." -ForegroundColor Red
        exit 1
    }
}
Write-Host ""

# --- The backend, with its own embedded database -----------------------------
# "clean" because Maven leaves the class files of deleted sources behind, and
# Spring scans whatever is in target/: one orphan from an earlier layout is
# enough to fail the boot with a NoSuchFieldError that points at nothing.
# The windows are /k, not /c, so a crash stays on screen to be read.
#
# Every `set` here is written as set "NAME=value". Without the quotes cmd takes
# the space before the && as part of the value, so JAVA_HOME ends in a space,
# Maven looks for "…\jdk-21 \bin\java.exe" and reports that JAVA_HOME is not
# defined correctly — while pointing at a path that looks perfectly right.
Write-Host "Starting the backend and its database..." -ForegroundColor Cyan
# --- The operator login ---------------------------------------------------
# Both processes need the same pair: the backend to check it, the voice server
# to present it. Taken from .env when it is there, and otherwise generated for
# this run — so the stack still starts with no configuration, and still starts
# locked rather than open.
$panelUser = "operator"
$panelPassword = ""
$envFile = Join-Path $root ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*PANEL_USER\s*=\s*(.+?)\s*$') { $panelUser = $Matches[1] }
        if ($_ -match '^\s*PANEL_PASSWORD\s*=\s*(.+?)\s*$') { $panelPassword = $Matches[1] }
    }
}
if (-not $panelPassword) {
    $bytes = New-Object 'System.Byte[]' 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $panelPassword = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    Write-Host ""
    Write-Host "No PANEL_PASSWORD in .env — generated one for this run:" -ForegroundColor Yellow
    Write-Host "    $panelUser / $panelPassword" -ForegroundColor Yellow
    Write-Host "  The browser will ask for it. Put it in .env to keep it."
}

$backend = Start-Process -PassThru -FilePath "cmd.exe" -ArgumentList @(
    "/k", "title AI Agent - backend && set `"JAVA_HOME=$jdk`" && set `"PANEL_USER=$panelUser`" && set `"PANEL_PASSWORD=$panelPassword`" && mvn clean spring-boot:run -Dspring-boot.run.profiles=dev"
) -WorkingDirectory (Join-Path $root "java-backend")

# --- The voice server --------------------------------------------------------
Write-Host "Starting the voice server..." -ForegroundColor Cyan
$voice = Start-Process -PassThru -FilePath "cmd.exe" -ArgumentList @(
    "/k", "title AI Agent - voice && set `"JAVA_BASE_URL=http://127.0.0.1:8080`" && set `"PANEL_USER=$panelUser`" && set `"PANEL_PASSWORD=$panelPassword`" && python -m uvicorn server:app --host 127.0.0.1 --port 8090"
) -WorkingDirectory (Join-Path $root "python-voice")

# --- Wait for the panel, then open it ----------------------------------------
Write-Host ""
# Eight minutes, because a first run downloads the whole Maven dependency tree
# and then PostgreSQL. A warm machine gets here in well under one.
Write-Host "Waiting for the panel (the first run downloads Maven dependencies and"
Write-Host "PostgreSQL, which can take several minutes)..."
$panel = "http://127.0.0.1:8080"
$ready = $false
foreach ($attempt in 1..240) {
    Start-Sleep -Seconds 2
    try {
        # The liveness probe, which is the one endpoint that answers without
        # the login — everything else would come back 401 from here.
        $response = Invoke-WebRequest -Uri "$panel/api/health/live" -TimeoutSec 3 -UseBasicParsing
        if ($response.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
}

if ($ready) {
    Write-Host ""
    Write-Host "  Panel ready:  $panel" -ForegroundColor Green
    Write-Host "  Voice server: http://127.0.0.1:8090/health"
    Write-Host ""
    Write-Host "  No API key yet? Open Settings in the panel and paste a Gemini key."
    Write-Host "  Close the two console windows to stop everything."
    Start-Process $panel
} else {
    Write-Host ""
    Write-Host "The panel did not come up. Look at the backend window for the reason." -ForegroundColor Red
    Write-Host "PIDs: backend $($backend.Id), voice $($voice.Id)"
}
