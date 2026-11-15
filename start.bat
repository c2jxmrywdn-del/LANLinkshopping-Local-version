@echo off
REM ============================================================
REM LANLinkshopping Local Launcher (ASCII Thin Wrapper)
REM Automatically delegates to start-local-env.ps1
REM ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local-env.ps1"
if errorlevel 1 (
  echo.
  echo [!] Launcher failed. See messages above.
  pause
  exit /b 1
)
pause
