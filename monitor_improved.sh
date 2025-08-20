#!/bin/bash

echo "📊 Улучшенный мониторинг системы парсера"
echo "=========================================="

while true; do
    clear
    echo "📊 Мониторинг системы парсера - $(date)"
    echo "=========================================="
    
    # Проверяем процессы
    echo "🔄 Процессы парсера:"
    ps aux | grep "image-parser" | grep -v grep || echo "   Нет активных процессов"
    
    echo ""
    
    # Размеры файлов
    echo "📁 Размеры файлов:"
    if [ -f "parser_data.db" ]; then
        echo "   База данных: $(ls -lh parser_data.db | awk '{print $5}')"
    else
        echo "   База данных: не найдена"
    fi
    
    if [ -d "downloads" ]; then
        echo "   Папка downloads: $(du -sh downloads | awk '{print $1}')"
    else
        echo "   Папка downloads: не найдена"
    fi
    
    if [ -d "logs" ]; then
        echo "   Папка logs: $(du -sh logs | awk '{print $1}')"
    else
        echo "   Папка logs: не найдена"
    fi
    
    echo ""
    
    # Статистика базы данных
    echo "📊 Статистика базы данных:"
    if [ -f "parser_data.db" ]; then
        sqlite3 parser_data.db "SELECT COUNT(*) as total FROM link_results;" 2>/dev/null || echo "   Ошибка чтения БД"
        sqlite3 parser_data.db "SELECT status, COUNT(*) as count FROM link_results GROUP BY status;" 2>/dev/null || echo "   Ошибка чтения БД"
    else
        echo "   База данных не найдена"
    fi
    
    echo ""
    echo "⏰ Обновление через 30 секунд... (Ctrl+C для выхода)"
    sleep 30
done
