#!/bin/bash

echo "📊 Мониторинг 6-часового теста парсера"
echo "Нажмите Ctrl+C для остановки"

while true; do
    clear
    echo "=== $(date) ==="
    echo ""
    echo "📈 Статистика парсера:"
    java -jar target/image-parser-1.0.0.jar stats | head -20
    echo ""
    echo "💾 Размер базы данных:"
    ls -lh parser_data.db 2>/dev/null || echo "База данных не найдена"
    echo ""
    echo "📁 Загруженные файлы:"
    find downloads -type f 2>/dev/null | wc -l | xargs echo "Количество:"
    echo ""
    echo "🔄 Процессы парсера:"
    ps aux | grep image-parser | grep -v grep
    echo ""
    echo "⏰ Обновление через 30 секунд..."
    sleep 30
done
