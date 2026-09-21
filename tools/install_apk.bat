@echo off
chcp 936 >nul 2>&1
setlocal

rem ============================================================
rem  安装 PhotoVault 到手机
rem  用法：双击本文件（手机需开启 USB 调试并已授权）
rem ============================================================

set "ROOT=%~dp0.."
set "ADB=%USERPROFILE%\.workbuddy\binaries\android-sdk\platform-tools\adb.exe"
set "APK=%ROOT%\android\app\build\outputs\apk\debug\app-debug.apk"

if not exist "%ADB%" (
  echo   没找到 adb，请先运行：python tools\setup_android_sdk.py
  pause
  exit /b 1
)
if not exist "%APK%" (
  echo   没找到 APK，请先编译：cd android ^&^& gradlew.bat assembleDebug
  pause
  exit /b 1
)

echo.
echo   等待设备...
"%ADB%" wait-for-device

for /f "usebackq delims=" %%i in (`"%ADB%" get-state`) do set "STATE=%%i"
if not "%STATE%"=="device" (
  echo   设备未授权。请在手机上点「允许 USB 调试」，然后重新运行本脚本。
  pause
  exit /b 1
)

echo   安装 %APK%
"%ADB%" install -r "%APK%"
if %errorlevel% neq 0 (
  echo   安装失败。
  pause
  exit /b 1
)

echo.
echo   安装完成。
echo.
set /p REV=是否设置 USB 端口转发？这样手机填 http://127.0.0.1:8765 就能连电脑，不用管防火墙 [y/n]:
if /i "%REV%"=="y" (
  "%ADB%" reverse tcp:8765 tcp:8765
  echo   已转发。App 设置里填：http://127.0.0.1:8765
  echo   注意：重新插拔数据线后需要重跑一次。
)

echo.
pause
