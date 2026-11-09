# ZeroTier 表端定制说明

这个目录只做一件事：把 ZeroTier 官方 Android 客户端的**启动页**换成适合 466×466 圆屏的版本，内核与 `VpnService` 保持官方原样。

## 重要的合规说明

- **官方 ZeroTier One for Android 采用 GPL-3.0**。本目录的 `src/com/zerotier/one/ui/NetworkListActivity.java` 是基于官方代码修改的衍生作品。
- 如果你公开本仓库：请随附 GPL-3.0 许可证文本，并说明「基于 ZeroTier One 1.12.0-3 修改」。
- **不要公开分发编译出来的 APK**：这份改版与官方签名不同，装到别人设备上会覆盖/冲突；上游也不建议分发非官方构建。
- 本目录的 `*.apk`、`*.idsig`、签名证书 `zt-watch.keystore`、以及 `zt-smali/` 等反编译中间产物都已在 `.gitignore` 中排除。

## 构建

```powershell
# 前置：JDK 17、Android SDK（platform 35 + build-tools 35.0.0）
# 把官方 ZeroTier APK 放到仓库根目录并命名为 zerotier-original.apk
$env:JAVA_HOME    = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\zerotier-watch\build-zerotier-watch.ps1
```

脚本会：apktool 解码 → `javac` 编译改造后的 Activity → `d8` → `baksmali` → 替换 smali → apktool 回编译 → `zipalign` → `apksigner` 签名（签名证书首次运行时用 `keytool` 生成，口令在脚本里，**仅适用于个人设备**）。

## 已知限制

- 不同固件的 VPN 授权页不同；没有 `VpnService` 确认界面的设备，只能靠改系统或换固件，本项目不处理。
- 表端只替换了网络列表界面，其余交互仍是官方实现。
