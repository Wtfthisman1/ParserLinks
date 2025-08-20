#!/bin/bash

echo "🧹 Полная очистка системы..."

# Останавливаем все процессы парсера
echo "🛑 Останавливаю все процессы парсера..."
pkill -f "image-parser" 2>/dev/null || true

# Очищаем логи
echo "🗑️ Очищаю старые логи..."
rm -f parser.log parser.2025-08-19.log
rm -f logs/*.log 2>/dev/null || true

# Очищаем базу данных от пустых записей
echo "🗑️ Очищаю базу данных..."
sqlite3 parser_data.db "DELETE FROM link_results WHERE status = 'empty';" 2>/dev/null || true
sqlite3 parser_data.db "VACUUM;" 2>/dev/null || true

# Очищаем папку загрузок
echo "🗑️ Очищаю папку загрузок..."
rm -rf downloads/* 2>/dev/null || true

# Показываем статистику
echo "📊 Статистика после очистки:"
echo "Размер базы данных:"
ls -lh parser_data.db 2>/dev/null || echo "База данных не найдена"

echo "Размер папки downloads:"
du -sh downloads 2>/dev/null || echo "Папка downloads не найдена"

echo "Размер папки logs:"
du -sh logs 2>/dev/null || echo "Папка logs не найдена"

echo "✅ Очистка завершена!"
