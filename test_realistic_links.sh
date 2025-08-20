#!/bin/bash

echo "🧪 Тестирование с реалистичными ссылками..."

# Создаем папку для тестовых логов
mkdir -p test_logs

# Тестируем с небольшим количеством ссылок
echo "🕷️ Тестируем imgbb с 100 ссылками..."
java -jar target/image-parser-1.0.0.jar smart imgbb 100 > test_logs/imgbb_test.log 2>&1 &

echo "🕷️ Тестируем postimages с 100 ссылками..."
java -jar target/image-parser-1.0.0.jar smart postimages 100 > test_logs/postimages_test.log 2>&1 &

# Ждем завершения
echo "⏳ Ожидаем завершения тестов..."
wait

echo "📊 Результаты тестирования:"
echo ""
echo "=== ImgBB ==="
tail -10 test_logs/imgbb_test.log

echo ""
echo "=== PostImages ==="
tail -10 test_logs/postimages_test.log

echo ""
echo "📈 Статистика базы данных:"
java -jar target/image-parser-1.0.0.jar stats

echo ""
echo "✅ Тестирование завершено!"
