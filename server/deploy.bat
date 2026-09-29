@echo off
REM ============================================================================
REM Chorus — 部署到外置 Tomcat
REM 用法: deploy.bat [tomcat路径]
REM       默认 C:\Program Files\Apache Software Foundation\Tomcat 10.0
REM ============================================================================

set TOMCAT_HOME=%1
if "%TOMCAT_HOME%"=="" set TOMCAT_HOME=C:\Program Files\Apache Software Foundation\Tomcat 10.0

echo [Chorus] ========================================
echo [Chorus] 部署到 Tomcat: %TOMCAT_HOME%
echo [Chorus] ========================================

REM 1. 确保中间件运行中
echo [Chorus] [1/4] 检查中间件...
echo   - 请确保 PostgreSQL 运行中 (端口 5432)

REM 2. 复制 setenv.bat
echo [Chorus] [2/4] 配置 Tomcat 环境变量...
if not exist "%TOMCAT_HOME%\bin\setenv.bat" (
    copy server\setenv.bat "%TOMCAT_HOME%\bin\setenv.bat"
    echo   已复制 setenv.bat 到 Tomcat bin 目录
) else (
    echo   setenv.bat 已存在，跳过
)

REM 3. 构建 WAR
echo [Chorus] [3/4] 构建 WAR 包...
cd /d "%~dp0"
call mvn clean package -DskipTests -q
if %ERRORLEVEL% NEQ 0 (
    echo [Chorus] 构建失败!
    exit /b 1
)
echo   构建成功: target\chorus-1.0.0.war

REM 4. 部署到 Tomcat (注意：上面已 cd 到 server 目录，WAR 在 target\ 下)
echo [Chorus] [4/4] 部署 WAR 到 Tomcat...
copy /Y target\chorus-1.0.0.war "%TOMCAT_HOME%\webapps\chorus.war"
if %ERRORLEVEL% NEQ 0 (
    echo [Chorus] 复制失败!
    exit /b 1
)

echo [Chorus] ========================================
echo [Chorus] 部署完成!
echo [Chorus]
echo [Chorus] 启动 Tomcat:
echo   "%TOMCAT_HOME%\bin\startup.bat"
echo [Chorus]
echo [Chorus] 访问地址:
echo   http://localhost:8080/chorus
echo [Chorus]
echo [Chorus] API 地址:
echo   http://localhost:8080/chorus/api
echo [Chorus]
echo [Chorus] Gateway WS:
echo   ws://localhost:8080/chorus/ws
echo [Chorus] ========================================
