#!/bin/sh
# Starts the application: build, stop the running one (if any), start the new version.
# If the build fails, the running application is left alone.
cd "$(dirname "$0")"
mkdir -p data
docker compose build || { echo "Сборка не удалась, приложение не перезапущено"; exit 1; }
docker compose down
docker compose up -d || { echo "Не удалось запустить приложение"; exit 1; }
echo "Приложение запущено: http://localhost:47321"
