@echo off
rem Build, stop the running app (if any), start the new version.
rem If the build fails, the running app is left as is.
cd /d "%~dp0"
if not exist data mkdir data
docker compose build
if errorlevel 1 (
  echo Build failed. Is Docker Desktop running? The app was not restarted.
  pause
  exit /b 1
)
docker compose down
docker compose up -d
if errorlevel 1 (
  echo Failed to start. Is Docker Desktop running?
) else (
  echo App started: http://localhost:47321
)
pause
