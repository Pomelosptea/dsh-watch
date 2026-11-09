param(
    [string]$WatchIp = '',
    [int]$AdbPort = 5555,
    [string]$DshHost = '',
    [int]$DshPort = 18787,
    [string]$Token = 'change-me',
    [switch]$ForceReplaceZeroTier
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$ztApk = Join-Path $here 'ZeroTier-Watch-1.12.0.apk'
$dshApk = Join-Path $here 'DSH-Watch-1.0.0.apk'

function Find-Adb {
    $candidates = @()
    if ($env:ADB) { $candidates += $env:ADB }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $candidates += @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
        'C:\platform-tools\adb.exe'
    )
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($path in $candidates) {
        if ($path -and (Test-Path $path)) { return $path }
    }
    throw '找不到 adb.exe。请安装 Android Platform Tools，或设置 ADB 环境变量。'
}

$adb = Find-Adb
Write-Host "使用 adb: $adb" -ForegroundColor DarkGray

if ($WatchIp) {
    Write-Host "连接手表 $WatchIp`:$AdbPort ..." -ForegroundColor Cyan
    & $adb connect "$WatchIp`:$AdbPort" | Out-Host
    Start-Sleep -Seconds 2
}

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if (-not $devices) {
    throw '没有找到已授权的手表。请先在手表上开启 ADB 调试，并执行 adb connect / adb pair。'
}
$serial = ($devices[0] -split '\s+')[0]
Write-Host "目标设备: $serial" -ForegroundColor Green
$model = (& $adb -s $serial shell getprop ro.product.model) 2>$null
Write-Host "型号: $model" -ForegroundColor DarkGray

foreach ($apk in @($ztApk, $dshApk)) {
    if (-not (Test-Path $apk)) { throw "缺少安装包：$apk" }
}

function Install-Apk([string]$apk, [string]$label) {
    Write-Host "安装 $label ..." -ForegroundColor Cyan
    $output = & $adb -s $serial install -r -d $apk 2>&1
    $output | Out-Host
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -match 'Failure') {
        return $false
    }
    return $true
}

# ZeroTier: official and watch builds have different signatures, so replace if needed.
$ztInstalled = (& $adb -s $serial shell pm path com.zerotier.one) 2>$null
if ($ztInstalled -and $ForceReplaceZeroTier) {
    Write-Host '卸载旧 ZeroTier（会清空 app 数据）...' -ForegroundColor Yellow
    & $adb -s $serial uninstall com.zerotier.one | Out-Host
}

if (-not (Install-Apk $ztApk 'ZeroTier Watch')) {
    if ($ztInstalled -and -not $ForceReplaceZeroTier) {
        $answer = Read-Host '安装失败，可能是签名冲突。是否卸载旧 ZeroTier 后重装？(y/N)'
        if ($answer -match '^[Yy]') {
            & $adb -s $serial uninstall com.zerotier.one | Out-Host
            if (-not (Install-Apk $ztApk 'ZeroTier Watch')) {
                throw 'ZeroTier Watch 安装失败。'
            }
        } else {
            throw 'ZeroTier Watch 未安装。'
        }
    } else {
        throw 'ZeroTier Watch 安装失败。'
    }
}

if (-not (Install-Apk $dshApk 'DSH Watch')) {
    throw 'DSH Watch 安装失败。'
}

Write-Host '授予录音权限、延长息屏时间 ...' -ForegroundColor Cyan
& $adb -s $serial shell pm grant com.deepseek.dshwatch android.permission.RECORD_AUDIO 2>$null | Out-Null
& $adb -s $serial shell settings put system screen_off_timeout 600000 2>$null | Out-Null

if ($DshHost) {
    Write-Host "写入主机配置 $DshHost`:$DshPort ..." -ForegroundColor Cyan
    & $adb -s $serial shell am force-stop com.deepseek.dshwatch 2>$null | Out-Null
    & $adb -s $serial shell am start -n com.deepseek.dshwatch/.MainActivity `
        --es dsh_host $DshHost --ei dsh_port $DshPort --es dsh_token $Token | Out-Host
}

Write-Host '打开 ZeroTier 表端 ...' -ForegroundColor Cyan
& $adb -s $serial shell am start -n com.zerotier.one/com.zerotier.one.ui.NetworkListActivity | Out-Host

Write-Host ''
Write-Host '完成。接下来：' -ForegroundColor Green
Write-Host '  1. 在手表 ZeroTier 里点“加入网络”，输入 16 位 Network ID；'
Write-Host '  2. 系统弹出 VPN 授权时点“允许”；'
Write-Host '  3. 到 ZeroTier Central 授权该节点；'
Write-Host '  4. 打开 DSH，顶部显示“主机在线”后，按住鲸鱼说话。'
Write-Host ''
Write-Host '如果 DSH 显示离线：检查主机 bridge、防火墙 18787/TCP、手表 ZeroTier 是否已连接。' -ForegroundColor Yellow
