@echo off
setlocal

set "ROOT=%~dp0"
set "BACKEND=%ROOT%backend"
set "FRONTEND=%ROOT%frontend"
set "SQLFILE=%BACKEND%\src\main\resources\sql\schema.sql"
set "DBPASS=%SPRING_DATASOURCE_PASSWORD%"
set "MYSQL="
set "SPRING_PROFILES_ACTIVE=dev"
if not defined DBPASS (
  echo [!] Set SPRING_DATASOURCE_PASSWORD in your environment before running this launcher.
  pause
  exit /b 1
)
set "SPRING_DATASOURCE_PASSWORD=%DBPASS%"
set "MYSQL_PWD=%DBPASS%"

echo ==========================================
echo   LANLinkshopping - one click launcher
echo ==========================================
echo.

REM ---------- auto-detect mysql.exe ----------
for /f "delims=" %%i in ('where mysql 2^>nul') do if not defined MYSQL set "MYSQL=%%i"
for %%R in ("C:\Program Files\MySQL" "C:\Program Files (x86)\MySQL" "D:\Program Files\MySQL" "E:\Program Files\MySQL") do (
  for /d %%V in ("%%~R\MySQL Server *") do if not defined MYSQL if exist "%%V\bin\mysql.exe" set "MYSQL=%%V\bin\mysql.exe"
)
if not defined MYSQL for /d %%V in ("C:\Program Files\MySQL\*") do if exist "%%V\bin\mysql.exe" set "MYSQL=%%V\bin\mysql.exe"
if not defined MYSQL for /d %%V in ("C:\MySQL*" "D:\MySQL*" "E:\MySQL*") do if exist "%%V\bin\mysql.exe" set "MYSQL=%%V\bin\mysql.exe"

if not defined MYSQL goto :no_mysql
echo [i] Found mysql: %MYSQL%

echo [0] Checking database ...
"%MYSQL%" -uroot -e "CREATE DATABASE IF NOT EXISTS lanlink_shopping;" 1>nul 2>nul
"%MYSQL%" -uroot lanlink_shopping -e "SELECT 1 FROM t_user LIMIT 1;" 1>nul 2>nul
if errorlevel 1 (
    echo     First run - creating tables and dictionary data ...
    "%MYSQL%" -uroot < "%SQLFILE%"
    echo     Database initialized.
) else (
    echo     Database ready, skipping schema.
)
goto :start_apps

:no_mysql
echo [!] mysql.exe not found in PATH or common install folders.
echo     Start MySQL and run schema.sql manually if this is the first time.
echo.

:start_apps
set "MYSQL_PWD="
set "DBPASS="
echo [1/2] Starting backend  -^> http://localhost:8080/api
start "LANLink-Backend" /D "%BACKEND%" cmd /k "java -jar target\lanlink-shopping.jar --spring.profiles.active=dev"

timeout /t 8 /nobreak 1>nul

echo [2/2] Starting frontend -^> http://localhost:5173
start "LANLink-Frontend" /D "%FRONTEND%" cmd /k "npm run dev"

timeout /t 5 /nobreak 1>nul
echo.
echo Done. Open http://localhost:5173 in your browser.
echo Demo accounts, password 123456:
echo   admin    13800000000
echo   buyer    13900000001
echo   merchant 13700000002
echo Tip: click the footer text 10 times fast to open the admin console.
echo To stop: close the two black windows.
echo.
pause
