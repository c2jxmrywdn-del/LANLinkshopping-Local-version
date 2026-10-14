# ============================================================
# 微服务一键构建 + 启动（Windows PowerShell）
#   用法：cd cloud; .\start-dev.ps1
#   前置：Nacos 已启动（docker compose -f ..\docker-compose.cloud.yml up -d）
#   启动顺序：build → gateway → 6 个业务服务
# ============================================================
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host '== 1/3 构建（含依赖模块安装）==' -ForegroundColor Cyan
mvn -q -DskipTests install
if ($LASTEXITCODE -ne 0) { Write-Error '构建失败'; exit 1 }

$services = @(
    @{ name = 'lanlink-gateway';        jar = 'lanlink-gateway\target\lanlink-gateway-1.0.0.jar' },
    @{ name = 'lanlink-auth-service';   jar = 'lanlink-auth-service\target\lanlink-auth-service-1.0.0.jar' },
    @{ name = 'lanlink-merchant-service'; jar = 'lanlink-merchant-service\target\lanlink-merchant-service-1.0.0.jar' },
    @{ name = 'lanlink-product-service'; jar = 'lanlink-product-service\target\lanlink-product-service-1.0.0.jar' },
    @{ name = 'lanlink-cart-service';   jar = 'lanlink-cart-service\target\lanlink-cart-service-1.0.0.jar' },
    @{ name = 'lanlink-order-service';  jar = 'lanlink-order-service\target\lanlink-order-service-1.0.0.jar' },
    @{ name = 'lanlink-payment-service'; jar = 'lanlink-payment-service\target\lanlink-payment-service-1.0.0.jar' }
)

Write-Host '== 2/3 启动服务 ==' -ForegroundColor Cyan
foreach ($s in $services) {
    $jarPath = Join-Path $root $s.jar
    if (-not (Test-Path $jarPath)) { Write-Error "缺少 jar: $jarPath"; exit 1 }
    Start-Process -FilePath 'java' -ArgumentList @('-jar', $jarPath) `
        -WorkingDirectory (Split-Path $jarPath -Parent) `
        -RedirectStandardOutput "$root\logs\$($s.name).out.log" `
        -RedirectStandardError "$root\logs\$($s.name).err.log"
    Write-Host "  已启动 $($s.name)"
}

Write-Host '== 3/3 等待注册（约 40 秒）==' -ForegroundColor Cyan
Start-Sleep -Seconds 40
Write-Host '服务注册检查：' -ForegroundColor Cyan
try {
    $list = Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20'
    $list.doms | ForEach-Object { Write-Host "  [注册] $_" }
} catch { Write-Host '  Nacos 查询失败，请确认 Nacos 容器已启动' }

Write-Host ''
Write-Host '网关入口: http://localhost:8300/api' -ForegroundColor Green
Write-Host '契约链路验证: curl -X POST http://localhost:8300/api/orders -H "Content-Type: application/json" -d "{\"userId\":2,\"prodId\":106,\"quantity\":1}"' -ForegroundColor Green
Write-Host '停止服务: Get-Process java | Stop-Process（或逐个关闭）' -ForegroundColor Yellow
