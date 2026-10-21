@echo off
cd /d "%~dp0"
docker compose down
echo App stopped
pause
