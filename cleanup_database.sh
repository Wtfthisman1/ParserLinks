#!/bin/bash

echo "🧹 Очистка и оптимизация базы данных..."

# Создаем резервную копию
echo "📦 Создаю резервную копию..."
cp parser_data.db parser_data_backup_$(date +%Y%m%d_%H%M%S).db

# Удаляем все записи со статусом "empty"
echo "🗑️ Удаляю пустые записи..."
sqlite3 parser_data.db "DELETE FROM link_results WHERE status = 'empty';"

# Оптимизируем базу данных
echo "⚡ Оптимизирую базу данных..."
sqlite3 parser_data.db "VACUUM;"
sqlite3 parser_data.db "ANALYZE;"

# Показываем новую статистику
echo "📊 Новая статистика:"
sqlite3 parser_data.db "SELECT COUNT(*) as total_records FROM link_results;"
sqlite3 parser_data.db "SELECT status, COUNT(*) as count FROM link_results GROUP BY status;"

echo "✅ Очистка завершена!"
