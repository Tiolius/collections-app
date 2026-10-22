-- Разовое добавление ролей пользователей (после migrate-to-users.sql).
-- Добавляет столбец users.role (все — «Пользователь») и делает Evered администратором.
-- Запускать один раз на остановленном приложении, предварительно сделав копию data/app.db.

-- Остановиться при первой ошибке (например, при повторном запуске) — ничего не изменится
.bail on

BEGIN;
ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user';
UPDATE users SET role = 'admin' WHERE name = 'Evered';
COMMIT;
