$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$node = $env:DSH_NODE
if (-not $node) {
    $candidates = @(
        'C:\Program Files\nodejs\node.exe',
        (Join-Path $env:LOCALAPPDATA 'Programs\nodejs\node.exe')
    )
    $node = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $node) { throw '未找到 node.exe，请安装 Node.js 18+ 或设置 DSH_NODE。' }
if (-not $env:DSH_HOME) { $env:DSH_HOME = Join-Path $env:USERPROFILE '.dsh' }
Set-Location $here
& $node (Join-Path $here 'server.mjs')
