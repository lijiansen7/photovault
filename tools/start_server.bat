@echo off
chcp 936 >nul 2>&1
setlocal

rem ============================================================
rem  PhotoVault NAS 服务端 - Windows 一键启动
rem  用法：双击本文件
rem        命令行：start_server.bat [Token] [端口]
rem ============================================================

set "ROOT=%~dp0.."
set "PORT=%~2"
if "%PORT%"=="" set "PORT=8765"
set "TOKEN=%~1"
if "%TOKEN%"=="" set "TOKEN=photovault"

set "PY=%USERPROFILE%\.workbuddy\binaries\python\envs\photovault\Scripts\python.exe"
if not exist "%PY%" set "PY=python"

echo.
echo   PhotoVault 服务端启动中
echo   Token : %TOKEN%
echo   端口  : %PORT%
echo   数据  : %ROOT%\server\data
echo.

netstat -ano | findstr ":%PORT% " | findstr "LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
  echo   端口 %PORT% 已被占用，服务应该已经在运行了。
  echo   直接打开 http://127.0.0.1:%PORT% 验收即可。
  echo   想重启就先关掉占用该端口的程序。
  echo.
  pause
  exit /b 1
)

set "LANIP="
for /f "usebackq delims=" %%i in (`"%PY%" "%ROOT%\tools\show_ip.py"`) do set "LANIP=%%i"

if defined LANIP (
  echo   手机 App 的 NAS 地址填：
  echo       http://%LANIP%:%PORT%
  echo   Token 填：%TOKEN%
) else (
  echo   没检测到局域网地址，请检查网络连接。
)

echo.
echo   浏览器验收：http://127.0.0.1:%PORT%
echo   停止服务：按 Ctrl+C
echo ============================================================

set "DATA_DIR=%ROOT%\server\data"
set "ACCESS_TOKEN=%TOKEN%"
pushd "%ROOT%\server"
"%PY%" run.py
popd
endlocal
