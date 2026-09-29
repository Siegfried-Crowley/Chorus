@echo off
REM ============================================================================
REM Tomcat 环境变量配置 — 放于 %CATALINA_HOME%\bin\setenv.bat
REM Spring Boot 外置 Tomcat 部署必须配置以下参数
REM ============================================================================

REM Spring Profile (postgres = 外置Tomcat + PostgreSQL 模式)
set JAVA_OPTS=%JAVA_OPTS% -Dspring.profiles.active=postgres

REM 数据库连接 (application.yml 的 postgres profile 会读取 DB_* 系统属性)
set JAVA_OPTS=%JAVA_OPTS% -DDB_URL=jdbc:postgresql://localhost:5432/chorus
set JAVA_OPTS=%JAVA_OPTS% -DDB_USER=chorus
set JAVA_OPTS=%JAVA_OPTS% -DDB_PASSWORD=chorus_dev_2026

REM JWT 密钥
set JAVA_OPTS=%JAVA_OPTS% -DJWT_SECRET=chorus-dev-secret-key-2026-minimum-256-bits-long

REM JVM 参数
set JAVA_OPTS=%JAVA_OPTS% -Xmx512m -Xms256m

REM UTF-8
set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8

echo [Chorus] Tomcat 环境变量已配置
