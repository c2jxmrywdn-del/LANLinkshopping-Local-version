@echo off
REM ============================================================
REM LANLinkshopping Local Launcher (thin ASCII wrapper).
REM All real logic lives in start-local-env.ps1 (UTF-8 safe).
REM This file is intentionally ASCII-only: cmd on Chinese Windows
REM parses .bat as GBK, so any non-ASCII text here would corrupt
REM variable names and break the script.
REM ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local-env.ps1"
if errorlevel 1 (
  echo.
  echo [!] Launcher failed. See messages above.
  pause
  exit /b 1
)
pause
