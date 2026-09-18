@echo off
rem 重新编译全部模块（仅依赖 JDK 自带的 javac / jar）
setlocal enabledelayedexpansion
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 javac，请先安装 JDK 8 或更高版本。
    exit /b 1
)

set LIB=lib
set BB=%LIB%\byte-buddy-1.14.19.jar
set BBA=%LIB%\byte-buddy-agent-1.14.19.jar
set H2=%LIB%\h2-2.2.224.jar

echo === 1/4 清理 ===
if exist out rmdir /s /q out
mkdir out\server out\demo out\agent 2>nul

echo === 2/4 编译服务端 ===
dir /s /b src\server\*.java > out\s.txt
javac -encoding UTF-8 -cp "%H2%" -d out\server @out\s.txt || exit /b 1

echo === 3/4 编译示例应用 ===
dir /s /b src\demo\*.java > out\d.txt
javac -encoding UTF-8 -d out\demo @out\d.txt || exit /b 1

echo === 4/4 编译探针并打包 ===
dir /s /b src\agent\*.java > out\a.txt
javac -encoding UTF-8 -cp "%BB%;%BBA%" -d out\agent @out\a.txt || exit /b 1

rem 引导辅助类：单独打包，并从探针主 JAR 中排除
mkdir out\boot\com\cqm\agent 2>nul
move /y out\agent\com\cqm\agent\MetricsAggregator*.class out\boot\com\cqm\agent\ >nul
jar cf agent\cqm-agent-bootstrap.jar -C out\boot .
jar cf agent\cqm-agent.jar -C out\agent .

echo.
echo 构建完成：
echo   out\server  out\demo  out\agent
echo   agent\cqm-agent.jar  agent\cqm-agent-bootstrap.jar
echo.
echo 提示：如需替换 server\classes，请手动复制 out\server 下的内容。
endlocal
