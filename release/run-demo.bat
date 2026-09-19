@echo off
rem 运行示例应用（挂载探针），用于快速查看平台效果
rem 前置条件：服务端已启动（server\start.bat）
setlocal
cd /d "%~dp0"

set SERVER=%1
if "%SERVER%"=="" set SERVER=http://127.0.0.1:8080

set SECONDS=%2
if "%SECONDS%"=="" set SECONDS=20

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 java 命令，请先安装 JDK 8 或更高版本。
    exit /b 1
)

echo 运行示例应用 %SECONDS% 秒，探针上报到 %SERVER%
echo 请同时打开 %SERVER% 观察数据
echo.

java -javaagent:agent\cqm-agent.jar ^
     -Dcqm.packages=com.demo ^
     -Dcqm.server=%SERVER% ^
     -Dcqm.interval=3000 ^
     -Dcqm.app=demo-app ^
     -cp demo\classes ^
     com.demo.BizService %SECONDS%

echo.
echo 示例应用已结束运行。
endlocal
