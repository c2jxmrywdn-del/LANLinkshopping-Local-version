#requires -Version 5.1
<#
.SYNOPSIS
  P0-A 结算双付修复 · 真机验收脚本（通道 A：PAID 终态/回填；通道 B：pay 与提现并发互斥）。

.DESCRIPTION
  默认 -Mode DryRun：只做「只读」检查（GET 健康检查 + MySQL SELECT），不登录、不发起任何写请求。
  -Mode Execute：执行完整验收（登录、按需生成/审核批次、越额提现断言、并发 pay vs withdraw 竞态），
                 并按「只删除本次运行创建的数据」原则清理。

  安全约定：
    * 默认 DryRun，任何 POST 都不会发送；
    * Execute 模式的清理只针对本脚本记录的主键（settle_id / withdrawal id / earning_id / biz_no），不碰存量；
    * 使用 -NoCleanup 可保留本次创建的数据用于排障（会打印所有主键）。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1
  # 只读预检（当前库现状：通道 A 回填是否生效 / 可提余额口径 / 残留 SETTLED）

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1 -Mode Execute
  # 完整验收：需要后端已部署通道 A+B，且存在已 PAID 批次（场景 1）
  # 场景 2 需要 -AllowGenerate 且目标商户有 AVAILABLE 收益（脚本会自建批次并在结束时清理）

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify\p0a-double-pay.ps1 -Mode Execute -AllowGenerate -WithLockProbe
  # 通道 B 确定性锁观测：将 SQL 会话持有 t_merchant 行锁 4 秒，断言 pay 被阻塞（而不是立即返回）

.NOTES
  依赖真实端点/表（均来自当前代码，勿改）：
    健康:   GET  /api/actuator/health                     (AuthInterceptor 白名单)
    登录:   POST /api/auth/login                           body {phone,password}
    CSRF:   GET  /api/user/csrf-token                      -> data.token，写请求头 X-CSRF-TOKEN
    提现:   POST /api/merchant/finance/withdrawals         body {amount,bankAccountName,bankAccountNo,bankName,applyNote}
    余额:   GET  /api/merchant/finance/balance             -> data.available/settledTotal/paidTotal/frozenTotal
    对账:   POST /api/admin/settlement/sync                (仅 -AllowGenerate 场景可能需要)
    生成:   POST /api/admin/settlement/generate?merId=&periodStart=&periodEnd=
    审核:   POST /api/admin/settlement/{settleId}/approve
    打款:   POST /api/admin/settlement/{settleId}/pay      body {payVoucher}
    表:     t_merchant / t_user / t_merchant_earning / t_merchant_settlement
            / t_merchant_settlement_item / t_merchant_withdrawal / t_finance_ledger
  业务错误约定：BusinessException 由 GlobalExceptionHandler 返回 HTTP 200 + body.code=400
               （脚本断言 body.code，而不是 HTTP 状态码）。
#>
[CmdletBinding()]
param(
    [ValidateSet('DryRun', 'Execute')]
    [string]$Mode = 'DryRun',

    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$MySqlContainer = 'shopping-mysql-1',
    [string]$MySqlPassword = '',
    [string]$Database = 'lanlink_shopping',
    [string]$EnvFile = '.env',

    [string]$AdminPhone = '13800000000',
    [string]$AdminPassword = '123456',
    [string]$MerchantPhone = '13700000002',
    [string]$MerchantPassword = '123456',

    # 指定验收批次；0=自动挑选（场景1取最新 PAID；场景2取最新 APPROVED）
    [long]$SettleId = 0,
    # 场景2：允许脚本通过 admin API 自建批次（generate+approve），结束时按主键清理
    [switch]$AllowGenerate,
    # 通道B确定性锁观测：SQL 会话持锁，断言 pay 被阻塞
    [switch]$WithLockProbe,
    # 保留本次创建的数据（排障用）
    [switch]$NoCleanup,

    [int]$HttpTimeoutSec = 30,
    [string]$EvidenceDir = 'reports/impl/evidence'
)

Set-StrictMode -Version 1.0
$ErrorActionPreference = 'Stop'

# ============================== 基础设施 ==============================

$script:Results = New-Object System.Collections.ArrayList
$script:RunId = ('{0}-{1}' -f (Get-Date -Format 'yyyyMMddHHmmss'), [guid]::NewGuid().ToString('N').Substring(0, 6))
$script:Marker = "P0A-ACC-$($script:RunId)"
$script:CreatedSettleId = 0
$script:CreatedBatch = $false
$script:EvidenceLog = ''

function Write-Banner([string]$Text) {
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor DarkCyan
    Write-Host $Text -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor DarkCyan
}

function Add-Result {
    param(
        [string]$Id,
        [string]$Name,
        [string]$Expected,
        [string]$Actual,
        [ValidateSet('PASS', 'FAIL', 'SKIP', 'INFO', 'WARN')][string]$Result,
        [string]$Command = '',
        [string]$Note = ''
    )
    [void]$script:Results.Add([pscustomobject]@{
            Id = $Id; Name = $Name; Expected = $Expected; Actual = $Actual
            Result = $Result; Command = $Command; Note = $Note
        })
    $color = switch ($Result) {
        'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'Yellow' } default { 'Gray' }
    }
    Write-Host ''
    Write-Host "[$Result] $Id $Name" -ForegroundColor $color
    if ($Command) { Write-Host "  命令: $Command" -ForegroundColor DarkGray }
    Write-Host "  期望: $Expected" -ForegroundColor DarkGray
    Write-Host "  实际: $Actual" -ForegroundColor $color
    if ($Note) { Write-Host "  备注: $Note" -ForegroundColor DarkGray }
}

# ---------- .env / MySQL ----------

function Get-EnvValue([string]$Key) {
    if (-not (Test-Path -LiteralPath $EnvFile)) { $alt = Join-Path $PSScriptRoot '..\..\.env'; if (Test-Path -LiteralPath $alt) { $script:EnvFileResolved = $alt } else { return '' } } else { $script:EnvFileResolved = $EnvFile }
    $line = Get-Content -LiteralPath $script:EnvFileResolved -Encoding UTF8 |
            Where-Object { $_ -match ('^\s*' + [regex]::Escape($Key) + '\s*=') } |
            Select-Object -First 1
    if (-not $line) { return '' }
    return ($line -replace ('^\s*' + [regex]::Escape($Key) + '\s*=\s*'), '').Trim().Trim('"').Trim("'")
}

function Resolve-MySqlPassword {
    if ($MySqlPassword) { return $MySqlPassword }
    $envPwd = Get-EnvValue 'MYSQL_ROOT_PASSWORD'
    if ($envPwd) { return $envPwd }
    throw "无法获得 MySQL 密码：请用 -MySqlPassword 传入，或在 $EnvFile 中配置 MYSQL_ROOT_PASSWORD"
}

function Invoke-SqlRaw([string]$Sql) {
    $dockerArgs = @('exec', $MySqlContainer, 'mysql', '-uroot', "-p$script:MySqlPwd", "-D$Database",
        '-N', '-B', '--connect-timeout=5', '-e', $Sql)
    # PS 5.1：$ErrorActionPreference=Stop 时原生程序 stderr 会被当成 NativeCommandError 抛出，
    # 这里临时降级并重定向 stderr（mysql 的 "password on command line" 警告不影响结果）。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = & docker @dockerArgs 2>$null } finally { $ErrorActionPreference = $prevEap }
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL 查询失败（exit=$LASTEXITCODE）。请确认容器 $MySqlContainer 在跑、-MySqlPassword 正确。SQL: $Sql"
    }
    # 逗号包裹：单行结果时避免调用方把字符串当数组、$rows[0] 退化成首字符
    return ,@($out)
}

function Invoke-SqlScalar([string]$Sql) {
    $rows = Invoke-SqlRaw $Sql
    if ($rows.Count -eq 0) { return '' }
    return ([string]($rows[0])).Trim()
}

function Invoke-SqlRows([string]$Sql) {
    $rows = Invoke-SqlRaw $Sql
    $result = New-Object System.Collections.ArrayList
    foreach ($line in $rows) {
        if ($null -eq $line) { continue }
        $s = [string]$line
        if ($s.Trim() -eq '') { continue }
        [void]$result.Add(($s -split "`t"))
    }
    # 逗号包裹：阻止 PowerShell 把 ArrayList 拆成两层（否则 $rows[0] 会变成第一个字段而非第一行）
    return ,$result
}

# ---------- HTTP ----------

function Test-BackendHealth {
    $uri = "$BaseUrl/actuator/health"
    try {
        $resp = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $text = ''
        if ($null -ne $resp.RawContentStream) {
            $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        }
        else { $text = [string]$resp.Content }
        [pscustomobject]@{ Ok = ($resp.StatusCode -eq 200 -and $text -match '"status"\s*:\s*"UP"'); Status = $resp.StatusCode; Text = $text }
    }
    catch {
        [pscustomobject]@{ Ok = $false; Status = 0; Text = $_.Exception.Message }
    }
}

Add-Type -AssemblyName System.Net.Http

function New-ApiSession {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.CookieContainer = New-Object System.Net.CookieContainer
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds($HttpTimeoutSec)
    return (New-Object psobject -Property @{ Client = $client; Handler = $handler })
}

function New-JsonMessage {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [string]$CsrfToken,
        [string]$ContentType = 'application/json'
    )
    $msg = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, "$BaseUrl$Path")
    if ($CsrfToken) { [void]$msg.Headers.Add('X-CSRF-TOKEN', $CsrfToken) }
    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Compress -Depth 6
        $msg.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, $ContentType)
    }
    return $msg
}

function Convert-Response {
    param($Response)
    $text = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $obj = $null
    if ($text) {
        try { $obj = ConvertFrom-Json -InputObject $text } catch { $obj = $null }
    }
    return (New-Object psobject -Property @{
            Status = [int]$Response.StatusCode; Body = $obj; Text = $text
        })
}

function Send-ApiJson {
    param($Session, [string]$Method, [string]$Path, $Body, [string]$CsrfToken)
    $msg = New-JsonMessage -Method $Method -Path $Path -Body $Body -CsrfToken $CsrfToken
    try {
        $resp = $Session.Client.SendAsync($msg).GetAwaiter().GetResult()
        return (Convert-Response -Response $resp)
    }
    finally { $msg.Dispose() }
}

function Get-ApiCode($Response) {
    if ($null -eq $Response -or $null -eq $Response.Body) { return -1 }
    if ($null -eq $Response.Body.code) { return -1 }
    return [int]$Response.Body.code
}

function Get-ApiMessage($Response) {
    if ($null -eq $Response -or $null -eq $Response.Body -or $null -eq $Response.Body.message) { return '' }
    return [string]$Response.Body.message
}

function Connect-ApiSession {
    param([string]$Phone, [string]$Password, [string]$Label)
    $s = New-ApiSession
    $login = Send-ApiJson -Session $s -Method 'Post' -Path '/auth/login' -Body @{ phone = $Phone; password = $Password }
    $code = Get-ApiCode $login
    if ($code -ne 200) {
        throw "$Label 登录失败（body.code=$code, msg=$(Get-ApiMessage $login)）。请确认账号已存在/密码正确；演示数据见 README。"
    }
    if ($login.Body.data -and $login.Body.data.require2fa) {
        throw "$Label 账号启用了两步验证（require2fa），本脚本不支持 2FA；请使用未启用 TOTP 的验收账号。"
    }
    $csrf = Send-ApiJson -Session $s -Method 'Get' -Path '/user/csrf-token'
    if ((Get-ApiCode $csrf) -ne 200 -or -not $csrf.Body.data.token) {
        throw "$Label 获取 CSRF token 失败：$(Get-ApiMessage $csrf)"
    }
    return (New-Object psobject -Property @{
            Label = $Label; Phone = $Phone; Csrf = [string]$csrf.Body.data.token; Session = $s
        })
}

# ============================== 只读核对 SQL（要求 3） ==============================

# C-A1/C-A2：回填是否生效 / 有没有残留「批次已 PAID、收益仍 SETTLED/REVERSED」
function Get-PaidBatchResidual {
    $sql = @"
SELECT COUNT(*) FROM t_merchant_earning e
  JOIN t_merchant_settlement_item si ON si.earning_id = e.earning_id
  JOIN t_merchant_settlement s       ON s.settle_id  = si.settle_id
 WHERE s.status = 'PAID'
   AND e.status IN ('SETTLED','REVERSED');
"@
    return [long](Invoke-SqlScalar $sql)
}

function Get-PaidBatchNotPaid {
    $sql = @"
SELECT COUNT(*) FROM t_merchant_earning e
  JOIN t_merchant_settlement_item si ON si.earning_id = e.earning_id
  JOIN t_merchant_settlement s       ON s.settle_id  = si.settle_id
 WHERE s.status = 'PAID' AND e.status <> 'PAID';
"@
    return [long](Invoke-SqlScalar $sql)
}

# C-A3：可提余额口径（SQL 推导）——PAID 批次明细从 settled 中排除
function Get-ExpectedBalance([long]$MerId) {
    $settledSql = @"
SELECT COALESCE(SUM(CASE WHEN e.status IN ('SETTLED','REVERSED') THEN e.net_amount ELSE 0 END),0)
FROM t_merchant_earning e
WHERE e.mer_id = $MerId
  AND NOT EXISTS (
      SELECT 1 FROM t_merchant_settlement_item si
        JOIN t_merchant_settlement s ON s.settle_id = si.settle_id
       WHERE si.earning_id = e.earning_id AND s.status = 'PAID');
"@
    $paidSql = "SELECT COALESCE(SUM(amount),0) FROM t_merchant_withdrawal WHERE mer_id = $MerId AND status = 1;"
    $frozenSql = "SELECT COALESCE(SUM(amount),0) FROM t_merchant_withdrawal WHERE mer_id = $MerId AND status = 0;"
    $settled = [decimal](Invoke-SqlScalar $settledSql)
    $paid = [decimal](Invoke-SqlScalar $paidSql)
    $frozen = [decimal](Invoke-SqlScalar $frozenSql)
    $raw = $settled - $paid - $frozen
    $available = if ($raw -lt 0) { [decimal]0 } else { $raw }
    return (New-Object psobject -Property @{
            SettledExclPaid = $settled; Paid = $paid; Frozen = $frozen
            RawBalance = $raw; Available = $available
        })
}

# C-A4：item_count / total_net 与明细不一致（巡检）
function Get-ItemCountMismatch {
    $sql = @"
SELECT COUNT(*) FROM (
  SELECT s.settle_id
  FROM t_merchant_settlement s
  LEFT JOIN t_merchant_settlement_item si ON si.settle_id = s.settle_id
  GROUP BY s.settle_id, s.item_count, s.total_net, s.status
  HAVING COUNT(si.id) <> s.item_count
      OR (s.status <> 'DRAFT' AND COALESCE(SUM(si.net_amount),0) <> s.total_net)
      OR (s.status <> 'DRAFT' AND COUNT(si.id) = 0)
) t;
"@
    return [long](Invoke-SqlScalar $sql)
}

# C-A5：孤立 PAID 收益（不在任何 PAID 批次明细内）——提示回填/状态异常
function Get-OrphanPaidEarnings {
    $sql = @"
SELECT COUNT(*) FROM t_merchant_earning e
 WHERE e.status = 'PAID'
   AND NOT EXISTS (
       SELECT 1 FROM t_merchant_settlement_item si
         JOIN t_merchant_settlement s ON s.settle_id = si.settle_id
        WHERE si.earning_id = e.earning_id AND s.status = 'PAID');
"@
    return [long](Invoke-SqlScalar $sql)
}

# ============================== 场景 1 ==============================

function Select-PaidBatch {
    $where = "s.status = 'PAID'"
    if ($SettleId -gt 0) { $where = "s.settle_id = $SettleId" }
    $sql = @"
SELECT s.settle_id, s.mer_id, s.total_net, s.item_count
FROM t_merchant_settlement s
WHERE $where
ORDER BY s.settle_id DESC LIMIT 1;
"@
    $rows = Invoke-SqlRows $sql
    if ($rows.Count -eq 0) { return $null }
    $r = $rows[0]
    return (New-Object psobject -Property @{
            SettleId = [long]$r[0]; MerId = [long]$r[1]
            TotalNet = [decimal]$r[2]; ItemCount = [long]$r[3]
        })
}

function Get-MerchantPhone([long]$MerId) {
    $sql = @"
SELECT u.phone FROM t_merchant m JOIN t_user u ON u.user_id = m.user_id WHERE m.mer_id = $MerId LIMIT 1;
"@
    return (Invoke-SqlScalar $sql)
}

function Invoke-Scenario1 {
    Write-Banner "场景 1：已 PAID 批次 → 可提余额应排除其收益 → 超额提现必须 400（改前可复现）"

    $batch = Select-PaidBatch
    if ($null -eq $batch) {
        Add-Result -Id 'S1-PICK' -Name '挑选已 PAID 批次' -Expected '存在至少 1 个 status=PAID 的批次' `
            -Actual '未找到（需先完成通道 A 实施并执行一次 pay 登记）' -Result 'SKIP' `
            -Command 'SELECT ... FROM t_merchant_settlement WHERE status=''PAID'''
        return
    }
    Add-Result -Id 'S1-PICK' -Name '挑选已 PAID 批次' -Expected '存在 status=PAID 批次' `
        -Actual ("settle_id={0}, mer_id={1}, total_net={2}, item_count={3}" -f $batch.SettleId, $batch.MerId, $batch.TotalNet, $batch.ItemCount) `
        -Result 'PASS' -Command 'SELECT settle_id,mer_id,total_net,item_count FROM t_merchant_settlement WHERE status=''PAID'''

    $expected = Get-ExpectedBalance -MerId $batch.MerId
    Add-Result -Id 'S1-SQL' -Name 'SQL 推导可提余额（排除 PAID 批次明细）' `
        -Expected 'settled(排除PAID批次) − paid − frozen，负值钳 0' `
        -Actual ("settled_excl_paid={0}, paid={1}, frozen={2}, raw={3}, available={4}" -f `
            $expected.SettledExclPaid, $expected.Paid, $expected.Frozen, $expected.RawBalance, $expected.Available) `
        -Result 'PASS' -Command 'SELECT SUM(net_amount) ... NOT EXISTS(PAID batch item); + withdrawal 聚合'

    if ($Mode -ne 'Execute') {
        Add-Result -Id 'S1-API' -Name 'API 可提余额与 SQL 口径一致性' -Expected 'GET /merchant/finance/balance 的 data.available 与 S1-SQL 一致（±0.005）' `
            -Actual 'DryRun 未登录、未调用 API（避免写登录日志）' -Result 'SKIP' `
            -Command "GET $BaseUrl/merchant/finance/balance"
        Add-Result -Id 'S1-400' -Name '超额提现应 400' -Expected '提现 (available+1.00) 返回 body.code=400「可提余额不足」' `
            -Actual 'DryRun 未发送 POST（-Mode Execute 时执行）' -Result 'SKIP' `
            -Command "POST $BaseUrl/merchant/finance/withdrawals body {amount: available+1.00, ...}"
        return
    }

    $phone = $MerchantPhone
    $dbPhone = Get-MerchantPhone -MerId $batch.MerId
    if ($dbPhone) { $phone = $dbPhone }
    $merchant = Connect-ApiSession -Phone $phone -Password $MerchantPassword -Label '商户'
    $balance = Send-ApiJson -Session $merchant.Session -Method 'Get' -Path '/merchant/finance/balance'
    if ((Get-ApiCode $balance) -ne 200) {
        Add-Result -Id 'S1-API' -Name '读取可提余额' -Expected 'body.code=200' -Actual ("code={0}, msg={1}" -f (Get-ApiCode $balance), (Get-ApiMessage $balance)) -Result 'FAIL' -Command 'GET /merchant/finance/balance'
        return
    }
    $apiAvailable = [decimal]$balance.Body.data.available
    $apiSettled = [decimal]$balance.Body.data.settledTotal
    $diff = [Math]::Abs($apiAvailable - $expected.Available)
    if ($diff -le 0.005) {
        Add-Result -Id 'S1-API' -Name 'API 可提余额与 SQL 口径一致性' -Expected ("available≈{0}" -f $expected.Available) `
            -Actual ("api.available={0}, api.settledTotal={1}, diff={2}" -f $apiAvailable, $apiSettled, $diff) -Result 'PASS' `
            -Command 'GET /merchant/finance/balance'
    }
    else {
        Add-Result -Id 'S1-API' -Name 'API 可提余额与 SQL 口径一致性' -Expected ("available≈{0}（±0.005）" -f $expected.Available) `
            -Actual ("api.available={0}, api.settledTotal={1}, diff={2} → 可提口径仍包含已 PAID 批次/或历史数据未回填" -f $apiAvailable, $apiSettled, $diff) -Result 'FAIL' `
            -Command 'GET /merchant/finance/balance'
    }

    $overAmount = [decimal][Math]::Round([double]($expected.Available + 1.00), 2)
    if ($overAmount -lt 1.00) { $overAmount = [decimal]1.00 }
    $body = @{
        amount = [double]$overAmount
        bankAccountName = 'P0A验收专用公司'
        bankAccountNo = '6222000011112222'
        bankName = '工商银行深圳分行'
        applyNote = $script:Marker
    }
    $resp = Send-ApiJson -Session $merchant.Session -Method 'Post' -Path '/merchant/finance/withdrawals' -Body $body -CsrfToken $merchant.Csrf
    $code = Get-ApiCode $resp
    $msg = Get-ApiMessage $resp
    $expectedTxt = "body.code=400 且 message 含「可提余额不足」"
    $actualTxt = "body.code=$code, message=$msg, http=$($resp.Status)"
    $cmdTxt = "POST $BaseUrl/merchant/finance/withdrawals {amount=$overAmount, applyNote=$($script:Marker)}"
    if ($code -eq 400 -and $msg -match '可提余额不足') {
        Add-Result -Id 'S1-400' -Name '超额提现应 400' -Expected $expectedTxt -Actual $actualTxt -Result 'PASS' -Command $cmdTxt
    }
    elseif ($code -eq 200) {
        # 改前缺陷复现：提现成功（产生数据），记录后由清理段按 marker 精确删除
        Add-Result -Id 'S1-400' -Name '超额提现应 400' -Expected $expectedTxt `
            -Actual "$actualTxt → 提现成功，说明可提余额未排除已 PAID 批次收益（通道 A 失效）" -Result 'FAIL' `
            -Command $cmdTxt -Note ('本次运行的提现单将按 applyNote={0} 精确清理' -f $script:Marker)
    }
    else {
        Add-Result -Id 'S1-400' -Name '超额提现应 400' -Expected $expectedTxt -Actual $actualTxt -Result 'FAIL' -Command $cmdTxt
    }
}

# ============================== 场景 2（并发 / 锁观测） ==============================

function Select-ApprovedBatch([long]$MerId) {
    $cond = "s.status = 'APPROVED'"
    if ($SettleId -gt 0) { $cond = "s.settle_id = $SettleId" }
    if ($MerId -gt 0) { $cond += " AND s.mer_id = $MerId" }
    $sql = @"
SELECT s.settle_id, s.mer_id, s.total_net, s.item_count
FROM t_merchant_settlement s WHERE $cond ORDER BY s.settle_id DESC LIMIT 1;
"@
    $rows = Invoke-SqlRows $sql
    if ($rows.Count -eq 0) { return $null }
    $r = $rows[0]
    return (New-Object psobject -Property @{
            SettleId = [long]$r[0]; MerId = [long]$r[1]; TotalNet = [decimal]$r[2]; ItemCount = [long]$r[3]
        })
}

function Get-BatchItemEarningIds([long]$SettleId) {
    $sql = "SELECT earning_id FROM t_merchant_settlement_item WHERE settle_id = $SettleId;"
    $ids = New-Object System.Collections.ArrayList
    foreach ($row in (Invoke-SqlRows $sql)) { [void]$ids.Add([long]$row[0]) }
    return $ids
}

function Invoke-Cleanup {
    Write-Banner '清理（仅删除本次运行创建的数据，按主键精确删除）'
    if ($NoCleanup) {
        Write-Host '  -NoCleanup 已指定：保留本次数据供排障。' -ForegroundColor Yellow
        if ($script:CreatedSettleId -gt 0) { Write-Host "  保留 settle_id=$($script:CreatedSettleId)" }
        Write-Host "  清理线索：t_merchant_withdrawal.apply_note='$($script:Marker)'，t_merchant_settlement.settle_id=$($script:CreatedSettleId)"
        return
    }
    # 1) 本次创建的提现单 + 对应台账
    $wdRows = Invoke-SqlRows "SELECT id, withdraw_no FROM t_merchant_withdrawal WHERE apply_note = '$($script:Marker)';"
    if ($wdRows.Count -gt 0) {
        $ids = New-Object System.Collections.ArrayList
        $nos = New-Object System.Collections.ArrayList
        foreach ($r in $wdRows) { [void]$ids.Add([long]$r[0]); [void]$nos.Add([string]$r[1]) }
        $noList = ($nos | ForEach-Object { "'" + ($_ -replace "'", "''") + "'" }) -join ','
        $idList = ($ids | ForEach-Object { [string]$_ }) -join ','
        Invoke-SqlRaw "DELETE FROM t_finance_ledger WHERE biz_no IN ($noList);" | Out-Null
        Invoke-SqlRaw "DELETE FROM t_merchant_withdrawal WHERE id IN ($idList);" | Out-Null
        Write-Host "  已删除提现单 $(($ids).Count) 条 / 台账同步" -ForegroundColor Green
    }
    else {
        Write-Host '  无本次创建的提现单' -ForegroundColor DarkGray
    }
    # 2) 本次创建的批次（含收益状态还原）
    if ($script:CreatedBatch -and $script:CreatedSettleId -gt 0) {
        $earningIds = Get-BatchItemEarningIds -SettleId $script:CreatedSettleId
        if ($earningIds.Count -gt 0) {
            $idList = ($earningIds | ForEach-Object { [string]$_ }) -join ','
            Invoke-SqlRaw "UPDATE t_merchant_earning SET status = CASE type WHEN 'REVERSE' THEN 'REVERSED' ELSE 'AVAILABLE' END WHERE earning_id IN ($idList) AND status IN ('SETTLED','PAID');" | Out-Null
        }
        Invoke-SqlRaw "DELETE FROM t_merchant_settlement_item WHERE settle_id = $($script:CreatedSettleId);" | Out-Null
        Invoke-SqlRaw "DELETE FROM t_merchant_settlement WHERE settle_id = $($script:CreatedSettleId);" | Out-Null
        Write-Host "  已删除本次创建批次 settle_id=$($script:CreatedSettleId) 并还原其收益行状态" -ForegroundColor Green
    }
    else {
        Write-Host '  无本次创建的批次' -ForegroundColor DarkGray
    }
}

function Invoke-LockProbe {
    Write-Banner '通道 B-1：确定性锁观测（SQL 会话持商户行锁 4s，断言 pay 被阻塞）'
    if ($Mode -ne 'Execute') {
        Add-Result -Id 'B1-LOCK' -Name 'pay 是否参与 t_merchant 行锁' -Expected '持锁期间 pay 阻塞 ≥2s' `
            -Actual 'DryRun 不发送写请求；Execute + -WithLockProbe 时执行' -Result 'SKIP' `
            -Command "SQL: START TRANSACTION; SELECT mer_id FROM t_merchant WHERE mer_id=? FOR UPDATE; SELECT SLEEP(4); COMMIT;"
        return
    }
    $batch = Select-ApprovedBatch -MerId 0
    if ($null -eq $batch) {
        Add-Result -Id 'B1-LOCK' -Name 'pay 是否参与 t_merchant 行锁' -Expected '存在 APPROVED 批次' `
            -Actual '无 APPROVED 批次；请先准备数据或用 -AllowGenerate（需 AVAILABLE 收益）' -Result 'SKIP'
        return
    }
    $admin = Connect-ApiSession -Phone $AdminPhone -Password $AdminPassword -Label '管理端'
    $lockJob = Start-Job -ScriptBlock {
        param($container, $pwd, $db, $merId)
        $sql = "START TRANSACTION; SELECT mer_id FROM t_merchant WHERE mer_id = $merId FOR UPDATE; SELECT SLEEP(4); COMMIT;"
        & docker exec $container mysql -uroot "-p$pwd" "-D$db" -N -B -e $sql 2>$null
    } -ArgumentList $MySqlContainer, $script:MySqlPwd, $Database, $batch.MerId
    Start-Sleep -Milliseconds 800
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Send-ApiJson -Session $admin.Session -Method 'Post' -Path ("/admin/settlement/{0}/pay" -f $batch.SettleId) -Body @{ payVoucher = $script:Marker } -CsrfToken $admin.Csrf
    $sw.Stop()
    $elapsed = [Math]::Round($sw.Elapsed.TotalSeconds, 2)
    Wait-Job $lockJob -Timeout 20 | Out-Null
    Remove-Job $lockJob -Force -ErrorAction SilentlyContinue
    $code = Get-ApiCode $resp
    $expectedTxt = 'elapsed ≥ 2s（说明 pay 在同商户商户行锁上排队）'
    $actualTxt = "elapsed=${elapsed}s, body.code=$code, msg=$(Get-ApiMessage $resp)"
    if ($elapsed -ge 2.0) {
        Add-Result -Id 'B1-LOCK' -Name 'pay 是否参与 t_merchant 行锁' -Expected $expectedTxt -Actual $actualTxt -Result 'PASS' `
            -Command "会话A: SELECT mer_id FROM t_merchant WHERE mer_id=$($batch.MerId) FOR UPDATE; 会话B: POST /admin/settlement/$($batch.SettleId)/pay"
    }
    else {
        Add-Result -Id 'B1-LOCK' -Name 'pay 是否参与 t_merchant 行锁' -Expected $expectedTxt `
            -Actual "$actualTxt → pay 未被阻塞，通道 B 锁未生效（或锁观测时序失败）" -Result 'FAIL' `
            -Command "会话A: SELECT ... FOR UPDATE; 会话B: POST /admin/settlement/$($batch.SettleId)/pay"
    }
}

function Invoke-Scenario2 {
    Write-Banner '场景 2：同商户 pay() 与 withdrawApply() 并发 → 恰一成一败（不双付）'
    if ($Mode -ne 'Execute') {
        Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '两请求并发后恰好一个 body.code=200，另一个 400；无 500/双成功' `
            -Actual 'DryRun 未发送写请求（Execute + -AllowGenerate 时执行；需目标商户有 AVAILABLE 收益）' -Result 'SKIP' `
            -Command "并发: POST /admin/settlement/{id}/pay  ||  POST /merchant/finance/withdrawals"
        return
    }

    $batch = Select-ApprovedBatch -MerId 0
    if ($null -eq $batch) {
        if (-not $AllowGenerate) {
            Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '存在 APPROVED 批次，或用 -AllowGenerate 自建' `
                -Actual '无 APPROVED 批次且未指定 -AllowGenerate' -Result 'SKIP'
            return
        }
        # 找一个有 AVAILABLE 收益的商户（优先 -MerchantPhone 对应商户）
        $merId = 0
        $phone = $MerchantPhone
        $rows = Invoke-SqlRows "SELECT m.mer_id, u.phone FROM t_merchant m JOIN t_user u ON u.user_id = m.user_id WHERE EXISTS (SELECT 1 FROM t_merchant_earning e WHERE e.mer_id = m.mer_id AND e.type='EARN' AND e.status='AVAILABLE') ORDER BY m.mer_id LIMIT 5;"
        if ($rows.Count -eq 0) {
            Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '目标商户存在 AVAILABLE 收益以生成批次' `
                -Actual '当前库无任何 AVAILABLE EARN，无法通过 generate 自建批次' -Result 'SKIP' `
                -Note '可先走真实订单完成+持有期满（或 POST /admin/settlement/sync）准备数据；本次验收环境未准备'
            return
        }
        foreach ($r in $rows) { if ($r[1] -eq $MerchantPhone) { $merId = [long]$r[0]; $phone = [string]$r[1]; break } }
        if ($merId -le 0) { $merId = [long]$rows[0][0]; $phone = [string]$rows[0][1] }
        $admin = Connect-ApiSession -Phone $AdminPhone -Password $AdminPassword -Label '管理端'
        $gen = Send-ApiJson -Session $admin.Session -Method 'Post' -Path ("/admin/settlement/generate?merId={0}" -f $merId) -CsrfToken $admin.Csrf
        if ((Get-ApiCode $gen) -ne 200) {
            Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected 'generate 成功' -Actual ("code={0}, msg={1}" -f (Get-ApiCode $gen), (Get-ApiMessage $gen)) -Result 'SKIP'
            return
        }
        $script:CreatedSettleId = [long]$gen.Body.data.settleId
        $script:CreatedBatch = $true
        $approve = Send-ApiJson -Session $admin.Session -Method 'Post' -Path ("/admin/settlement/{0}/approve" -f $script:CreatedSettleId) -CsrfToken $admin.Csrf
        if ((Get-ApiCode $approve) -ne 200) {
            Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected 'approve 成功' -Actual ("code={0}, msg={1}" -f (Get-ApiCode $approve), (Get-ApiMessage $approve)) -Result 'FAIL'
            return
        }
        $batch = Select-ApprovedBatch -MerId $merId
        if ($null -eq $batch) {
            Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '生成并审核后存在 APPROVED 批次' -Actual '未查到' -Result 'FAIL'
            return
        }
        Write-Host "  已自建批次 settle_id=$($script:CreatedSettleId)（mer_id=$merId, total_net=$($batch.TotalNet)）" -ForegroundColor Yellow
    }
    else {
        Add-Result -Id 'S2-DATA' -Name '复用现有 APPROVED 批次' -Expected '存在 APPROVED 批次' `
            -Actual ("settle_id={0}, mer_id={1}, total_net={2}" -f $batch.SettleId, $batch.MerId, $batch.TotalNet) -Result 'PASS'
        Write-Host '  警告：复用现有批次会在结束时尝试还原其状态（pay 会改变批次/收益状态）。建议 -AllowGenerate 使用自建数据。' -ForegroundColor Yellow
        $script:CreatedSettleId = $batch.SettleId
        $script:CreatedBatch = $false
    }

    $merPhone = if ($batch.MerId -eq 0) { $MerchantPhone } else { (Get-MerchantPhone -MerId $batch.MerId) }
    if (-not $merPhone) { $merPhone = $MerchantPhone }
    $merchant = Connect-ApiSession -Phone $merPhone -Password $MerchantPassword -Label '商户'
    $admin = Connect-ApiSession -Phone $AdminPhone -Password $AdminPassword -Label '管理端'

    $bal = Send-ApiJson -Session $merchant.Session -Method 'Get' -Path '/merchant/finance/balance'
    if ((Get-ApiCode $bal) -ne 200) {
        Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '读取余额成功' -Actual (Get-ApiMessage $bal) -Result 'FAIL'
        return
    }
    $available = [decimal]$bal.Body.data.available
    $totalNet = [decimal]$batch.TotalNet
    $wf = [double]$available; $tf = [double]$totalNet; $withdrawAmount = [decimal][Math]::Round([Math]::Min($wf, [Math]::Max(1.0, $wf - $tf + 1.0)), 2)
    if ($available -lt 1.0 -or $withdrawAmount -lt 1.0) {
        Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '可提余额 ≥ 1.00 以便构造竞态金额' `
            -Actual ("available={0}, batch.total_net={1} → 无法构造「提现+打款 > 余额」的竞态" -f $available, $totalNet) -Result 'SKIP'
        return
    }
    Add-Result -Id 'S2-PLAN' -Name '竞态参数' -Expected 'W + T > 可提余额（两通道互斥时恰一成一败）' `
        -Actual ("available={0}, batch.total_net={1}, withdraw_amount={2}" -f $available, $totalNet, $withdrawAmount) -Result 'INFO'

    $payMsg = New-JsonMessage -Method 'Post' -Path ("/admin/settlement/{0}/pay" -f $batch.SettleId) -Body @{ payVoucher = $script:Marker } -CsrfToken $admin.Csrf
    $wdMsg = New-JsonMessage -Method 'Post' -Path '/merchant/finance/withdrawals' -CsrfToken $merchant.Csrf -Body @{
        amount = [double]$withdrawAmount
        bankAccountName = 'P0A验收专用公司'
        bankAccountNo = '6222000011112222'
        bankName = '工商银行深圳分行'
        applyNote = $script:Marker
    }
    $payTask = $admin.Session.Client.SendAsync($payMsg)
    $wdTask = $merchant.Session.Client.SendAsync($wdMsg)
    try {
        [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($payTask, $wdTask))
        $payResp = Convert-Response -Response $payTask.GetAwaiter().GetResult()
        $wdResp = Convert-Response -Response $wdTask.GetAwaiter().GetResult()
    }
    finally { $payMsg.Dispose(); $wdMsg.Dispose() }

    $payCode = Get-ApiCode $payResp
    $wdCode = Get-ApiCode $wdResp
    $okCount = 0
    if ($payCode -eq 200) { $okCount++ }
    if ($wdCode -eq 200) { $okCount++ }
    $actualTxt = "pay(code=$payCode, msg=$(Get-ApiMessage $payResp)) | withdraw(code=$wdCode, msg=$(Get-ApiMessage $wdResp))"
    $cmdTxt = "并发: POST /admin/settlement/$($batch.SettleId)/pay  ||  POST /merchant/finance/withdrawals {amount=$withdrawAmount}"
    if ($payCode -eq 500 -or $wdCode -eq 500) {
        Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '无 500/死锁' -Actual $actualTxt -Result 'FAIL' -Command $cmdTxt
    }
    elseif ($okCount -eq 1) {
        $note = '符合预期：一个通道成功、另一个 400（被余额/状态守卫拦截）'
        if ($payCode -eq 200) { $note += '；pay 胜出后商户可提余额应已扣除本批 total_net' }
        Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '恰好 1 个 body.code=200，另一个 400' -Actual $actualTxt -Result 'PASS' -Command $cmdTxt -Note $note
    }
    else {
        Add-Result -Id 'S2-RACE' -Name '并发 pay vs 提现' -Expected '恰好 1 个 body.code=200，另一个 400' `
            -Actual "$actualTxt → 成功数=$okCount（2=双付，0=异常）" -Result 'FAIL' -Command $cmdTxt
    }
}

# ============================== 主流程 ==============================

try {
    if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
    $script:EvidenceLog = Join-Path $EvidenceDir ('p0a-acceptance-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
    try { Start-Transcript -Path $script:EvidenceLog -Force | Out-Null } catch { Write-Host "（无法开启 transcript：$($_.Exception.Message)）" }

    Write-Banner "P0-A 双付修复验收 | Mode=$Mode | RunId=$($script:RunId)"
    Write-Host "BaseUrl = $BaseUrl"
    Write-Host "DB      = $Database @ container $MySqlContainer"
    Write-Host "Marker  = $($script:Marker)"
    Write-Host "证据日志 = $script:EvidenceLog"

    # ---- 前置检查 ----
    Write-Banner '前置检查'
    $health = Test-BackendHealth
    if ($health.Ok) {
        Add-Result -Id 'PRE-1' -Name '后端健康检查' -Expected 'GET /actuator/health → 200 且 status=UP' -Actual "HTTP $($health.Status): $($health.Text)" -Result 'PASS' -Command "GET $BaseUrl/actuator/health"
    }
    else {
        Add-Result -Id 'PRE-1' -Name '后端健康检查' -Expected 'GET /actuator/health → 200 且 status=UP' -Actual "HTTP $($health.Status): $($health.Text)" -Result 'FAIL' -Command "GET $BaseUrl/actuator/health"
        throw '后端不健康，后续验收无法进行。请先确认 shopping-backend-1 已启动且端口 8080 映射到 127.0.0.1。'
    }

    $script:MySqlPwd = Resolve-MySqlPassword
    $mysqlOk = Invoke-SqlScalar 'SELECT 1;'
    if ($mysqlOk -eq '1') {
        Add-Result -Id 'PRE-2' -Name 'MySQL 可连' -Expected 'docker exec mysql -N -B -e "SELECT 1" → 1' -Actual '1' -Result 'PASS' `
            -Command "docker exec $MySqlContainer mysql -uroot -p*** -N -B -e 'SELECT 1'"
    }
    else {
        Add-Result -Id 'PRE-2' -Name 'MySQL 可连' -Expected 'SELECT 1 → 1' -Actual $mysqlOk -Result 'FAIL'
        throw 'MySQL 不可用。'
    }

    $tables = @('t_merchant', 't_user', 't_merchant_earning', 't_merchant_settlement', 't_merchant_settlement_item', 't_merchant_withdrawal', 't_finance_ledger')
    $tableList = ($tables | ForEach-Object { "'" + $_ + "'" }) -join ','
    $existing = [long](Invoke-SqlScalar "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME IN ($tableList);")
    if ($existing -ge $tables.Count) {
        Add-Result -Id 'PRE-3' -Name '验收所需表存在' -Expected ("$($tables.Count) 张表齐全") -Actual ("存在 $existing 张: " + ($tables -join ', ')) -Result 'PASS' `
            -Command "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME IN (...)"
    }
    else {
        Add-Result -Id 'PRE-3' -Name '验收所需表存在' -Expected ("$($tables.Count) 张表齐全") -Actual "仅存在 $existing 张" -Result 'FAIL'
        throw '缺少验收所需表，请检查数据库/初始化器。'
    }

    # ---- 只读核对（通道 A 回填/口径/巡检） ----
    Write-Banner '只读核对 SQL（要求 3：回填是否生效 / 可提口径 / 残留 SETTLED 但批次已 PAID）'
    $residual = Get-PaidBatchResidual
    if ($residual -eq 0) {
        Add-Result -Id 'A1' -Name '回填是否生效（PAID 批次明细仍为 SETTLED/REVERSED 的行数）' -Expected '0' -Actual "$residual" -Result 'PASS' `
            -Command 'SELECT COUNT(*) FROM t_merchant_earning e JOIN t_merchant_settlement_item si ... JOIN t_merchant_settlement s WHERE s.status=''PAID'' AND e.status IN (''SETTLED'',''REVERSED'')'
    }
    else {
        Add-Result -Id 'A1' -Name '回填是否生效（PAID 批次明细仍为 SETTLED/REVERSED 的行数）' -Expected '0' `
            -Actual "$residual → 回填未生效：需部署含回填 initializer 的后端并重启，或手工执行 p0a 回填 SQL" -Result 'FAIL' `
            -Command 'SELECT COUNT(*) FROM ... WHERE s.status=''PAID'' AND e.status IN (''SETTLED'',''REVERSED'')'
    }
    $notPaid = Get-PaidBatchNotPaid
    if ($notPaid -eq 0) {
        Add-Result -Id 'A2' -Name 'PAID 批次明细必须全部 PAID' -Expected '0' -Actual "$notPaid" -Result 'PASS'
    }
    else {
        Add-Result -Id 'A2' -Name 'PAID 批次明细必须全部 PAID' -Expected '0' -Actual "$notPaid 行非 PAID（通道 A 不变量未成立）" -Result 'FAIL'
    }
    $mismatch = Get-ItemCountMismatch
    if ($mismatch -eq 0) {
        Add-Result -Id 'A5' -Name 'item_count/total_net 与明细一致性巡检' -Expected '0 个不一致批次' -Actual "$mismatch" -Result 'PASS' `
            -Command 'GROUP BY settle_id HAVING COUNT(items)<>item_count OR SUM(net)<>total_net'
    }
    else {
        Add-Result -Id 'A5' -Name 'item_count/total_net 与明细一致性巡检' -Expected '0 个不一致批次' -Actual "$mismatch 个不一致批次（需人工核对，见 README 巡检 SQL）" -Result 'WARN' -Note '巡检项，不一定是双付缺陷'
    }
    $orphan = Get-OrphanPaidEarnings
    if ($orphan -eq 0) {
        Add-Result -Id 'A6' -Name '孤立 PAID 收益（不在任何 PAID 批次明细内）' -Expected '0' -Actual "$orphan" -Result 'PASS'
    }
    else {
        Add-Result -Id 'A6' -Name '孤立 PAID 收益（不在任何 PAID 批次明细内）' -Expected '0' -Actual "$orphan 行（回填/状态异常线索）" -Result 'WARN'
    }

    # ---- 场景 1 ----
    Invoke-Scenario1

    # ---- 通道 B ----
    if ($WithLockProbe) { Invoke-LockProbe }
    else {
        Add-Result -Id 'B1-LOCK' -Name 'pay 是否参与 t_merchant 行锁' -Expected '持锁期间 pay 阻塞 ≥2s' `
            -Actual '未指定 -WithLockProbe（默认跳过，避免额外写操作）' -Result 'SKIP'
    }
    Invoke-Scenario2

    # ---- 清理 ----
    Invoke-Cleanup

    # ---- 汇总 ----
    Write-Banner '验收结果汇总'
    $script:Results | Format-Table -AutoSize Id, Name, Result, Actual -Wrap | Out-String -Width 200 | Write-Host
    $pass = @($script:Results | Where-Object { $_.Result -eq 'PASS' }).Count
    $fail = @($script:Results | Where-Object { $_.Result -eq 'FAIL' }).Count
    $skip = @($script:Results | Where-Object { $_.Result -eq 'SKIP' }).Count
    Write-Host ("PASS={0}  FAIL={1}  SKIP={2}  （证据日志：{3}）" -f $pass, $fail, $skip, $script:EvidenceLog) -ForegroundColor Cyan
    if ($fail -gt 0) { exit 1 }
    if ($skip -gt 0 -and $pass -eq 0) { exit 3 }
    exit 0
}
catch {
    Write-Host ''
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
    if ($script:Results.Count -gt 0) { $script:Results | Format-Table -AutoSize Id, Name, Result, Actual -Wrap | Out-String -Width 200 | Write-Host }
    exit 2
}
finally {
    try { Stop-Transcript | Out-Null } catch { }
}






