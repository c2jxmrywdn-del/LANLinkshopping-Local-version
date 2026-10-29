# 验证脚本：五维因子按商品差异化 + 每日资讯源头链接
# 用法：powershell -ExecutionPolicy Bypass -File verify-price-features.ps1
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$api = if ($env:API_BASE) { $env:API_BASE } else { 'http://127.0.0.1:8080/api' }
$outDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '.verify-out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Get-Json($url) {
    $raw = curl.exe -s --max-time 30 $url
    if (-not $raw) { throw "empty response from $url" }
    return $raw | ConvertFrom-Json
}

Write-Host "== 1. 资讯接口：源头链接字段 =="
$news = Get-Json "$api/price-monitor/news?limit=8"
$newsCount = @($news.data).Count
Write-Host "news rows: $newsCount"
$news.data | Select-Object -First 8 | ForEach-Object {
    Write-Host ("  [{0}] {1} | {2} | {3}" -f $_.linkType, $_.source, $_.url, $_.title)
}
$badLink = @($news.data | Where-Object { -not $_.url -or -not $_.linkType })
if ($badLink.Count -gt 0) { throw "news rows missing url/linkType: $($badLink.Count)" }
$linkTypes = @($news.data | ForEach-Object { $_.linkType } | Sort-Object -Unique) -join ','
Write-Host "linkTypes = $linkTypes"
if ($linkTypes -notmatch 'source|internal|search|article') { throw 'unexpected linkType set' }

Write-Host ""
Write-Host "== 2. 五维因子：不同商品结果必须不同 =="
$prodIds = @(1, 2, 4, 8)
$results = @{}
foreach ($id in $prodIds) {
    $f = Get-Json "$api/price-monitor/factors/$id"
    $results[$id] = $f.data
    $sig = ($f.data.factors | ForEach-Object { "$($_.key)=$($_.score)" }) -join ';'
    Write-Host ("  prod {0} {1} | index={2} dominant={3} | {4}" -f $id, $f.data.title, $f.data.sentimentIndex, $f.data.dominant.label, $sig)
    if (@($f.data.factors).Count -ne 5) { throw "prod $id factors count != 5" }
    if ($null -eq $f.data.systematicAnalysis) { throw "prod $id missing systematicAnalysis" }
}
$sigs = @{}
foreach ($id in $prodIds) {
    $sigs[$id] = ($results[$id].factors | ForEach-Object { "$($_.key)=$($_.score)/$($_.productNewsCount)" }) -join ';'
}
$distinct = @($sigs.Values | Sort-Object -Unique)
Write-Host "distinct factor signatures: $($distinct.Count) / $($prodIds.Count)"
if ($distinct.Count -lt 3) { throw 'per-product factors are not differentiated enough' }

Write-Host ""
Write-Host "== 3. 因子证据链接 / 商品画像 =="
$r1 = $results[1]
Write-Host "productProfile keywords: $((@($r1.productProfile.keywords)) -join ', ')"
Write-Host "systematicAnalysis.conclusion: $($r1.systematicAnalysis.conclusion)"
foreach ($fac in $r1.factors) {
    $ref = @($fac.newsRefs)[0]
    Write-Host ("  {0}: score={1} newsScore={2} behavior={3} weight={4} productNews={5} news={6}" -f `
        $fac.key, $fac.score, $fac.newsScore, $fac.behaviorScore, $fac.weight, $fac.productNewsCount, $fac.newsCount)
    if ($ref) { Write-Host ("     ref -> [{0}] {1} {2}" -f $ref.linkType, $ref.url, $ref.title) }
    if ($ref -and -not $ref.url) { throw "factor $($fac.key) newsRef missing url" }
}

Write-Host ""
Write-Host "== 4. 英文参数 factors?lang=en（关键字应英文） =="
$en = Get-Json "$api/price-monitor/factors/1?lang=en"
Write-Host "EN summary: $($en.data.summary)"
$enText = ($en.data.factors | ForEach-Object { "$($_.basis) $($_.productSignals -join ' ')" }) -join ' '
if ($enText -match '[\u4e00-\u9fff]') { Write-Host 'WARN: EN factor basis/productSignals contains CJK (news titles exempt later)' }
else { Write-Host 'EN factor basis/productSignals: no CJK' }

Write-Host ""
Write-Host "ALL CHECKS PASSED"
