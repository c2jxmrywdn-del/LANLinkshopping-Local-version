# LANLinkshopping local AI: WSL / Docker / GPU health helper
# Usage (PowerShell 5.1+):
#   scripts/local-ai/wsl-health.ps1              # status only
#   scripts/local-ai/wsl-health.ps1 -Clean       # drop WSL clean page cache (fast, non-disruptive)
#   scripts/local-ai/wsl-health.ps1 -Warm        # send a tiny text request to llama.cpp router
# Notes:
#   - VmmemWSL high memory is normally Linux page cache (reclaimable) + WSL VM reservation,
#     NOT a CPU spin. CPU is usually <2% at idle.
#   - Do NOT run swapoff during active development: paging in 4-5GB can freeze the VM for
#     ~1-2 minutes and makes in-flight browser requests fail with "Network Error".
param(
    [switch]$Clean,
    [switch]$Warm,
    [string]$Gateway = "http://127.0.0.1:8090"
)

$ErrorActionPreference = "Continue"
Write-Output "===== Windows VmmemWSL ====="
$vm = Get-Process VmmemWSL -ErrorAction SilentlyContinue
if ($vm) {
    Write-Output ("WorkingSet: {0:N2} GB | Private: {1:N2} GB | PID: {2}" -f ($vm.WorkingSet64 / 1GB), ($vm.PrivateMemorySize64 / 1GB), $vm.Id)
} else {
    Write-Output "VmmemWSL not running"
}

Write-Output ""
Write-Output "===== WSL (docker-desktop distro) ====="
wsl -d docker-desktop -- sh -c "free -m; echo '---'; cat /proc/loadavg; echo '---'; swapon --show 2>/dev/null; echo '---'; grep -E 'MemTotal|MemFree|MemAvailable|Cached|SwapTotal|SwapFree' /proc/meminfo"
if ($LASTEXITCODE -ne 0) { Write-Output "wsl docker-desktop unavailable" }

Write-Output ""
Write-Output "===== LANLink containers ====="
docker ps --filter "name=shopping-" --filter "name=lanlink-" --format "{{.Names}}`t{{.Status}}`t{{.Image}}"

Write-Output ""
Write-Output "===== GPU ====="
nvidia-smi --query-gpu=name,memory.used,memory.total,utilization.gpu --format=csv

if ($Clean) {
    Write-Output ""
    Write-Output "===== Cleaning WSL clean page cache (safe) ====="
    wsl -d docker-desktop -- sh -c "sync; echo 1 > /proc/sys/vm/drop_caches; echo dropped; free -m"
    $vm2 = Get-Process VmmemWSL -ErrorAction SilentlyContinue
    if ($vm2) { Write-Output ("VmmemWSL WorkingSet after clean: {0:N2} GB" -f ($vm2.WorkingSet64 / 1GB)) }
}

if ($Warm) {
    Write-Output ""
    Write-Output "===== Warming text model via gateway ====="
    $body = '{"model":"qwen2.5-7b","messages":[{"role":"user","content":"ping"}],"max_tokens":8}'
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
    $t = Get-Date
    try {
        $resp = curl.exe -s --max-time 180 -X POST "$Gateway/v1/chat/completions" -H "Content-Type: application/json" --data-binary "@$tmp"
        $ms = [math]::Round(((Get-Date) - $t).TotalMilliseconds)
        if ($resp -match 'choices') { Write-Output "warm OK (${ms} ms)" } else { Write-Output "warm response: $resp" }
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

Write-Output ""
Write-Output "Tips:"
Write-Output "  - VmmemWSL CPU > 5% at idle for long periods = investigate 'wsl -d docker-desktop -- top' and docker stats."
Write-Output "  - Page cache from model files makes VmmemWSL look large; -Clean drops it but it rebuilds on next model load."
Write-Output "  - For more host RAM, disable unused Docker Desktop extensions / Kubernetes, or lower .wslconfig memory."
