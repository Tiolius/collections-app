#!/bin/sh
# Stops the application (data in the data folder is kept)
cd "$(dirname "$0")"
docker compose down
echo "Приложение остановлено"
