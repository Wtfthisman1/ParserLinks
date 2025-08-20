#!/bin/bash

echo "🔍 Мониторинг 8-часового теста парсеров..."
echo "=========================================="

while true; do
    echo "$(date '+%H:%M:%S') - Проверка статуса..."
    
    # Проверяем что процессы работают
    if pgrep -f "image-parser.*imgbb" > /dev/null; then
        echo "✅ Imgbb парсер работает"
    else
        echo "❌ Imgbb парсер остановлен"
    fi
    
    if pgrep -f "image-parser.*postimages" > /dev/null; then
        echo "✅ Postimages парсер работает"
    else
        echo "❌ Postimages парсер остановлен"
    fi
    
    # Показываем статистику
    echo "📊 Статистика:"
    java -jar target/image-parser-1.0.0.jar stats 2>/dev/null | grep -E "(Всего:|Скачано:|Пустых:|Ошибок:)" | head -8
    
    # Проверяем скачанные файлы
    if [ -d "downloads" ]; then
        file_count=$(find downloads -type f | wc -l)
        total_size=$(du -sh downloads 2>/dev/null | cut -f1)
        echo "📁 Скачано файлов: $file_count"
        echo "💾 Общий размер: $total_size"
        
        if [ $file_count -gt 0 ]; then
            echo "📋 Последние 5 файлов:"
            ls -la downloads/ | tail -5
        fi
    else
        echo "📁 Папка downloads не существует"
    fi
    
    # Показываем размер логов
    if [ -f "imgbb_8h.log" ]; then
        imgbb_log_size=$(du -h imgbb_8h.log | cut -f1)
        echo "📄 Imgbb лог: $imgbb_log_size"
    fi
    
    if [ -f "postimages_8h.log" ]; then
        postimages_log_size=$(du -h postimages_8h.log | cut -f1)
        echo "📄 Postimages лог: $postimages_log_size"
    fi
    
    echo "=========================================="
    sleep 60  # Проверяем каждую минуту
done
