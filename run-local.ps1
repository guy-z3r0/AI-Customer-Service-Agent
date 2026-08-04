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
Write-Host ""

# --- The backend, with its own embedded database -----------------------------
Write-Host "Starting the backend and its database..." -ForegroundColor Cyan
$backend = Start-Process -PassThru -FilePath "cmd.exe" -ArgumentList @(
    "/c", "title AI Agent - backend && set JAVA_HOME=$jdk && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
) -WorkingDirectory (Join-Path $root "java-backend")

# --- The voice server --------------------------------------------------------
Write-Host "Starting the voice server..." -ForegroundColor Cyan
$voice = Start-Process -PassThru -FilePath "cmd.exe" -ArgumentList @(
    "/c", "title AI Agent - voice && set JAVA_BASE_URL=http://127.0.0.1:8080 && python -m uvicorn server:app --host 127.0.0.1 --port 8090"
) -WorkingDirectory (Join-Path $root "python-voice")

# --- Wait for the panel, then open it ----------------------------------------
Write-Host ""
Write-Host "Waiting for the panel (the first run downloads PostgreSQL, so give it a minute)..."
$panel = "http://127.0.0.1:8080"
$ready = $false
foreach ($attempt in 1..90) {
    Start-Sleep -Seconds 2
    try {
        $response = Invoke-WebRequest -Uri "$panel/api/health" -TimeoutSec 3 -UseBasicParsing
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
