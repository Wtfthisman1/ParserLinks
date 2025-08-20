#!/bin/bash

# Скрипт для запуска краулера на 7 часов для обоих хостингов
# Автор: AI Assistant
# Дата: $(date)

echo "🚀 Запуск краулера на 7 часов для imgbb и postimages"
echo "⏰ Время начала: $(date)"
echo "⏰ Время окончания: $(date -d '+7 hours')"
echo ""

# Создаем папку для логов если её нет
mkdir -p logs

# Функция для запуска краулера для одного хостинга
run_hosting_crawler() {
    local hosting=$1
    local log_file="logs/crawler_${hosting}_$(date +%Y%m%d_%H%M%S).log"
    
    echo "🕷️ Запуск краулера для $hosting..."
    echo "📝 Лог файл: $log_file"
    
    # Запускаем краулер в фоне
    nohup java -jar target/image-parser-1.0.0.jar smart "$hosting" 100000 > "$log_file" 2>&1 &
    local pid=$!
    echo "✅ Краулер для $hosting запущен с PID: $pid"
    echo "$pid" > "logs/${hosting}_pid.txt"
    
    return $pid
}

# Функция для остановки краулера
stop_hosting_crawler() {
    local hosting=$1
    local pid_file="logs/${hosting}_pid.txt"
    
    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        echo "🛑 Останавливаю краулер для $hosting (PID: $pid)..."
        kill -TERM "$pid" 2>/dev/null
        sleep 5
        kill -KILL "$pid" 2>/dev/null
        rm -f "$pid_file"
        echo "✅ Краулер для $hosting остановлен"
    else
        echo "⚠️ PID файл для $hosting не найден"
    fi
}

# Функция для мониторинга
monitor_crawlers() {
    echo "📊 Мониторинг краулеров..."
    while true; do
        echo "⏰ $(date) - Проверка статуса..."
        
        for hosting in imgbb postimages; do
            local pid_file="logs/${hosting}_pid.txt"
            if [ -f "$pid_file" ]; then
                local pid=$(cat "$pid_file")
                if ps -p "$pid" > /dev/null 2>&1; then
                    echo "✅ $hosting: работает (PID: $pid)"
                else
                    echo "❌ $hosting: остановлен (PID: $pid)"
                    rm -f "$pid_file"
                fi
            else
                echo "⚠️ $hosting: PID файл не найден"
            fi
        done
        
        echo ""
        sleep 300  # Проверяем каждые 5 минут
    done
}

# Обработка сигналов для корректного завершения
cleanup() {
    echo ""
    echo "🛑 Получен сигнал завершения, останавливаю краулеры..."
    stop_hosting_crawler "imgbb"
    stop_hosting_crawler "postimages"
    echo "✅ Все краулеры остановлены"
    exit 0
}

# Устанавливаем обработчики сигналов
trap cleanup SIGINT SIGTERM

# Запускаем краулеры
echo "🕷️ Запуск краулеров..."
run_hosting_crawler "imgbb"
run_hosting_crawler "postimages"

echo ""
echo "⏰ Краулеры запущены. Ожидание 7 часов..."
echo "📊 Для мониторинга запустите: tail -f logs/crawler_*.log"
echo "🛑 Для остановки нажмите Ctrl+C"

# Ждем 7 часов
sleep 25200  # 7 часов = 7 * 60 * 60 секунд

echo ""
echo "⏰ 7 часов истекли, останавливаю краулеры..."

# Останавливаем краулеры
stop_hosting_crawler "imgbb"
stop_hosting_crawler "postimages"

echo ""
echo "✅ Работа завершена!"
echo "📊 Статистика:"
echo "   - Время начала: $(date -d '-7 hours')"
echo "   - Время окончания: $(date)"
echo "   - Логи сохранены в папке logs/"

# Показываем финальную статистику
echo ""
echo "📈 Финальная статистика:"
java -jar target/image-parser-1.0.0.jar stats
