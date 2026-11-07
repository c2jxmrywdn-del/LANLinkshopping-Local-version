﻿﻿﻿param(
    [string]$BackupRoot = "D:\LANLinkBackups\minio",
    [string]$MinioContainer = "lanlink-minio",
    [int]$MaxBackupHours = 26
)

$ErrorActionPreference = "Stop"
$result = [ordered]@{
    checkedAt = (Get-Date).ToString("s")
    minioReady = $false
    objectCount = 0
    latestBackup = $null
    backupAgeHours = $null
    healthy = $false
}

& docker exec $MinioContainer mc ready local | Out-Null
if ($LASTEXITCODE -eq 0) { $result.minioReady = $true }

$countOutput = & docker exec $MinioContainer mc ls --recursive local/products
$result.objectCount = [int](($countOutput | Measure-Object).Count)

if (Test-Path $BackupRoot) {
    $latest = Get-ChildItem -Path $BackupRoot -Directory |
        Where-Object { $_.Name -match '^\d{8}-\d{6}$' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($latest) {
        $result.latestBackup = $latest.FullName
        $result.backupAgeHours = [math]::Round(((Get-Date) - $latest.LastWriteTime).TotalHours, 1)
    }
}

$result.healthy = $result.minioReady -and ($result.backupAgeHours -ne $null) -and ($result.backupAgeHours -le $MaxBackupHours)
$result | ConvertTo-Json | Write-Output
if (-not $result.healthy) { exit 1 }
