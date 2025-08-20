#!/bin/bash

echo "=== БЫСТРЫЙ МОНИТОРИНГ КРАУЛЕРА ==="
echo "⏰ Время: $(date)"
echo ""

# Проверяем процессы
echo "📊 ПРОЦЕССЫ:"
ps aux | grep -E "(java|image-parser)" | grep -v grep | awk '{print "✅ " $11 " (PID: " $2 ")"}'

echo ""
echo "📈 СТАТИСТИКА БАЗЫ ДАННЫХ:"
java -jar target/image-parser-1.0.0.jar stats 2>/dev/null | head -10

echo ""
echo "📝 ПОСЛЕДНИЕ ЛОГИ:"
echo "--- IMGBB ---"
tail -3 logs/crawler_imgbb_*.log 2>/dev/null | tail -3
echo ""
echo "--- POSTIMAGES ---"
tail -3 logs/crawler_postimages_*.log 2>/dev/null | tail -3

echo ""
echo "🔄 Для обновления запустите скрипт снова"
