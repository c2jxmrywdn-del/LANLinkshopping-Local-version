﻿﻿﻿param(
    [string]$BackupRoot = "D:\LANLinkBackups\minio",
    [string]$Network = "shopping_default",
    [string]$Image = "bitnamilegacy/minio-client:latest",
    [string]$MinioContainer = "lanlink-minio",
    [int]$Keep = 14
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $repoRoot ".env"

function Read-DotEnv([string]$path) {
    $map = @{}
    Get-Content -Path $path | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $map[$matches[1]] = $matches[2].Trim()
        }
    }
    return $map
}

if (-not (Test-Path $envFile)) { throw "未找到 $envFile" }
$envMap = Read-DotEnv $envFile
$user = $envMap["MINIO_ROOT_USER"]
$pass = $envMap["MINIO_ROOT_PASSWORD"]
if (-not $user -or -not $pass) { throw ".env 缺少 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$target = Join-Path $BackupRoot $stamp
New-Item -ItemType Directory -Force -Path $target | Out-Null

Write-Host "[backup] $stamp -> $target"
$dockerArgs = @(
    "run", "--rm", "--network", $Network,
    "-v", "${target}:/backup",
    "-e", "MINIO_USER=$user",
    "-e", "MINIO_PASS=$pass",
    "--entrypoint", "/bin/sh",
    $Image,
    "-c", 'mc alias set src http://minio:9000 "$MINIO_USER" "$MINIO_PASS" && mc mirror --overwrite src/products /backup/products && mc ls --recursive src/products | wc -l > /backup/object-count.txt'
)
& docker @dockerArgs
if ($LASTEXITCODE -ne 0) { throw "mc mirror 备份失败，退出码 $LASTEXITCODE" }

$backupCount = [int]((Get-Content -Path (Join-Path $target "object-count.txt") -Raw).Trim())
$liveCount = [int]((& docker exec $MinioContainer mc ls --recursive local/products | Measure-Object).Count)
if ($backupCount -ne $liveCount) {
    throw "备份对象数不一致：backup=$backupCount live=$liveCount"
}

$manifest = [ordered]@{
    createdAt = (Get-Date).ToString("s")
    objectCount = $backupCount
    backupPath = $target
    minioContainer = $MinioContainer
}
$manifest | ConvertTo-Json | Set-Content -Path (Join-Path $target "manifest.json") -Encoding UTF8

$old = Get-ChildItem -Path $BackupRoot -Directory |
    Where-Object { $_.Name -match '^\d{8}-\d{6}$' } |
    Sort-Object Name -Descending |
    Select-Object -Skip $Keep
foreach ($dir in $old) {
    Write-Host "[backup] 清理旧备份 $($dir.Name)"
    Remove-Item -Path $dir.FullName -Recurse -Force
}

Write-Host "[backup] 完成：对象数 $backupCount，目录 $target"
