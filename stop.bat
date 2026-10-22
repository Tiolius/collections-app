@echo off
rem The window waits for a key only on errors.
cd /d "%~dp0"
docker compose down
if errorlevel 1 (
  echo Failed to stop. Is Docker Desktop running?
  pause
  exit /b 1
)
echo App stopped
