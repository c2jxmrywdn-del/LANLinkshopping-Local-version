# ============================================================
# LANLinkshopping Local Environment Launcher (PowerShell)
#   Called by start.bat / local-launcher bat wrappers (ASCII thin wrappers).
#   Reads .env, verifies RSA-2048 / JWE crypto, starts Docker base
#   services, monitors backend and frontend, prevents duplicate runs.
#   Env vars are passed via process inheritance (safe for & in JDBC URL).
#   Keep this file 100% ASCII: PowerShell 5 reads no-BOM scripts as ANSI.
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[System.Net.WebRequest]::DefaultWebProxy = New-Object System.Net.WebProxy
Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Say($msg, $color) {
    if ($color) { Write-Host $msg -ForegroundColor $color } else { Write-Host $msg }
}

function Test-HttpOk($url, $timeoutSec = 2) {
    try {
        $targetUrl = if ($url -match '^http://localhost(?::(\d+))?(.*)$') {
            $port = if ($matches[1]) { ":$($matches[1])" } else { "" }
            "http://127.0.0.1$port$($matches[2])"
        } else {
            $url
        }
        $handler = New-Object System.Net.Http.HttpClientHandler
        $handler.UseProxy = $false
        $client = New-Object System.Net.Http.HttpClient($handler)
        $client.Timeout = [TimeSpan]::FromSeconds($timeoutSec)
        $resp = $client.GetAsync($targetUrl).GetAwaiter().GetResult()
        $code = [int]$resp.StatusCode
        $client.Dispose()
        $handler.Dispose()
        if ($code -ge 200 -and $code -lt 400) { return $true }
        if ($code -eq 503 -and $url.Contains('actuator/health')) { return $true }
        return $false
    } catch {
        return $false
    }
}

function Check-Service($url, $timeoutSec = 2) {
    if ($url.Contains('localhost')) {
        $v4Url = $url.Replace('localhost', '127.0.0.1')
        if (Test-HttpOk $v4Url $timeoutSec) { return $true }
    }
    return (Test-HttpOk $url $timeoutSec)
}

# Read a URL and return the HTTP status code plus body (used for smoke tests).
function Get-HttpProbe($url, $timeoutSec = 10) {
    $result = @{ Ok = $false; Status = 0; Body = '' }
    try {
        $handler = New-Object System.Net.Http.HttpClientHandler
        $handler.UseProxy = $false
        $client = New-Object System.Net.Http.HttpClient($handler)
        $client.Timeout = [TimeSpan]::FromSeconds($timeoutSec)
        $resp = $client.GetAsync($url).GetAwaiter().GetResult()
        $result.Status = [int]$resp.StatusCode
        $result.Body = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $result.Ok = ($result.Status -ge 200 -and $result.Status -lt 300)
        $client.Dispose()
        $handler.Dispose()
    } catch {
        $result.Body = $_.Exception.Message
    }
    return $result
}

# Smoke test one JSON API: HTTP 200 AND body.code == 200 (project-wide envelope).
function Test-ApiEnvelope($url, $timeoutSec = 15) {
    $probe = Get-HttpProbe $url $timeoutSec
    if (-not $probe.Ok) {
        return @{ Ok = $false; Reason = "HTTP $($probe.Status)"; Body = $probe.Body }
    }
    if ($probe.Body -match '"code"\s*:\s*200') {
        return @{ Ok = $true; Reason = 'HTTP 200 / code 200'; Body = $probe.Body }
    }
    $short = if ($probe.Body.Length -gt 160) { $probe.Body.Substring(0, 160) } else { $probe.Body }
    return @{ Ok = $false; Reason = 'code != 200'; Body = $short }
}

# Print actionable hints for the most common backend startup failures.
function Show-BackendFailureHints($logPath) {
    if (-not (Test-Path $logPath)) { return }
    $text = Get-Content $logPath -Raw -ErrorAction SilentlyContinue
    if (-not $text) { return }
    $patterns = @(
        @{ Match = 'Data too long';        Hint = 'JWE ciphertext exceeds column width: CryptoFieldSchemaInitializer must run before DataInitializer.' },
        @{ Match = 'Unknown column';       Hint = 'Entity has a column the DB lacks: a SchemaInitializer did not run before JPA/MyBatis query.' },
        @{ Match = 'BeanCurrentlyInCreation|ConflictingBeanDefinition'; Hint = 'Spring bean cycle: add @Lazy on the back-reference.' },
        @{ Match = 'FlywayException|Migration'; Hint = 'Flyway failed: check db-migrations version ordering and baseline-version.' },
        @{ Match = 'Access denied for user'; Hint = 'DB credentials wrong: verify MYSQL_ROOT_PASSWORD / SPRING_DATASOURCE_PASSWORD in .env.' },
        @{ Match = 'Communications link failure'; Hint = 'MySQL not reachable: start Docker base services first.' },
        @{ Match = 'Port 8080 was already in use'; Hint = 'Port 8080 busy: stop the other process or the Docker backend container.' }
    )
    foreach ($p in $patterns) {
        if ($text -match $p.Match) {
            Say "    [!] $($p.Hint)" 'Yellow'
        }
    }
}

Say '===================================================='
Say '  LANLinkshopping - Local Development Launcher'
Say '  (RSA-2048 Crypto Baseline + Preload Engine Ready)'
Say '===================================================='
Say ''

# ---------- 0. Ensure Logs Directory Exists ----------
$logsDir = Join-Path $root 'cloud\logs'
if (-not (Test-Path $logsDir)) {
    New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
}

# ---------- 1. Load .env and Auto-Provision Dev Keys ----------
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) {
    Say '[!] .env not found. Copy .env.example to .env and fill in configuration.' 'Red'
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

# Check if MySQL password is set; fallback to MYSQL_ROOT_PASSWORD if needed
if (-not $env:SPRING_DATASOURCE_PASSWORD) {
    if ($env:MYSQL_ROOT_PASSWORD) {
        $env:SPRING_DATASOURCE_PASSWORD = $env:MYSQL_ROOT_PASSWORD
        [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD', $env:MYSQL_ROOT_PASSWORD, 'Process')
    } else {
        Say '[!] SPRING_DATASOURCE_PASSWORD is not set in .env' 'Red'
        exit 1
    }
}

# Remap container hostnames to loopback for local host execution
if ($env:REDIS_HOST -eq 'redis') {
    $env:REDIS_HOST = '127.0.0.1'
    [Environment]::SetEnvironmentVariable('REDIS_HOST', '127.0.0.1', 'Process')
    Say '[i] Remapped REDIS_HOST from container alias "redis" to 127.0.0.1 for local host.' 'Cyan'
}
if ($env:AI_GATEWAY_BASE_URL -and $env:AI_GATEWAY_BASE_URL -match 'lanlink-ai-gateway') {
    $env:AI_GATEWAY_BASE_URL = $env:AI_GATEWAY_BASE_URL.Replace('lanlink-ai-gateway', '127.0.0.1')
    [Environment]::SetEnvironmentVariable('AI_GATEWAY_BASE_URL', $env:AI_GATEWAY_BASE_URL, 'Process')
    Say '[i] Remapped AI_GATEWAY_BASE_URL to 127.0.0.1 for local host.' 'Cyan'
}
Say '[i] .env loaded successfully.'

# RSA-2048 / JWE Security Baseline Auto-Provisioning for Dev
if (-not $env:LANLINK_CRYPTO_JWE_ACTIVE_PRIVATE_KEY -or -not $env:LANLINK_CRYPTO_JWE_ACTIVE_PUBLIC_KEY) {
    try {
        $rsa = [System.Security.Cryptography.RSA]::Create(2048)
        $privBase64 = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey())
        $pubBase64 = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())
        $devKid = "dev-rsa2048-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"

        [Environment]::SetEnvironmentVariable('LANLINK_CRYPTO_JWE_ACTIVE_KID', $devKid, 'Process')
        [Environment]::SetEnvironmentVariable('LANLINK_CRYPTO_JWE_ACTIVE_PRIVATE_KEY', $privBase64, 'Process')
        [Environment]::SetEnvironmentVariable('LANLINK_CRYPTO_JWE_ACTIVE_PUBLIC_KEY', $pubBase64, 'Process')
        [Environment]::SetEnvironmentVariable('LANLINK_CRYPTO_MIN_RSA_BITS', '2048', 'Process')
        [Environment]::SetEnvironmentVariable('LANLINK_CRYPTO_JWE_ENABLED', 'true', 'Process')

        Say '[i] Auto-generated RSA-2048 keypair for local session (JWE RSA-OAEP-256 enabled).' 'Cyan'
    } catch {
        Say '[w] RSA-2048 key auto-provisioning skipped; falling back to legacy AES-GCM.' 'Yellow'
    }
} else {
    Say '[i] Using configured RSA-2048 JWE cryptographic keys from .env.' 'Green'
}

# Auto-provision proxy secret for local Vite proxy if missing
if (-not $env:SUPPORT_PROXY_SECRET) {
    $genSecret = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    [Environment]::SetEnvironmentVariable('SUPPORT_PROXY_SECRET', $genSecret, 'Process')
    Say '[i] Auto-generated local SUPPORT_PROXY_SECRET for session.' 'Cyan'
}

# ---------- 2. Docker Base Services ----------
Say ''
Say '[1/4] Starting Docker base services ...'
$composeFile = Join-Path $root 'docker-compose.local.yml'
$oldEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------------------
# Reclaim stale same-name containers before `compose up`.
#
# docker-compose.yml pins several services with `container_name:` (ai-gateway,
# llama-cpp, minio, rabbitmq...). A container with that exact name created from a
# *different* compose project (e.g. an old copy of the repo, or a detached
# `-f ai-gateway/docker-compose.yml up`) makes the whole stack fail with:
#   Conflict. The container name "/lanlink-ai-gateway" is already in use
# which aborts every remaining service, so the backend loses its dependencies and
# the launcher reports a generic "compose returned non-zero".
#
# We only remove the container when it belongs to a *different* compose project
# or has no compose project label at all; containers of THIS project are left to
# `compose up` (it reconciles them itself).
# ---------------------------------------------------------------------------
$composePath = Join-Path $root 'docker-compose.yml'
$currentProject = 'shopping'
if (Test-Path $composePath) {
    $nameLine = Select-String -Path $composePath -Pattern '^\s*name:\s*(\S+)\s*$' | Select-Object -First 1
    if ($nameLine) { $currentProject = $nameLine.Matches[0].Groups[1].Value.Trim('''"') }
}
$pinnedNames = @()
if (Test-Path $composePath) {
    $pinnedNames = Select-String -Path $composePath -Pattern '^\s*container_name:\s*(\S+)\s*$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value.Trim('''"') } | Sort-Object -Unique
}
foreach ($cname in $pinnedNames) {
    $exists = docker ps -a --filter "name=^/$cname$" --format '{{.Names}}' 2>$null
    if (-not $exists) { continue }
    $owner = docker inspect $cname --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>$null
    if ($owner -and $owner -ne '<no value>' -and $owner -ne $currentProject) {
        Say "[i] Reclaiming stale container '$cname' (owned by compose project '$owner', current project '$currentProject')..." 'Yellow'
        docker rm -f $cname 2>&1 | Out-Null
    } elseif (-not $owner -or $owner -eq '<no value>') {
        Say "[i] Reclaiming stale container '$cname' (no compose project label)..." 'Yellow'
        docker rm -f $cname 2>&1 | Out-Null
    }
}

docker compose -f $composeFile up -d 2>&1 | ForEach-Object { Write-Host $_ }
$dockerExit = $LASTEXITCODE

# Stop conflicting Docker containers if running (free ports 5173 and 8080 for local dev).
# Only backend/frontend are stopped: mysql/redis/minio/rabbitmq/ai-gateway must stay up,
# otherwise the host-run backend loses its dependencies.
$dockerFrontend = docker ps -q --filter "name=shopping-frontend" 2>$null
if ($dockerFrontend) {
    Say '[i] Stopping conflicting Docker frontend container (shopping-frontend-1) to free port 5173...' 'Yellow'
    docker stop shopping-frontend-1 2>&1 | Out-Null
}
$dockerBackend = docker ps -q --filter "name=shopping-backend" 2>$null
if ($dockerBackend) {
    Say '[i] Stopping conflicting Docker backend container (shopping-backend-1) to free port 8080...' 'Yellow'
    docker stop shopping-backend-1 2>&1 | Out-Null
}

# Host-run backend needs these container services; warn early instead of failing later.
foreach ($svc in @('shopping-mysql-1', 'shopping-redis-1', 'shopping-minio-1', 'shopping-rabbitmq-1')) {
    $running = docker ps -q --filter "name=$svc" 2>$null
    if (-not $running) {
        Say "[w] Container $svc is not running. Start base services: docker compose -f docker-compose.yml up -d mysql redis minio rabbitmq" 'Yellow'
    }
}

$ErrorActionPreference = $oldEAP
if ($dockerExit -ne 0) {
    Say '[w] Docker compose returned non-zero. If Docker is offline, mail/sandbox will be mocked.' 'Yellow'
} else {
    Say '[i] Docker base services are ready.' 'Green'
}

# ---------- 3. Backend Health Probe or Launch ----------
Say ''
Say '[2/4] Checking backend status -> http://127.0.0.1:8080/api'
$backendAlreadyUp = Check-Service 'http://127.0.0.1:8080/api/actuator/health/liveness'

$backendDir = Join-Path $root 'backend'
$jar = Join-Path $backendDir 'target\lanlink-shopping.jar'

$needsRebuild = $false
if (-not (Test-Path $jar)) {
    $needsRebuild = $true
} else {
    # Check if any Java source file is newer than the built jar
    $jarTime = (Get-Item $jar).LastWriteTime
    $newerSrc = Get-ChildItem -Path (Join-Path $backendDir 'src\main\java') -Recurse -Filter *.java |
        Where-Object { $_.LastWriteTime -gt $jarTime } | Select-Object -First 1
    if ($newerSrc) {
        Say "[i] Detected updated source code ($($newerSrc.Name)). Recompiling backend jar..." 'Cyan'
        $needsRebuild = $true
    }
}

if ($backendAlreadyUp -and -not $needsRebuild) {
    Say '[i] Backend service is ALREADY running and healthy (UP). Skipping duplicate launch.' 'Green'
} else {
    if ($needsRebuild) {
        Say '[i] Compiling backend via Maven (skip tests for rapid launch) ...'
        $mvnExit = Start-Process cmd -ArgumentList '/c', 'mvn -f backend\pom.xml package -DskipTests' -WorkingDirectory $root -Wait -PassThru
        if ($mvnExit.ExitCode -ne 0) {
            Say '[!] Backend Maven build failed. Check compilation errors above.' 'Red'
            exit 1
        }
    }

    # If backend was already up but needs rebuild, stop the existing local process first
    if ($backendAlreadyUp) {
        Say '[i] Stopping older backend process on port 8080 to reload freshly compiled jar...' 'Yellow'
        Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
            ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 1
    }

    Say '[i] Launching backend: lanlink-shopping.jar (dev profile)...'
    $backendOut = Join-Path $logsDir 'launcher-backend.out.log'
    $backendErr = Join-Path $logsDir 'launcher-backend.err.log'

    "" | Set-Content $backendOut -Force
    "" | Set-Content $backendErr -Force

    $backendProc = Start-Process java -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $jar, '--spring.profiles.active=dev') `
        -WorkingDirectory $backendDir `
        -RedirectStandardOutput $backendOut `
        -RedirectStandardError  $backendErr `
        -PassThru

    Say '[i] Waiting for backend to initialize (polling /actuator/health) ...'
    $backendReady = $false
    # 90s budget: Flyway + 29 SchemaInitializers + AI KB bootstrap routinely exceed 40s on cold start.
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 1
        if ($i % 10 -eq 0) { Write-Host -NoNewline "[$i s] " }
        Write-Host -NoNewline '.'

        if ($backendProc.HasExited) {
            Write-Host ''
            Say "[!] Backend process terminated unexpectedly (Exit Code: $($backendProc.ExitCode))." 'Red'
            Say '--- Last lines of cloud\logs\launcher-backend.out.log ---' 'Yellow'
            if (Test-Path $backendOut) {
                Get-Content $backendOut -Tail 20 | ForEach-Object { Write-Host $_ }
            }
            Say '--- Last lines of cloud\logs\launcher-backend.err.log ---' 'Yellow'
            if (Test-Path $backendErr) {
                Get-Content $backendErr -Tail 15 | ForEach-Object { Write-Host $_ }
            }
            Show-BackendFailureHints $backendOut
            Show-BackendFailureHints $backendErr
            break
        }

        if (Check-Service 'http://127.0.0.1:8080/api/actuator/health/liveness') {
            $backendReady = $true
            break
        }
    }
    Write-Host ''
    if ($backendReady) {
        Say '[i] Backend initialized successfully!' 'Green'
    } elseif (-not $backendProc.HasExited) {
        Say '[i] Backend is still initializing in background.' 'Yellow'
        Say '    The launcher will continue; re-check http://127.0.0.1:8080/api/actuator/health in a minute.' 'Yellow'
        Show-BackendFailureHints $backendOut
    }
}

# ---------- 4. Frontend Health Probe or Launch ----------
Say ''
Say '[3/4] Checking frontend dev server -> http://127.0.0.1:5173'
$frontendAlreadyUp = Check-Service 'http://127.0.0.1:5173'

if ($frontendAlreadyUp) {
    Say '[i] Frontend dev server is ALREADY running and healthy (UP).' 'Green'
} else {
    $frontendDir = Join-Path $root 'frontend'
    if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
        Say '[i] frontend/node_modules missing, running npm install (first run is slow) ...' 'Yellow'
        Start-Process cmd -ArgumentList '/k', 'title LANLink Frontend Dev Server && npm install --no-audit --no-fund && npm run dev' -WorkingDirectory $frontendDir
    } else {
        Say '[i] Launching Vite dev server in dedicated window ...' 'Cyan'
        Start-Process cmd -ArgumentList '/k', 'title LANLink Frontend Dev Server && npm run dev' -WorkingDirectory $frontendDir
    }

    Say '[i] Waiting for frontend dev server to initialize (polling http://127.0.0.1:5173) ...'
    $frontendReady = $false
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Seconds 1
        Write-Host -NoNewline '.'
        if (Check-Service 'http://127.0.0.1:5173') {
            $frontendReady = $true
            break
        }
    }
    Write-Host ''
    if ($frontendReady) {
        Say '[i] Frontend dev server initialized successfully!' 'Green'
    } else {
        Say '[w] Frontend server is taking longer than expected to start.' 'Yellow'
        Say '    Check the "LANLink Frontend Dev Server" terminal window for logs.' 'Yellow'
    }
}

# Automatically open default browser to frontend
if (Check-Service 'http://127.0.0.1:5173') {
    Say '[i] Opening browser to http://127.0.0.1:5173 ...' 'Cyan'
    try {
        Start-Process 'http://127.0.0.1:5173'
    } catch {
        Say '[w] Could not automatically open browser. Please navigate to http://127.0.0.1:5173 manually.' 'Yellow'
    }
}

# ---------- 5. Health Check & Diagnostic Summary ----------
Say ''
Say '[4/4] System Diagnostic Summary:'

$bStatus = if (Check-Service 'http://127.0.0.1:8080/api/actuator/health/liveness') { 'UP (200 OK)' } else { 'STARTING (see cloud\logs\launcher-backend.out.log)' }
Say "   Backend API      http://127.0.0.1:8080/api          -> $bStatus" $(if ($bStatus.StartsWith('UP')) { 'Green' } else { 'Yellow' })

$fStatus = if (Check-Service 'http://127.0.0.1:5173') { 'UP (200 OK)' } else { 'STARTING (check frontend terminal)' }
Say "   Frontend Web     http://127.0.0.1:5173              -> $fStatus" $(if ($fStatus.StartsWith('UP')) { 'Green' } else { 'Yellow' })

# ---------- 5.1 API Smoke Tests (catch "service abnormal" toasts before users do) ----------
if (Check-Service 'http://127.0.0.1:8080/api/actuator/health/liveness') {
    Say ''
    Say '   API smoke tests:'
    $smokeTargets = @(
        @{ Name = 'Home industries';  Url = 'http://127.0.0.1:8080/api/home/industries' },
        @{ Name = 'Home hot products'; Url = 'http://127.0.0.1:8080/api/home/hot?limit=8' },
        @{ Name = 'Campaign list';    Url = 'http://127.0.0.1:8080/api/campaign/list?tab=promo' },
        @{ Name = 'Daily news';       Url = 'http://127.0.0.1:8080/api/price-monitor/news/daily?limit=6' }
    )
    $smokeFailed = @()
    foreach ($t in $smokeTargets) {
        $r = Test-ApiEnvelope $t.Url 20
        if ($r.Ok) {
            Say ("     {0,-20} OK   ({1})" -f $t.Name, $r.Reason) 'Green'
        } else {
            Say ("     {0,-20} FAIL ({1})" -f $t.Name, $r.Reason) 'Red'
            Say ("       {0}" -f $r.Body) 'DarkGray'
            $smokeFailed += $t.Name
        }
    }
    if ($smokeFailed.Count -gt 0) {
        Say "   [!] $($smokeFailed.Count) API(s) failed. The frontend will show 'service abnormal' toasts for these." 'Yellow'
        Say '       Check: docker compose -f docker-compose.yml logs --since 10m backend | Select-String ERROR' 'Yellow'
    }

    # AI inference must run on the RTX 4070 (llama.cpp), never silently on CPU.
    $aiKey = $env:AI_GATEWAY_API_KEY
    if ($aiKey) {
        $body = '{"model":"qwen2.5-7b","messages":[{"role":"user","content":"ping"}],"max_tokens":4,"use_kb":false}'
        try {
            $handler = New-Object System.Net.Http.HttpClientHandler
            $handler.UseProxy = $false
            $client = New-Object System.Net.Http.HttpClient($handler)
            $client.Timeout = [TimeSpan]::FromSeconds(120)
            $client.DefaultRequestHeaders.Add('Authorization', "Bearer $aiKey")
            $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
            $resp = $client.PostAsync('http://127.0.0.1:8090/v1/chat/completions', $content).GetAwaiter().GetResult()
            $payload = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $client.Dispose(); $handler.Dispose()
            if ($payload -match '"backend"\s*:\s*"llamacpp') {
                Say '     AI inference         OK   (llamacpp -> RTX 4070)' 'Green'
            } elseif ($payload -match '"backend"\s*:\s*"ollama') {
                Say '     AI inference         WARN (running on CPU/Ollama, expected 4070)' 'Yellow'
            } else {
                Say '     AI inference         WARN (gateway reachable but backend label unknown)' 'Yellow'
            }
        } catch {
            Say "     AI inference         FAIL ($($_.Exception.Message))" 'Yellow'
            Say '       Is the gateway up? docker compose -f ai-gateway/docker-compose.yml up -d ai-gateway' 'Yellow'
        }
    }
}

Say '   Mailpit Inbox    http://127.0.0.1:8081              (SMTP: 1025)'
Say '   Sandbox Gateway  http://127.0.0.1:18080/health'
Say '   Crypto Standard  RSA-2048 + JWE (RSA-OAEP-256 / A256GCM)' 'Green'
Say '   Preload Pipeline L1 LRU + L2 IndexedDB + In-Flight Deduplication' 'Green'
Say ''
Say '   Demo Accounts (Password: 123456):'
Say '     Admin      13800000000'
Say '     Buyer      13900000001'
Say '     Merchant   13700000002'
Say ''
Say '   Logs:'
Say '     Backend stdout : cloud\logs\launcher-backend.out.log'
Say '     Backend stderr : cloud\logs\launcher-backend.err.log'
Say ''
Say '   To stop: Close terminal windows, then run: docker compose -f docker-compose.local.yml down'
Say '===================================================='
