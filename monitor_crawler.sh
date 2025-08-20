#!/bin/bash

echo "📊 Мониторинг краулера запущен..."
echo "⏰ Нажмите Ctrl+C для остановки мониторинга"
echo ""

while true; do
    clear
    echo "=== МОНИТОРИНГ КРАУЛЕРА ==="
    echo "⏰ Время: $(date)"
    echo ""
    
    # Проверяем основной процесс
    if [ -f "logs/background_pid.txt" ]; then
        BACKGROUND_PID=$(cat logs/background_pid.txt)
        if ps -p "$BACKGROUND_PID" > /dev/null 2>&1; then
            echo "✅ Основной процесс: работает (PID: $BACKGROUND_PID)"
        else
            echo "❌ Основной процесс: остановлен (PID: $BACKGROUND_PID)"
        fi
    else
        echo "⚠️ PID файл основного процесса не найден"
    fi
    
    echo ""
    echo "=== ПРОЦЕССЫ КРАУЛЕРОВ ==="
    
    # Проверяем процессы краулеров
    for hosting in imgbb postimages; do
        if [ -f "logs/${hosting}_pid.txt" ]; then
            PID=$(cat "logs/${hosting}_pid.txt")
            if ps -p "$PID" > /dev/null 2>&1; then
                echo "✅ $hosting: работает (PID: $PID)"
                
                # Показываем последние строки лога
                LOG_FILE=$(ls -t logs/crawler_${hosting}_*.log 2>/dev/null | head -1)
                if [ -n "$LOG_FILE" ]; then
                    echo "   📝 Последние строки лога:"
                    tail -3 "$LOG_FILE" | sed 's/^/   /'
                fi
            else
                echo "❌ $hosting: остановлен (PID: $PID)"
            fi
        else
            echo "⚠️ $hosting: PID файл не найден"
        fi
        echo ""
    done
    
    echo "=== СТАТИСТИКА БАЗЫ ДАННЫХ ==="
    java -jar target/image-parser-1.0.0.jar stats 2>/dev/null | head -20
    
    echo ""
    echo "🔄 Обновление через 30 секунд... (Ctrl+C для выхода)"
    sleep 30
done
