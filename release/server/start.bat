@echo off
rem 启动代码质量监控平台服务端
rem 用法: start.bat [端口] [源码目录]
setlocal

set PORT=%1
if "%PORT%"=="" set PORT=8080

set SRC=%2
if "%SRC%"=="" set SRC=..\demo\src

cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 java 命令，请先安装 JDK 8 或更高版本并配置 PATH。
    exit /b 1
)

echo 正在启动代码质量监控平台...
echo   端口:     %PORT%
echo   源码目录: %SRC%
echo   监控页面: http://127.0.0.1:%PORT%/
echo.

java -Xmx256m -Dcqm.src="%SRC%" -cp "classes;lib\h2-2.2.224.jar" com.cqm.server.Server %PORT%

endlocal
