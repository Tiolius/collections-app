#!/bin/sh
# Остановка приложения (данные в папке data сохраняются)
cd "$(dirname "$0")"
docker compose down
echo "Приложение остановлено"
