param(
    [int]$Port = 18787,
    [string]$Token = 'change-me',
    [switch]$AutoStart
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$hostDir = Join-Path $here 'host'
$configPath = Join-Path $hostDir 'config.json'
$serverPath = Join-Path $hostDir 'server.mjs'
if (-not (Test-Path $serverPath)) { throw "缺少 $serverPath" }

$node = $env:DSH_NODE
if (-not $node) {
    $candidates = @(
        'C:\Program Files\nodejs\node.exe',
        (Join-Path $env:LOCALAPPDATA 'Programs\nodejs\node.exe')
    )
    $node = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $node) {
    $cmd = Get-Command node -ErrorAction SilentlyContinue
    if ($cmd) { $node = $cmd.Source }
}
if (-not $node) { throw '未找到 Node.js。请先安装 Node.js 18+。' }

$config = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$config.port = $Port
$config.token = $Token
$config | ConvertTo-Json -Depth 6 | Set-Content $configPath -Encoding UTF8
Write-Host "配置已写入 $configPath" -ForegroundColor Cyan

try {
    $existing = Get-NetFirewallRule -DisplayName "DSH Watch Bridge $Port" -ErrorAction SilentlyContinue
    if (-not $existing) {
        New-NetFirewallRule -DisplayName "DSH Watch Bridge $Port" `
            -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any | Out-Null
        Write-Host "已添加防火墙规则 TCP $Port" -ForegroundColor Green
    } else {
        Write-Host "防火墙规则已存在 (TCP $Port)" -ForegroundColor DarkGray
    }
} catch {
    Write-Host "防火墙规则添加失败（需要管理员权限）：$($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "请手动执行：New-NetFirewallRule -DisplayName 'DSH Watch Bridge $Port' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any" -ForegroundColor Yellow
}

$running = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($running) {
    Write-Host "端口 $Port 已在监听，桥接已经在运行。" -ForegroundColor DarkGray
} else {
    Write-Host '桥接尚未启动。现在会打开一个桥接窗口；保持它开着。' -ForegroundColor Yellow
    Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', "`"$hostDir\start-bridge.bat`"") -WorkingDirectory $hostDir
    Start-Sleep -Seconds 3
}

if ($AutoStart) {
    try {
        $action = New-ScheduledTaskAction -Execute $node -Argument 'server.mjs' -WorkingDirectory $hostDir
        $trigger = New-ScheduledTaskTrigger -AtLogOn
        Register-ScheduledTask -TaskName 'DSH Watch Bridge' -Action $action -Trigger $trigger `
            -Description 'DeepSeek Harness watch bridge' -RunLevel Highest -Force | Out-Null
        Write-Host '已设置登录自启动（任务计划程序：DSH Watch Bridge）' -ForegroundColor Green
    } catch {
        Write-Host "自启动设置失败：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host ''
Write-Host '自检：' -ForegroundColor Cyan
try {
    $health = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/health" -TimeoutSec 5
    Write-Host $health.Content -ForegroundColor Green
} catch {
    Write-Host "健康检查失败：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host '请查看桥接窗口里的报错，或手动运行 host\start-bridge.bat。' -ForegroundColor Yellow
}
Write-Host ''
Write-Host "手表填：主机 ZeroTier IP，端口 $Port，令牌 $Token" -ForegroundColor White
