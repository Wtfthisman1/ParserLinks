#!/bin/bash

echo "🧪 Тестирование функциональности парсера..."

echo ""
echo "=== Тест 1: Проверка статистики ==="
java -jar target/image-parser-1.0.0.jar stats

echo ""
echo "=== Тест 2: Проверка конфигурации ==="
java -jar target/image-parser-1.0.0.jar config

echo ""
echo "=== Тест 3: Тест с небольшим количеством ссылок (5) ==="
echo "Этот тест покажет, что парсер корректно обрабатывает 404 ошибки"
java -jar target/image-parser-1.0.0.jar fixed imgbb 5

echo ""
echo "=== Тест 4: Проверка статистики после теста ==="
java -jar target/image-parser-1.0.0.jar stats

echo ""
echo "✅ Тестирование завершено"
