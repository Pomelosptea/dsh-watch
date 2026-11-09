param(
    [string]$SourceApk = 'zerotier-original.apk',
    [string]$OutApk = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $root
$tools = Join-Path $project 'tools'
if (-not [System.IO.Path]::IsPathRooted($SourceApk)) { $SourceApk = Join-Path $project $SourceApk }
if (-not $OutApk) { $OutApk = Join-Path $root 'ZeroTier-Watch-1.12.0.apk' }
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { throw '请先设置 JAVA_HOME 指向 JDK 17' }
$androidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { throw '请先设置 ANDROID_HOME 指向 Android SDK' }
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome
$java = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
$androidJar = Join-Path $androidHome 'platforms\android-35\android.jar'
$buildTools = Join-Path $androidHome 'build-tools\35.0.0'

if (-not (Test-Path $SourceApk)) { throw "找不到原始 APK：$SourceApk" }
if (-not (Test-Path $androidJar)) { throw "找不到 android.jar：$androidJar" }

$raw = Join-Path $root 'zt-smali'
if (-not (Test-Path (Join-Path $raw 'AndroidManifest.xml'))) {
    & $java -jar (Join-Path $tools 'apktool.jar') d -r -f -o $raw $SourceApk
    if ($LASTEXITCODE -ne 0) { throw 'apktool 解码失败' }
}

$classes = Join-Path $root 'classes'
$dex = Join-Path $root 'dex'
$baksmaliOut = Join-Path $root 'baksmali'
Remove-Item $classes, $dex, $baksmaliOut -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classes, $dex, $baksmaliOut | Out-Null

$source = Join-Path $root 'src\com\zerotier\one\ui\NetworkListActivity.java'
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'javac 编译失败' }

$classFiles = Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object { $_.FullName }
& (Join-Path $buildTools 'd8.bat') --min-api 26 --lib $androidJar --output $dex @classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

$cp = @(
    (Join-Path $tools 'baksmali.jar'),
    (Join-Path $tools 'dexlib2.jar'),
    (Join-Path $tools 'util.jar'),
    (Join-Path $tools 'guava.jar'),
    (Join-Path $tools 'jcommander.jar')
) -join ';'
& $java -cp $cp org.jf.baksmali.Main d (Join-Path $dex 'classes.dex') -o $baksmaliOut
if ($LASTEXITCODE -ne 0) { throw 'baksmali 失败' }

$target = Join-Path $raw 'smali\com\zerotier\one\ui'
Copy-Item (Join-Path $baksmaliOut 'com\zerotier\one\ui\NetworkListActivity*.smali') $target -Force

$unsigned = Join-Path $root 'ZeroTier-Watch-unsigned.apk'
$aligned = Join-Path $root 'ZeroTier-Watch-aligned.apk'
& $java -jar (Join-Path $tools 'apktool.jar') b -f $raw -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'apktool 回编译失败' }

& (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

$ks = Join-Path $root 'zt-watch.keystore'
if (-not (Test-Path $ks)) {
    & (Join-Path $javaHome 'bin\keytool.exe') -genkeypair -noprompt -keystore $ks `
        -storepass dshwatch -keypass dshwatch -alias dshwatch `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=DSH Watch, OU=DSH, O=DeepSeek Harness, L=Shanghai, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool 失败' }
}

& (Join-Path $buildTools 'apksigner.bat') sign --ks $ks --ks-key-alias dshwatch `
    --ks-pass pass:dshwatch --key-pass pass:dshwatch --out $OutApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }

Write-Output "Built: $OutApk"
Get-Item $OutApk | Select-Object Length, LastWriteTime
exit 0
