@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   DSH Watch / ZeroTier - OPPO Watch X2
echo ============================================
echo.
set /p WATCH_IP=手表 ADB IP（已 adb connect 可留空）: 
set /p DSH_HOST=主机 ZeroTier IP（例如 10.42.0.7）: 
set /p DSH_PORT=桥接端口（默认 18787）: 
set /p DSH_TOKEN=令牌（默认 change-me）: 
if "%DSH_PORT%"=="" set DSH_PORT=18787
if "%DSH_TOKEN%"=="" set DSH_TOKEN=change-me
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-watch.ps1" -WatchIp "%WATCH_IP%" -DshHost "%DSH_HOST%" -DshPort %DSH_PORT% -Token "%DSH_TOKEN%"
echo.
pause
