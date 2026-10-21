#!/bin/sh
# Запуск приложения
cd "$(dirname "$0")"
mkdir -p data
docker compose up -d --build
echo "Приложение запущено: http://localhost:47321"
