@echo off
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "BACKEND=%ROOT%backend"
set "FRONTEND=%ROOT%frontend"
set "ENVFILE=%ROOT%.env"

echo ==========================================
echo   LANLinkshopping 本地一键启动
echo ==========================================
echo.

REM ---------- 读取 .env ----------
if not exist "%ENVFILE%" (
  echo [!] 未找到 .env，请先复制 .env.example 为 .env 并填写 MySQL 密码与沙盒令牌。
  pause
  exit /b 1
)
for /f "usebackq tokens=1,* delims==" %%A in ("%ENVFILE%") do (
  set "LINE=%%A"
  if not "!LINE!"=="" if not "!LINE:~0,1!"=="#" (
    set "%%A=%%B"
  )
)

if not defined SPRING_DATASOURCE_PASSWORD (
  echo [!] .env 中未设置 SPRING_DATASOURCE_PASSWORD
  pause
  exit /b 1
)

REM ---------- 1. 基础服务（Mailpit + 客服沙盒）----------
echo [1/4] 启动 Docker 基础服务 Mailpit + 客服学习沙盒 ...
docker compose -f "%ROOT%docker-compose.local.yml" up -d --build
if errorlevel 1 (
  echo [!] Docker 基础服务启动失败，请确认 Docker Desktop 已运行。
  pause
  exit /b 1
)

REM ---------- 2. 后端 ----------
echo [2/4] 启动后端 -^> http://localhost:8080/api
if exist "%BACKEND%\target\lanlink-shopping.jar" (
  start "LANLink-Backend" /D "%BACKEND%" cmd /k "set SPRING_DATASOURCE_PASSWORD=%SPRING_DATASOURCE_PASSWORD%&& set SPRING_DATASOURCE_URL=%SPRING_DATASOURCE_URL%&& set SPRING_DATASOURCE_USERNAME=%SPRING_DATASOURCE_USERNAME%&& set SUPPORT_SANDBOX_URL=%SUPPORT_SANDBOX_URL%&& set SUPPORT_SANDBOX_TOKEN=%SUPPORT_SANDBOX_TOKEN%&& set SUPPORT_SANDBOX_MODE=%SUPPORT_SANDBOX_MODE%&& java -jar target\lanlink-shopping.jar --spring.profiles.active=dev"
) else (
  start "LANLink-Backend" /D "%BACKEND%" cmd /k "set SPRING_DATASOURCE_PASSWORD=%SPRING_DATASOURCE_PASSWORD%&& set SPRING_DATASOURCE_URL=%SPRING_DATASOURCE_URL%&& set SPRING_DATASOURCE_USERNAME=%SPRING_DATASOURCE_USERNAME%&& set SUPPORT_SANDBOX_URL=%SUPPORT_SANDBOX_URL%&& set SUPPORT_SANDBOX_TOKEN=%SUPPORT_SANDBOX_TOKEN%&& set SUPPORT_SANDBOX_MODE=%SUPPORT_SANDBOX_MODE%&& call "%ROOT%mvnw.cmd" -q spring-boot:run -Dspring-boot.run.profiles=dev"
)

timeout /t 20 /nobreak 1>nul

REM ---------- 3. 前端 ----------
echo [3/4] 启动前端 -^> http://localhost:5173
start "LANLink-Frontend" /D "%FRONTEND%" cmd /k "npm run dev"

REM ---------- 4. 完成 ----------
echo [4/4] Done.
echo.
echo   商城前端     http://localhost:5173
echo   后端接口     http://localhost:8080/api
echo   邮件收件箱   http://localhost:8081   (Mailpit)
echo   客服沙盒     http://127.0.0.1:18080/health
echo.
echo   演示账号（密码 123456）:
echo     admin    13800000000
echo     buyer    13900000001
echo     merchant 13700000002
echo.
echo   停止：关闭两个黑色窗口；基础服务: docker compose -f docker-compose.local.yml down
echo.
pause
