<#
.SYNOPSIS
  LANLink 订单支付死信队列（q.order.paid.dlq）运维治理脚本 (Phase 2 / G11 运维闭环)
.DESCRIPTION
  提供对 RabbitMQ 共享死信队列的查询积压、批量重放与清空丢弃功能。
.EXAMPLE
  .\replay-dlq.ps1 -Action inspect
  .\replay-dlq.ps1 -Action replay -Count 50
  .\replay-dlq.ps1 -Action purge
#>
[CmdletBinding()]
param(
    [ValidateSet("inspect", "replay", "purge")]
    [string]$Action = "inspect",

    [int]$Count = 10,

    [string]$BackendUrl = "http://localhost:8080/api",

    [string]$Token = "",

    [string]$RabbitMqHttpUrl = "http://localhost:15672/api",

    [string]$RabbitMqUser = "guest",

    [string]$RabbitMqPass = "guest"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "LANLink RabbitMQ 死信队列运维工具 (q.order.paid.dlq)" -ForegroundColor Cyan
Write-Host "操作类型: $Action | 批次数量: $Count" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

function Invoke-BackendApi {
    param([string]$Path, [string]$Method = "GET", [hashtable]$Body = $null)
    $headers = @{}
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }
    $url = "$BackendUrl$Path"
    try {
        if ($Method -eq "GET") {
            return Invoke-RestMethod -Uri $url -Method GET -Headers $headers -TimeoutSec 10
        } else {
            return Invoke-RestMethod -Uri $url -Method POST -Headers $headers -TimeoutSec 10
        }
    } catch {
        Write-Warning "后端 API 请求失败 ($url): $_"
        return $null
    }
}

switch ($Action) {
    "inspect" {
        Write-Host "[1/2] 正在向后端管理端点查询死信积压..." -ForegroundColor Yellow
        $resp = Invoke-BackendApi -Path "/admin/mq/dlq/depth"
        if ($resp -and $resp.code -eq 200) {
            $depth = $resp.data.depth
            $queue = $resp.data.queue
            Write-Host "=> 队列名称: $queue" -ForegroundColor Green
            Write-Host "=> 当前死信积压数: $depth 条" -ForegroundColor $(if ($depth -gt 0) { "Red" } else { "Green" })
        } else {
            Write-Host "[2/2] 回退直接查询 RabbitMQ Management API (:15672)..." -ForegroundColor Yellow
            $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitMqUser}:${RabbitMqPass}"))
            $mqHeaders = @{ Authorization = "Basic $auth" }
            try {
                $queueInfo = Invoke-RestMethod -Uri "$RabbitMqHttpUrl/queues/%2F/q.order.paid.dlq" -Headers $mqHeaders -TimeoutSec 5
                Write-Host "=> RabbitMQ 队列状态: $($queueInfo.state)" -ForegroundColor Green
                Write-Host "=> 消息总积压数: $($queueInfo.messages) 条" -ForegroundColor $(if ($queueInfo.messages -gt 0) { "Red" } else { "Green" })
            } catch {
                Write-Error "无法连接 RabbitMQ 管理端点: $_"
            }
        }
    }

    "replay" {
        Write-Host "正在准备将死信重放至 lanlink.order 交换机 (最大 $Count 条)..." -ForegroundColor Yellow
        $resp = Invoke-BackendApi -Path "/admin/mq/dlq/replay?max=$Count" -Method "POST"
        if ($resp -and $resp.code -eq 200) {
            $replayed = $resp.data.replayed
            Write-Host "=> 成功重放死信消息: $replayed 条" -ForegroundColor Green
            Write-Host "=> 目标交换机: $($resp.data.targetExchange) (RoutingKey: $($resp.data.targetRoutingKey))" -ForegroundColor Gray
        } else {
            Write-Error "重放失败，请确认后端服务已启动且具备管理员权限"
        }
    }

    "purge" {
        Write-Host "警告: 正在执行清空（丢弃）死信队列操作！" -ForegroundColor Red
        $confirm = Read-Host "确认要清空 q.order.paid.dlq 吗？请输入 'YES' 确认"
        if ($confirm -ne "YES") {
            Write-Host "操作已取消。" -ForegroundColor Yellow
            return
        }
        $resp = Invoke-BackendApi -Path "/admin/mq/dlq/purge" -Method "POST"
        if ($resp -and $resp.code -eq 200) {
            Write-Host "=> 死信队列已清空，丢弃条数: $($resp.data.purged) 条" -ForegroundColor Green
        } else {
            Write-Error "清空失败，请检查服务连接"
        }
    }
}
