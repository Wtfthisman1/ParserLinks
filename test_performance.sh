#!/bin/bash

echo "🚀 Тестирование производительности Netty vs Legacy парсера"
echo "=================================================="

# Создаем директорию для тестов если не существует
mkdir -p downloads

# Функция для измерения времени выполнения
measure_time() {
    local start_time=$(date +%s.%N)
    "$@"
    local end_time=$(date +%s.%N)
    local duration=$(echo "$end_time - $start_time" | bc -l)
    echo "$duration"
}

# Функция для очистки базы данных перед тестом
clean_db() {
    echo "🧹 Очистка базы данных..."
    rm -f parser_data.db
}

# Тест 1: Legacy парсер (100 ссылок)
echo ""
echo "📊 ТЕСТ 1: Legacy парсер (100 ссылок)"
echo "----------------------------------------"
clean_db
echo "⏱️ Запуск legacy парсера..."
legacy_time=$(measure_time java -jar target/image-parser-1.0.0.jar fixed postimages 100)
echo "✅ Legacy парсер завершен за ${legacy_time} секунд"

# Тест 2: Netty парсер (100 ссылок)
echo ""
echo "📊 ТЕСТ 2: Netty парсер (100 ссылок)"
echo "----------------------------------------"
clean_db
echo "⏱️ Запуск Netty парсера..."
netty_time=$(measure_time java -jar target/image-parser-1.0.0.jar --netty netty-fixed postimages 100)
echo "✅ Netty парсер завершен за ${netty_time} секунд"

# Вычисляем ускорение
speedup=$(echo "scale=2; $legacy_time / $netty_time" | bc -l)
echo ""
echo "📈 РЕЗУЛЬТАТЫ ПРОИЗВОДИТЕЛЬНОСТИ:"
echo "=================================="
echo "Legacy парсер: ${legacy_time} секунд"
echo "Netty парсер:  ${netty_time} секунд"
echo "Ускорение:     ${speedup}x"

# Тест 3: Большая нагрузка (1000 ссылок)
echo ""
echo "📊 ТЕСТ 3: Большая нагрузка (1000 ссылок)"
echo "--------------------------------------------"
clean_db
echo "⏱️ Запуск Netty парсера на 1000 ссылок..."
netty_1000_time=$(measure_time java -jar target/image-parser-1.0.0.jar --netty netty-fixed postimages 1000)
echo "✅ Netty парсер (1000 ссылок) завершен за ${netty_1000_time} секунд"

# Вычисляем RPS
legacy_rps=$(echo "scale=2; 100 / $legacy_time" | bc -l)
netty_rps=$(echo "scale=2; 100 / $netty_time" | bc -l)
netty_1000_rps=$(echo "scale=2; 1000 / $netty_1000_time" | bc -l)

echo ""
echo "📊 ДЕТАЛЬНАЯ СТАТИСТИКА:"
echo "========================"
echo "Legacy парсер (100 ссылок): ${legacy_rps} RPS"
echo "Netty парсер (100 ссылок):  ${netty_rps} RPS"
echo "Netty парсер (1000 ссылок): ${netty_1000_rps} RPS"
echo ""
echo "🚀 Netty парсер показывает ускорение в ${speedup} раз!"
echo ""
echo "💡 Рекомендации:"
echo "• Используйте --netty для максимальной производительности"
echo "• Netty парсер может обрабатывать 1000+ ссылок в минуту"
echo "• Стриминговый парсинг экономит трафик и время"
