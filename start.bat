@echo off
chcp 65001 >nul
echo ========================================
echo  Chorus - 一键启动
echo ========================================
echo.

echo [1/3] 删除旧编译缓存...
if exist target rmdir /s /q target
if exist data rmdir /s /q data

echo [2/3] 编译后端...
call mvn clean compile -q
if %ERRORLEVEL% NEQ 0 (
    echo 编译失败！请检查代码错误。
    pause
    exit /b 1
)

echo [3/3] 启动后端...
echo.
echo 看到 "Started ChorusApplication" 后，新开终端执行:
echo   cd client ^&^& npm run dev
echo.
echo 浏览器打开 http://localhost:3000
echo.
call mvn spring-boot:run

pause
