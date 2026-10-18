# ============================================================
# LANLinkshopping Local Environment Launcher (PowerShell)
#   Called by the local launcher .bat files (ASCII thin wrappers).
#   Reads .env, starts Docker base services, backend jar, frontend dev server.
#   Env vars are passed via process inheritance (safe for & in JDBC URL).
#   Keep this file 100% ASCII: PowerShell 5 reads no-BOM scripts as ANSI.
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Say($msg, $color) {
    if ($color) { Write-Host $msg -ForegroundColor $color } else { Write-Host $msg }
}

Say '=========================================='
Say '  LANLinkshopping - Local Launcher'
Say '=========================================='
Say ''

# ---------- 1. Load .env ----------
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) {
    Say '[!] .env not found. Copy .env.example to .env and fill in MySQL password + sandbox token.' 'Red'
    exit 1
}
$vars = @{}
Get-Content $envFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $i = $line.IndexOf('=')
        if ($i -gt 0) {
            $k = $line.Substring(0, $i).Trim()
            $v = $line.Substring($i + 1).Trim()
            $vars[$k] = $v
            [Environment]::SetEnvironmentVariable($k, $v, 'Process')
        }
    }
}
if (-not $env:SPRING_DATASOURCE_PASSWORD) {
    Say '[!] SPRING_DATASOURCE_PASSWORD is not set in .env' 'Red'
    exit 1
}
Say '[i] .env loaded (DB + sandbox + mail variables).'

# ---------- 2. Docker base services (Mailpit + support sandbox) ----------
Say ''
Say '[1/4] Starting Docker base services (Mailpit + support sandbox) ...'
$composeFile = Join-Path $root 'docker-compose.local.yml'
# External commands write progress to stderr; do NOT let PS treat that as an error.
$oldEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
docker compose -f $composeFile up -d --build 2>&1 | ForEach-Object { Write-Host $_ }
$ErrorActionPreference = $oldEAP
if ($LASTEXITCODE -ne 0) {
    Say '[!] Docker base services failed. Is Docker Desktop running?' 'Red'
    exit 1
}

# ---------- 3. Backend ----------
Say ''
Say '[2/4] Starting backend -> http://localhost:8080/api'
$backendDir = Join-Path $root 'backend'
$jar = Join-Path $backendDir 'target\lanlink-shopping.jar'
if (Test-Path $jar) {
    Say '[i] Using built jar: backend\target\lanlink-shopping.jar'
    Start-Process java -ArgumentList @('-jar', $jar, '--spring.profiles.active=dev') `
        -WorkingDirectory $backendDir `
        -RedirectStandardOutput (Join-Path $root 'cloud\logs\launcher-backend.out.log') `
        -RedirectStandardError  (Join-Path $root 'cloud\logs\launcher-backend.err.log')
} else {
    Say '[i] Jar not found, building + running via Maven (first run is slow) ...'
    # mvn is mvn.cmd - must be launched through cmd, otherwise Start-Process cannot resolve it
    Start-Process cmd -ArgumentList '/c', 'mvn -f backend\pom.xml spring-boot:run "-Dspring-boot.run.profiles=dev"' `
        -WorkingDirectory $root
}

Say '[i] Waiting for backend to initialize (about 25s) ...'
Start-Sleep -Seconds 25

# ---------- 4. Frontend ----------
Say ''
Say '[3/4] Starting frontend -> http://localhost:5173'
$frontendDir = Join-Path $root 'frontend'
if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
    Say '[i] node_modules missing, running npm install (first run is slow) ...'
    Start-Process cmd -ArgumentList '/c', 'npm install --no-audit --no-fund && npm run dev' `
        -WorkingDirectory $frontendDir
} else {
    Start-Process cmd -ArgumentList '/c', 'npm run dev' -WorkingDirectory $frontendDir
}

# ---------- 5. Health check + summary ----------
Start-Sleep -Seconds 8
Say ''
Say '[4/4] Done. Health check:'
$backendStatus = 'DOWN'
try {
    $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/actuator/health' -UseBasicParsing -TimeoutSec 5
    if ($r.StatusCode -eq 200) { $backendStatus = 'UP (200)' }
} catch { $backendStatus = "DOWN ($($_.Exception.Message))" }
Say "   Backend   http://localhost:8080/api      -> $backendStatus" $(if ($backendStatus.StartsWith('UP')) { 'Green' } else { 'Yellow' })

$frontendStatus = 'not responding yet'
try {
    $r = Invoke-WebRequest -Uri 'http://localhost:5173' -UseBasicParsing -TimeoutSec 5
    if ($r.StatusCode -eq 200) { $frontendStatus = 'UP (200)' }
} catch { $frontendStatus = 'starting (check the frontend window)' }
Say "   Frontend  http://localhost:5173          -> $frontendStatus"
Say "   Mailpit   http://localhost:8081          (inbox)"
Say "   Sandbox   http://127.0.0.1:18080/health"
Say ''
Say '   Demo accounts (password 123456):'
Say '     admin    13800000000'
Say '     buyer    13900000001'
Say '     merchant 13700000002'
Say ''
Say '   Stop: close the backend/frontend windows, then run:'
Say '     docker compose -f docker-compose.local.yml down'
Say ''
Say '   Backend log: cloud\logs\launcher-backend.out.log / .err.log'
