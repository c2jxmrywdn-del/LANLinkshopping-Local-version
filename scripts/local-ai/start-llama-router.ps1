# ============================================================
#  LANLink local llama.cpp router launcher (ASCII only)
#  Starts ONE llama-server in ROUTER mode on 127.0.0.1:8123 with
#  two presets on the same GPU (RTX 4070 8GB, --models-max 1):
#    - qwen2.5-7b : Qwen2.5-7B-Instruct Q4_K_M  (text)
#    - qwen2.5-vl : Qwen2.5-VL-7B-Instruct Q4_K_M + mmproj (vision)
#  The router loads/switches models on demand, so the AI gateway can
#  call either model by name through one OpenAI-compatible endpoint.
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File scripts/local-ai/start-llama-router.ps1
#    powershell -ExecutionPolicy Bypass -File scripts/local-ai/start-llama-router.ps1 -Status
#    powershell -ExecutionPolicy Bypass -File scripts/local-ai/start-llama-router.ps1 -Stop
#
#  Env overrides: LLAMA_DIR, LLAMA_SERVER_PORT, LLAMA_MODELS_MAX
# ============================================================
param(
    [string]$LlamaDir = $(if ($env:LLAMA_DIR) { $env:LLAMA_DIR } else { 'D:\Docker\toolbox\llama.cpp' }),
    [string]$BindHost = '127.0.0.1',
    [int]$Port = $(if ($env:LLAMA_SERVER_PORT) { [int]$env:LLAMA_SERVER_PORT } else { 8123 }),
    [int]$ModelsMax = $(if ($env:LLAMA_MODELS_MAX) { [int]$env:LLAMA_MODELS_MAX } else { 1 }),
    [switch]$Status,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$server = Join-Path $LlamaDir 'llama-bin\llama-server.exe'
$modelsDir = Join-Path $LlamaDir 'models'
$presetPath = Join-Path $LlamaDir 'router-preset.ini'
$logPath = Join-Path $LlamaDir 'router-server.log'
$errPath = Join-Path $LlamaDir 'router-server.err.log'

$textModel = Join-Path $modelsDir 'Qwen2.5-7B-Instruct-Q4_K_M.gguf'
$vlModel = Join-Path $modelsDir 'Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf'
$mmproj = Join-Path $modelsDir 'mmproj-F16.gguf'

function Get-RouterModels {
    try {
        $raw = & curl.exe -s --max-time 5 "http://$BindHost`:$Port/v1/models"
        if (-not $raw) { return $null }
        $json = $raw | ConvertFrom-Json
        return @($json.data)
    } catch {
        return $null
    }
}

function Stop-LlamaServers {
    $procs = Get-Process -Name 'llama-server' -ErrorAction SilentlyContinue
    if ($procs) {
        $procs | Stop-Process -Force
        Write-Host "[router] stopped $($procs.Count) llama-server process(es)"
    }
    Start-Sleep -Seconds 2
}

function Write-PresetFile {
    if (-not (Test-Path $textModel)) { throw "text model not found: $textModel" }
    if (-not (Test-Path $vlModel)) { throw "VL model not found: $vlModel" }
    if (-not (Test-Path $mmproj)) { throw "mmproj not found: $mmproj" }
    $lines = @(
        '[*]',
        'jinja = 1',
        'log-colors = off',
        'cache-type-k = q8_0',
        'cache-type-v = q8_0',
        'flash-attn = on',
        'n-gpu-layers = 99',
        '',
        '[qwen2.5-7b]',
        ('model = ' + $textModel),
        'ctx-size = 16384',
        'rope-scaling = yarn',
        'rope-scale = 4',
        'yarn-orig-ctx = 4096',
        '',
        '[qwen2.5-vl]',
        ('model = ' + $vlModel),
        ('mmproj = ' + $mmproj),
        'ctx-size = 8192',
        'image-min-tokens = 1024',
        ''
    )
    Set-Content -Path $presetPath -Value $lines -Encoding ASCII
    Write-Host "[router] preset written: $presetPath"
}

if ($Status) {
    $models = Get-RouterModels
    if (-not $models) {
        Write-Host "[router] NOT running at http://$BindHost`:$Port"
        exit 1
    }
    Write-Host "[router] running at http://$BindHost`:$Port"
    foreach ($m in $models) {
        $state = 'unknown'
        if ($m.status -and $m.status.value) { $state = $m.status.value }
        Write-Host ("  - {0} ({1})" -f $m.id, $state)
    }
    exit 0
}

if ($Stop) {
    Stop-LlamaServers
    Write-Host "[router] stopped"
    exit 0
}

Write-PresetFile
Stop-LlamaServers

$args = @(
    '--models-preset', $presetPath,
    '--host', $BindHost,
    '--port', "$Port",
    '--models-max', "$ModelsMax"
)
Write-Host ("[router] starting llama-server router on http://{0}:{1} (models-max={2})" -f $BindHost, $Port, $ModelsMax)
$proc = Start-Process -FilePath $server -ArgumentList $args -WorkingDirectory $LlamaDir `
    -RedirectStandardOutput $logPath -RedirectStandardError $errPath `
    -WindowStyle Hidden -PassThru
Write-Host ("[router] pid {0}, logs: {1}" -f $proc.Id, $logPath)

$deadline = (Get-Date).AddSeconds(90)
$models = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) {
        Write-Host "[router] server exited with code $($proc.ExitCode); see $errPath"
        Get-Content $errPath -ErrorAction SilentlyContinue | Select-Object -Last 20
        exit 1
    }
    $models = Get-RouterModels
    if ($models) { break }
}

if (-not $models) {
    Write-Host "[router] timeout waiting for /v1/models; see $logPath"
    exit 1
}

Write-Host "[router] READY. Models (loaded on demand):"
foreach ($m in $models) {
    $state = 'unknown'
    if ($m.status -and $m.status.value) { $state = $m.status.value }
    Write-Host ("  - {0} ({1})" -f $m.id, $state)
}
Write-Host ""
Write-Host "Next: point the AI gateway at http://host.docker.internal:$Port/v1"
Write-Host "  text model  : qwen2.5-7b"
Write-Host "  vision model: qwen2.5-vl"
exit 0
