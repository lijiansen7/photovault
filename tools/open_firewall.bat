@echo off
chcp 936 >nul 2>&1
setlocal

rem ============================================================
rem  放行 PhotoVault 端口（必须管理员身份运行）
rem  用法：右键本文件 - 以管理员身份运行
rem ============================================================

set "PORT=%~1"
if "%PORT%"=="" set "PORT=8765"

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo.
  echo   权限不足。请右键本文件，选择「以管理员身份运行」。
  echo.
  pause
  exit /b 1
)

echo 正在为端口 %PORT% 添加入站放行规则...
netsh advfirewall firewall delete rule name="PhotoVault %PORT%" >nul 2>&1
netsh advfirewall firewall add rule name="PhotoVault %PORT%" dir=in action=allow protocol=TCP localport=%PORT% profile=any

if %errorlevel% equ 0 (
  echo.
  echo   完成。端口 %PORT% 已放行，手机现在可以连了。
) else (
  echo.
  echo   添加失败。请手动在防火墙高级设置里添加入站规则。
)

echo.
pause
