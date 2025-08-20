#!/bin/bash

echo "🔍 Мониторинг парсеров..."
echo "=========================="

while true; do
    echo "$(date '+%H:%M:%S') - Проверка статистики..."
    
    # Показываем статистику
    java -jar target/image-parser-1.0.0.jar stats 2>/dev/null | grep -E "(Всего:|Скачано:|Пустых:|Ошибок:)" | head -8
    
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
    
    echo "=========================="
    sleep 60  # Проверяем каждую минуту
done
