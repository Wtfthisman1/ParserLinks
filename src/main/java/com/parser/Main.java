package com.parser;

import com.parser.config.ParserConfig;
import com.parser.model.LinkResult;
import com.parser.parser.ImageParser;
import com.parser.parser.NettyImageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Главный класс приложения
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        printBanner();
        
        // Обработка команды help
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            showHelp();
            return;
        }
        
        // Определяем тип парсера (netty или legacy)
        boolean useNetty = args.length > 0 && args[0].equals("--netty");
        String[] actualArgs = useNetty ? Arrays.copyOfRange(args, 1, args.length) : args;
        
        if (actualArgs.length == 0) {
            showHelp();
            return;
        }
        
        String command = actualArgs[0];
        
                // Выбираем парсер в зависимости от команды и флага
        if (useNetty || command.equals("netty-crawl") || command.equals("netty-smart") || command.equals("netty-fixed")) {
            // Используем высокопроизводительный Netty парсер
            try (NettyImageParser parser = new NettyImageParser()) {
                processNettyCommands(parser, actualArgs);
                if (!command.equals("stats") && !command.equals("cleanup")) {
                    parser.printFinalStats();
                }
            } catch (Exception e) {
                logger.error("❌ Ошибка Netty парсера: {}", e.getMessage());
                System.exit(1);
            }
        } else {
            // Используем legacy парсер
            try (ImageParser parser = new ImageParser()) {
                processLegacyCommands(parser, actualArgs);
                if (!command.equals("stats") && !command.equals("cleanup")) {
                    parser.printFinalStats();
                }
            } catch (Exception e) {
                logger.error("❌ Ошибка: {}", e.getMessage());
                System.exit(1);
            }
        }
    }
    
    /**
     * Обработка команд для Netty парсера
     */
    private static void processNettyCommands(NettyImageParser parser, String[] args) {
        String command = args[0];
        
        switch (command) {
            case "netty-crawl" -> {
                if (args.length < 2) {
                    logger.error("❌ Укажите хостинг: java -jar image-parser.jar --netty netty-crawl <hosting> [token_length]");
                    return;
                }
                String hosting = args[1];
                int tokenLength = args.length > 2 ? Integer.parseInt(args[2]) : -1;
                logger.info("🕷️ Запуск высокопроизводительного краулера для {} с размером токена {}", hosting, tokenLength > 0 ? tokenLength : "авто");
                parser.runCrawler(hosting, tokenLength);
            }
            case "netty-smart" -> {
                if (args.length < 2) {
                    logger.error("❌ Укажите хостинг: java -jar image-parser.jar --netty netty-smart <hosting> [count]");
                    return;
                }
                String hosting = args[1];
                int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
                logger.info("🧠 Высокопроизводительный умный парсинг {} ссылок для {}", count, hosting);
                parser.runSmartParsing(hosting, count);
            }
            case "netty-fixed" -> {
                if (args.length < 3) {
                    logger.error("❌ Укажите хостинг и количество: java -jar image-parser.jar --netty netty-fixed <hosting> <count>");
                    return;
                }
                String hosting = args[1];
                try {
                    int count = Integer.parseInt(args[2]);
                    if (count <= 0) {
                        logger.error("❌ Количество должно быть положительным числом");
                        return;
                    }
                    logger.info("🚀 Высокопроизводительный парсинг {} ссылок для {}", count, hosting);
                    parser.runFixedParsing(hosting, count, 100);
                } catch (NumberFormatException e) {
                    logger.error("❌ Некорректное количество: {}", args[2]);
                    return;
                }
            }
            case "continuous" -> {
                if (args.length < 2) {
                    logger.error("❌ Укажите хостинг: java -jar image-parser.jar --netty continuous <hosting>");
                    return;
                }
                String hosting = args[1];
                logger.info("🚀 Запуск высокопроизводительного непрерывного парсинга {}", hosting);
                parser.runContinuousParsing(hosting, 100);
            }
            case "fixed" -> {
                if (args.length < 3) {
                    logger.error("❌ Укажите хостинг и количество: java -jar image-parser.jar --netty fixed <hosting> <count>");
                    return;
                }
                String hosting = args[1];
                try {
                    int count = Integer.parseInt(args[2]);
                    if (count <= 0) {
                        logger.error("❌ Количество должно быть положительным числом");
                        return;
                    }
                    logger.info("🚀 Высокопроизводительный парсинг {} ссылок для {}", count, hosting);
                    parser.runFixedParsing(hosting, count, 100);
                } catch (NumberFormatException e) {
                    logger.error("❌ Некорректное количество: {}", args[2]);
                    return;
                }
            }
            case "custom" -> {
                if (args.length < 3) {
                    logger.error("❌ Укажите хостинг и URL: java -jar image-parser.jar --netty custom <hosting> <url1,url2,...>");
                    return;
                }
                String hosting = args[1];
                String urls = args[2];
                List<String> urlList = Arrays.asList(urls.split(","));
                logger.info("🚀 Высокопроизводительная обработка {} пользовательских ссылок для {}", urlList.size(), hosting);
                parser.processCustomUrls(urlList, hosting);
            }
            case "stats" -> {
                showStatistics(parser);
            }
            case "cleanup" -> {
                int days = args.length > 1 ? Integer.parseInt(args[1]) : 30;
                logger.info("🧹 Очистка записей старше {} дней...", days);
                parser.cleanupOldRecords(days);
                logger.info("✅ Очистка завершена");
            }
            case "config" -> {
                showConfig();
            }
            case "test-links" -> {
                if (args.length < 2) {
                    logger.error("❌ Укажите файл со ссылками: java -jar image-parser.jar --netty test-links <file>");
                    return;
                }
                String filePath = args[1];
                logger.info("🧪 Тестирование с реальными ссылками из файла: {}", filePath);
                parser.testWithRealLinks(filePath);
            }
            default -> {
                logger.error("❌ Неизвестная команда Netty: {}", command);
                showHelp();
            }
        }
    }
    
    /**
     * Обработка команд для Legacy парсера
     */
    private static void processLegacyCommands(ImageParser parser, String[] args) {
        String command = args[0];
        
        switch (command) {
            case "continuous" -> {
                if (args.length < 2) {
                    logger.error("❌ Укажите хостинг: java -jar image-parser.jar continuous <hosting>");
                    return;
                }
                String hosting = args[1];
                logger.info("🚀 Запуск непрерывного парсинга {}", hosting);
                parser.runContinuousParsing(hosting, 100);
            }
            case "fixed" -> {
                if (args.length < 3) {
                    logger.error("❌ Укажите хостинг и количество: java -jar image-parser.jar fixed <hosting> <count>");
                    return;
                }
                String hosting = args[1];
                try {
                    int count = Integer.parseInt(args[2]);
                    if (count <= 0) {
                        logger.error("❌ Количество должно быть положительным числом");
                        return;
                    }
                    logger.info("🚀 Запуск парсинга {} ссылок для {}", count, hosting);
                    parser.runFixedParsing(hosting, count, 100);
                } catch (NumberFormatException e) {
                    logger.error("❌ Некорректное количество: {}", args[2]);
                    return;
                }
            }
            case "custom" -> {
                if (args.length < 3) {
                    logger.error("❌ Укажите хостинг и URL: java -jar image-parser.jar custom <hosting> <url1,url2,...>");
                    return;
                }
                String hosting = args[1];
                String urls = args[2];
                List<String> urlList = Arrays.asList(urls.split(","));
                logger.info("🚀 Обработка {} пользовательских ссылок для {}", urlList.size(), hosting);
                parser.processCustomUrls(urlList, hosting);
            }
            case "stats" -> {
                showStatistics(parser);
            }
            case "cleanup" -> {
                int days = args.length > 1 ? Integer.parseInt(args[1]) : 30;
                logger.info("🧹 Очистка записей старше {} дней...", days);
                parser.cleanupOldRecords(days);
                logger.info("✅ Очистка завершена");
            }
            case "test" -> {
                logger.info("🧪 Тестирование конкретных ссылок...");
                testSpecificLinks();
            }
            case "crawl" -> {
                if (args.length < 2) {
                    logger.error("❌ Укажите хостинг: java -jar image-parser.jar crawl <hosting> [token_length]");
                    return;
                }
                String hosting = args[1];
                int tokenLength = args.length > 2 ? Integer.parseInt(args[2]) : -1;
                logger.info("🕷️ Запуск краулера для {} с размером токена {}", hosting, tokenLength > 0 ? tokenLength : "авто");
                parser.runCrawler(hosting, tokenLength);
            }
            case "smart" -> {
                if (args.length < 2) {
                    logger.error("❌ Укажите хостинг: java -jar image-parser.jar smart <hosting> [count]");
                    return;
                }
                String hosting = args[1];
                int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
                logger.info("🧠 Умный парсинг {} ссылок для {}", count, hosting);
                parser.runSmartParsing(hosting, count);
            }
            case "config" -> {
                showConfig();
            }
            default -> {
                logger.error("❌ Неизвестная команда: {}", command);
                showHelp();
            }
        }
    }
    
    private static void showStatistics(ImageParser parser) {
        showStatisticsCommon(parser);
    }
    
    private static void showStatistics(NettyImageParser parser) {
        showStatisticsCommon(parser);
        // Дополнительно показываем статистику Netty
        parser.printNettyStats();
    }
    
    private static void showStatisticsCommon(Object parser) {
        logger.info("📊 Статистика по хостам:");
        
        List<LinkResult> recentResults;
        if (parser instanceof ImageParser) {
            recentResults = ((ImageParser) parser).getRecentResults(20);
        } else if (parser instanceof NettyImageParser) {
            recentResults = ((NettyImageParser) parser).getRecentResults(20);
        } else {
            logger.error("Неизвестный тип парсера");
            return;
        }
        
        Map<String, Map<String, Integer>> stats = recentResults.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    LinkResult::getHosting,
                    java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(),
                        list -> {
                            Map<String, Integer> hostingStats = new java.util.HashMap<>();
                            hostingStats.put("total", list.size());
                            hostingStats.put("downloaded", (int) list.stream()
                                    .filter(r -> "downloaded".equals(r.getStatus())).count());
                            hostingStats.put("empty", (int) list.stream()
                                    .filter(r -> "empty".equals(r.getStatus())).count());
                            hostingStats.put("errors", (int) list.stream()
                                    .filter(r -> "error".equals(r.getStatus())).count());
                            return hostingStats;
                        }
                    )
                ));
        
        if (!stats.isEmpty()) {
            stats.forEach((hosting, data) -> {
                logger.info("  {}:", hosting);
                logger.info("    Всего: {}", data.get("total"));
                logger.info("    Скачано: {}", data.get("downloaded"));
                logger.info("    Пустых: {}", data.get("empty"));
                logger.info("    Ошибок: {}", data.get("errors"));
            });
        } else {
            logger.info("  Нет данных для отображения");
        }
        
        // Показываем последние результаты
        logger.info("\n🕒 Последние 20 результатов:");
        if (!recentResults.isEmpty()) {
            recentResults.forEach(result -> {
                String statusEmoji = switch (result.getStatus()) {
                    case "downloaded" -> "✅";
                    case "empty" -> "❌";
                    case "skipped" -> "⏭️";
                    case "error" -> "⚠️";
                    default -> "❓";
                };
                
                logger.info("  {} {} ({})", statusEmoji, result.getUrl(), result.getStatus());
                if (result.getFilePath() != null) {
                    logger.info("    📁 {}", result.getFilePath());
                }
            });
        } else {
            logger.info("  Нет результатов для отображения");
        }
    }
    
    private static void showHelp() {
        logger.info("""
            📖 Использование:
            
            🚀 ВЫСОКОПРОИЗВОДИТЕЛЬНЫЙ NETTY ПАРСЕР (рекомендуется):
            • --netty netty-crawl <hosting> [token_length]     - Максимальная производительность краулер
            • --netty netty-smart <hosting> [count]            - Умный парсинг с Netty
            • --netty netty-fixed <hosting> <count>            - Фиксированное количество с Netty
            • --netty continuous <hosting>                     - Непрерывный парсинг с Netty
            • --netty fixed <hosting> <count>                  - Фиксированное количество с Netty
            • --netty custom <hosting> <url1,url2,...>         - Пользовательские ссылки с Netty
            
            📊 LEGACY ПАРСЕР (OkHttp):
            • continuous <hosting>                    - Непрерывный парсинг
            • fixed <hosting> <count>                 - Парсинг фиксированного количества ссылок
            • custom <hosting> <url1,url2,...>        - Обработка пользовательских ссылок
            • crawl <hosting> [token_length]          - Краулер с настраиваемым размером токена
            • smart <hosting> [count]                 - Умный парсинг с приоритизацией
            • test                                    - Тестирование конкретных ссылок
            
            🛠️ Утилиты (работают с обоими парсерами):
            • stats                                   - Показать статистику
            • cleanup [days]                          - Очистка старых записей
            • config                                  - Показать конфигурацию
            
            🚀 Примеры высокопроизводительного парсинга:
            • java -jar image-parser.jar --netty netty-crawl postimages 8
            • java -jar image-parser.jar --netty netty-smart imgbb 5000
            • java -jar image-parser.jar --netty netty-fixed postimages 1000
            
            📊 Примеры legacy парсинга:
            • java -jar image-parser.jar crawl postimages 8
            • java -jar image-parser.jar smart imgbb 5000
            • java -jar image-parser.jar config
            
            ⚡ ОТЛИЧИЯ NETTY ПАРСЕРА:
            • Стриминговый парсинг (читает только первые 8KB)
            • Максимальный параллелизм (1000+ одновременных запросов)
            • Пул соединений HTTP/2
            • Отказ от HEAD запросов (сразу GET с прерыванием)
            • Rate limiting для стабильной нагрузки
            
            Поддерживаемые хостинги: imgbb, postimages
            """);
    }
    
    private static void showConfig() {
        logger.info("⚙️ Конфигурация парсера:");
        logger.info("");
        logger.info("Производительность:");
        logger.info("• Максимум одновременных запросов: {}", ParserConfig.MAX_CONCURRENT_REQUESTS);
        logger.info("• Таймаут запроса: {} сек", ParserConfig.REQUEST_TIMEOUT_SECONDS);
        logger.info("• Попыток повтора: {}", ParserConfig.RETRY_ATTEMPTS);
        logger.info("• Задержка между попытками: {} мс", ParserConfig.RETRY_DELAY_MS);
        logger.info("");
        logger.info("Человеческое поведение:");
        logger.info("• Минимальная задержка между запросами: {} мс", ParserConfig.MIN_DELAY_BETWEEN_REQUESTS_MS);
        logger.info("• Максимальная задержка между запросами: {} мс", ParserConfig.MAX_DELAY_BETWEEN_REQUESTS_MS);
        logger.info("• User-Agent'ов: {}", ParserConfig.USER_AGENTS.length);
        logger.info("");
        logger.info("Фильтрация:");
        logger.info("• Минимальный возраст изображения: {} дней", ParserConfig.MIN_IMAGE_AGE_DAYS);
        logger.info("• Максимальный размер файла: {} МБ", ParserConfig.MAX_IMAGE_SIZE_MB);
        logger.info("");
        logger.info("Сохранение:");
        logger.info("• Папка загрузок: {}", ParserConfig.DOWNLOAD_DIR);
        logger.info("• База данных: {}", ParserConfig.DB_PATH);
    }
    
    private static void printBanner() {
        String banner = """
            
            ╔══════════════════════════════════════════════════════════════╗
            ║                    Парсер фотохостингов                      ║
            ║                                                              ║
            ║  Поддерживаемые хостинги:                                    ║
            ║  • ibb.co (ImgBB)                                            ║
            ║  • postimg.cc (Postimages)                                   ║
            ╚══════════════════════════════════════════════════════════════╝
            """;
        System.out.println(banner);
    }
    
    private static void testSpecificLinks() {
        String[] urls = {
            "https://ibb.co/abc123",
            "https://ibb.co/def456",
            "https://postimg.cc/ghi789",
            "https://postimg.cc/jkl012",
            "https://postimg.cc/mno345"
        };
        
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        
        try {
            
            for (String url : urls) {
                logger.info("\n=== Проверка: {} ===", url);
                
                try {
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(url)
                            .head()
                            .build();
                    
                    try (okhttp3.Response response = client.newCall(request).execute()) {
                        logger.info("Код ответа: {}", response.code());
                        logger.info("Content-Type: {}", response.header("content-type"));
                        logger.info("Content-Length: {}", response.header("content-length"));
                        logger.info("Last-Modified: {}", response.header("last-modified"));
                        logger.info("Date: {}", response.header("date"));
                        
                        if (response.isSuccessful()) {
                            String contentType = response.header("content-type");
                            if (contentType != null && contentType.startsWith("image/")) {
                                logger.info("✅ Изображение найдено!");
                            } else {
                                logger.info("❌ Не изображение или нет Content-Type");
                            }
                        } else {
                            logger.info("❌ HTTP ошибка: {}", response.code());
                        }
                    }
                } catch (Exception e) {
                    logger.info("❌ Ошибка: {}", e.getMessage());
                }
            }
            
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            
        } catch (Exception e) {
            logger.error("Ошибка создания HTTP клиента: {}", e.getMessage());
        }
    }
}
