﻿﻿﻿param(
    [Parameter(Mandatory = $true)][string]$BackupDir,
    [string]$Network = "shopping_default",
    [string]$Image = "bitnamilegacy/minio-client:latest",
    [string]$MinioContainer = "lanlink-minio",
    [switch]$Force
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

if (-not (Test-Path $BackupDir)) { throw "备份目录不存在：$BackupDir" }
if (-not (Test-Path $envFile)) { throw "未找到 $envFile" }
$envMap = Read-DotEnv $envFile
$user = $envMap["MINIO_ROOT_USER"]
$pass = $envMap["MINIO_ROOT_PASSWORD"]
if (-not $user -or -not $pass) { throw ".env 缺少 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD" }

if (-not $Force) {
    $answer = Read-Host "恢复会用备份覆盖 MinIO 中同名对象，输入 RESTORE 继续"
    if ($answer -ne "RESTORE") { throw "已取消恢复" }
}

$dockerArgs = @(
    "run", "--rm", "--network", $Network,
    "-v", "${BackupDir}:/backup:ro",
    "-e", "MINIO_USER=$user",
    "-e", "MINIO_PASS=$pass",
    "--entrypoint", "/bin/sh",
    $Image,
    "-c", 'mc alias set src http://minio:9000 "$MINIO_USER" "$MINIO_PASS" && mc mirror --overwrite /backup/products src/products'
)
& docker @dockerArgs
if ($LASTEXITCODE -ne 0) { throw "恢复失败，退出码 $LASTEXITCODE" }

$liveCount = [int]((& docker exec $MinioContainer mc ls --recursive local/products | Measure-Object).Count)
Write-Host "[restore] 完成：当前对象数 $liveCount"
