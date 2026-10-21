@echo off
cd /d "%~dp0"
if not exist data mkdir data
docker compose up -d --build
if errorlevel 1 (
  echo Failed to start. Is Docker Desktop running?
) else (
  echo App started: http://localhost:47321
)
pause
