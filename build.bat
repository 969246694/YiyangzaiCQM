@echo off
rem 构建全部模块（仅依赖 JDK 自带的 javac / jar，无需 Maven / Gradle）
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
set JP=%LIB%\javaparser-core-3.26.4.jar

for %%J in ("%BB%" "%BBA%" "%H2%") do (
    if not exist %%J (
        echo [错误] 缺少依赖 %%J
        exit /b 1
    )
)

echo === 1/5 清理 ===
if exist out rmdir /s /q out
if exist agent\cqm-agent.jar del /q agent\cqm-agent.jar
if exist agent\cqm-agent-bootstrap.jar del /q agent\cqm-agent-bootstrap.jar
mkdir out\server out\demo out\agent out\boot 2>nul

echo === 2/5 编译服务端 ===
dir /s /b server\src\*.java > out\s.txt
javac -encoding UTF-8 -cp "%H2%;%JP%" -d out\server @out\s.txt || exit /b 1

echo === 3/5 编译示例应用 ===
dir /s /b demo\src\*.java > out\d.txt
javac -encoding UTF-8 -d out\demo @out\d.txt || exit /b 1

echo === 4/5 编译诊断探针 ===
if exist diag\src (
    mkdir out\diag 2>nul
    dir /s /b diag\src\*.java > out\g.txt
    javac -encoding UTF-8 -cp "%BB%;%BBA%" -d out\diag @out\g.txt || exit /b 1
)

echo === 5/5 编译探针并打包 ===
dir /s /b agent\src\*.java > out\a.txt
javac -encoding UTF-8 -cp "%BB%;%BBA%" -d out\agent @out\a.txt || exit /b 1

rem 聚合器单独放进引导类加载器，并从探针主 JAR 中移除
mkdir out\boot\com\cqm\agent 2>nul
move /y out\agent\com\cqm\agent\MetricsAggregator*.class out\boot\com\cqm\agent\ >nul

(
echo Manifest-Version: 1.0
echo Premain-Class: com.cqm.agent.CqmAgent
echo Agent-Class: com.cqm.agent.CqmAgent
echo Can-Redefine-Classes: true
echo Can-Retransform-Classes: true
echo.
) > out\MANIFEST.MF

jar cf agent\cqm-agent-bootstrap.jar -C out\boot .
jar cfm agent\cqm-agent.jar out\MANIFEST.MF -C out\agent .

echo.
echo 构建完成：
echo   out\server  out\demo  out\agent
echo   agent\cqm-agent.jar  agent\cqm-agent-bootstrap.jar
echo.
echo 运行服务端：
echo   java -Dcqm.src=demo/src -cp "out/server;%H2%" com.cqm.server.Server 8080
endlocal
