package com.parser.parser;

import com.parser.config.ParserConfig;
import com.parser.database.DatabaseManager;
import com.parser.generator.LinkGenerator;
import com.parser.model.LinkResult;
import com.parser.processor.NettyImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Высокопроизводительный парсер изображений на основе Netty
 * Реализует максимальный параллелизм и стриминговый парсинг
 */
public class NettyImageParser implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NettyImageParser.class);
    
    // Конфигурация производительности
    private static final int MAX_CONCURRENT_REQUESTS = 1000; // Увеличиваем в 10 раз
    private static final int BATCH_SIZE = 100;
    private static final int RATE_LIMIT_PER_SECOND = 500; // Ограничиваем RPS
    
    private final DatabaseManager databaseManager;
    private final LinkGenerator linkGenerator;
    private final NettyImageProcessor nettyProcessor;
    
    // Пул потоков для обработки результатов
    private final ExecutorService resultProcessor;
    
    // Rate limiter для контроля нагрузки
    private final Semaphore rateLimiter;
    
    // Статистика
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong emptyLinks = new AtomicLong(0);
    private final AtomicLong downloadedImages = new AtomicLong(0);
    private final AtomicLong skippedImages = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final LocalDateTime startTime = LocalDateTime.now();
    
    public NettyImageParser() throws SQLException {
        this.databaseManager = new DatabaseManager();
        this.linkGenerator = new LinkGenerator();
        this.nettyProcessor = new NettyImageProcessor();
        this.resultProcessor = Executors.newFixedThreadPool(16); // 16 потоков для обработки результатов
        this.rateLimiter = new Semaphore(RATE_LIMIT_PER_SECOND);
        
        logger.info("🚀 NettyImageParser инициализирован с максимальным параллелизмом");
    }
    
    /**
     * Обработка одной ссылки с проверкой в базе данных
     */
    public CompletableFuture<LinkResult> processSingleUrlAsync(String url, String hosting) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Проверяем, не обрабатывали ли мы уже эту ссылку
                if (databaseManager.isUrlProcessed(url)) {
                    logger.debug("⏭️ URL уже обработан: {}", url);
                    return LinkResult.builder()
                            .url(url)
                            .hosting(hosting)
                            .status("skipped")
                            .build();
                }
                
                // Применяем rate limiting
                rateLimiter.acquire();
                
                // Обрабатываем изображение через Netty
                return nettyProcessor.processImageUrlAsync(url, hosting).get();
                
            } catch (Exception e) {
                logger.error("💥 Ошибка обработки {}: {}", url, e.getMessage());
                return LinkResult.builder()
                        .url(url)
                        .hosting(hosting)
                        .status("error")
                        .errorMessage(e.getMessage())
                        .build();
            }
        }, resultProcessor).thenApply(result -> {
            // Сохраняем результат в базу данных
            try {
                databaseManager.saveResult(result);
                updateStats(result.getStatus());
            } catch (Exception e) {
                logger.error("Ошибка сохранения результата: {}", e.getMessage());
            }
            return result;
        });
    }
    
    /**
     * Обработка батча ссылок с максимальным параллелизмом
     */
    public List<LinkResult> processUrlsBatch(List<String> urls, String hosting) {
        logger.info("🎯 Обрабатываю батч из {} ссылок для {}", urls.size(), hosting);
        
        // Создаем CompletableFuture для каждой ссылки
        List<CompletableFuture<LinkResult>> futures = urls.stream()
                .map(url -> processSingleUrlAsync(url, hosting))
                .toList();
        
        // Ждем завершения всех задач
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );
        
        try {
            allFutures.get(10, TimeUnit.SECONDS); // Уменьшено с 30 до 10 секунд
            
            // Собираем результаты
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(2, TimeUnit.SECONDS); // Таймаут 2 секунды на каждую задачу
                        } catch (Exception e) {
                            logger.error("Ошибка в задаче: {}", e.getMessage());
                            return null;
                        }
                    })
                    .filter(result -> result != null)
                    .toList();
                    
        } catch (Exception e) {
            logger.error("Ошибка обработки батча: {}", e.getMessage());
            // Возвращаем частичные результаты вместо пустого списка
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.getNow(null);
                        } catch (Exception ex) {
                            return null;
                        }
                    })
                    .filter(result -> result != null)
                    .toList();
        }
    }
    
    /**
     * Непрерывный парсинг с максимальной производительностью
     */
    public void runContinuousParsing(String hostingName, int batchSize) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("🚀 Запуск непрерывного парсинга для {} с максимальной производительностью", hostingName);
        
        try {
            int batchNum = 0;
            while (!Thread.currentThread().isInterrupted()) {
                // Генерируем новый батч ссылок
                List<String> urls = linkGenerator.generateHostingSpecificLinks(hostingName, batchSize);
                
                // Обрабатываем батч
                List<LinkResult> results = processUrlsBatch(urls, hostingName);
                
                // Выводим статистику каждые 10 батчей
                batchNum++;
                if (batchNum % 10 == 0) {
                    printStats();
                    printNettyStats();
                }
                
                // Минимальная пауза между батчами
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            logger.info("Получен сигнал остановки");
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Парсинг фиксированного количества ссылок
     */
    public void runFixedParsing(String hostingName, int totalUrls, int batchSize) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("🚀 Запуск парсинга {} ссылок для {} с максимальной производительностью", totalUrls, hostingName);
        
        try {
            int processed = 0;
            
            while (processed < totalUrls && !Thread.currentThread().isInterrupted()) {
                // Определяем размер текущего батча
                int currentBatchSize = Math.min(batchSize, totalUrls - processed);
                
                // Генерируем ссылки
                List<String> urls = linkGenerator.generateHostingSpecificLinks(hostingName, currentBatchSize);
                
                // Обрабатываем батч
                List<LinkResult> results = processUrlsBatch(urls, hostingName);
                
                // Обновляем прогресс
                processed += results.size();
                
                // Показываем прогресс
                if (processed % 1000 == 0 || processed >= totalUrls) {
                    logger.info("📊 Прогресс: {}/{} ({}%)", 
                        processed, totalUrls, Math.round((double) processed / totalUrls * 100));
                }
                
                // Минимальная пауза между батчами
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            logger.info("Получен сигнал остановки");
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Обработка пользовательских ссылок
     */
    public void processCustomUrls(List<String> urls, String hostingName) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("🚀 Обработка {} пользовательских ссылок для {} с максимальной производительностью", 
                   urls.size(), hostingName);
        
        try {
            // Обрабатываем ссылки батчами
            for (int i = 0; i < urls.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, urls.size());
                List<String> batchUrls = urls.subList(i, endIndex);
                
                List<LinkResult> results = processUrlsBatch(batchUrls, hostingName);
                
                // Показываем прогресс
                if ((i + BATCH_SIZE) % 1000 == 0 || i + BATCH_SIZE >= urls.size()) {
                    logger.info("📊 Прогресс: {}/{} ({}%)", 
                        Math.min(i + BATCH_SIZE, urls.size()), urls.size(),
                        Math.round((double)(i + BATCH_SIZE) / urls.size() * 100));
                }
                
                // Минимальная пауза между батчами
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            logger.info("Получен сигнал остановки");
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Запуск краулера с максимальной производительностью
     */
    public void runCrawler(String hostingName, int tokenLength) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("🕷️ Запуск высокопроизводительного краулера для {} с размером токена {}", hostingName, 
                   tokenLength > 0 ? tokenLength : "авто");
        
        try {
            // Если указан размер токена, обновляем конфигурацию
            if (tokenLength > 0) {
                ParserConfig.HostingConfig config = ParserConfig.HOSTINGS.get(hostingName);
                ParserConfig.HOSTINGS.put(hostingName, new ParserConfig.HostingConfig(
                    config.getName(), config.getBaseUrl(), tokenLength, 
                    config.getTokenChars(), config.getCheckPath(), config.getDownloadPath()
                ));
                logger.info("📝 Обновлен размер токена для {}: {}", hostingName, tokenLength);
            }
            
            // Запускаем непрерывный парсинг
            runContinuousParsing(hostingName, BATCH_SIZE);
            
        } catch (Exception e) {
            logger.error("💥 Ошибка краулера: {}", e.getMessage());
        }
    }
    
    /**
     * Умный парсинг с максимальной производительностью
     */
    public void runSmartParsing(String hostingName, int count) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("🧠 Запуск умного парсинга {} ссылок для {} с максимальной производительностью", 
                   count, hostingName);
        
        try {
            // Генерируем умные ссылки
            List<String> urls = linkGenerator.generateHostingSpecificLinks(hostingName, count);
            logger.info("🎯 Сгенерировано {} умных ссылок", urls.size());
            
            // Обрабатываем ссылки батчами
            for (int i = 0; i < urls.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, urls.size());
                List<String> batchUrls = urls.subList(i, endIndex);
                
                List<LinkResult> results = processUrlsBatch(batchUrls, hostingName);
                
                // Показываем прогресс
                if ((i + BATCH_SIZE) % 1000 == 0 || i + BATCH_SIZE >= urls.size()) {
                    logger.info("📊 Прогресс: {}/{} ({}%)", 
                        Math.min(i + BATCH_SIZE, urls.size()), urls.size(),
                        Math.round((double)(i + BATCH_SIZE) / urls.size() * 100));
                }
                
                // Минимальная пауза между батчами
                Thread.sleep(50);
            }
            
        } catch (Exception e) {
            logger.error("💥 Ошибка умного парсинга: {}", e.getMessage());
        }
    }
    
    private void updateStats(String status) {
        totalProcessed.incrementAndGet();
        
        switch (status) {
            case "empty" -> emptyLinks.incrementAndGet();
            case "downloaded" -> downloadedImages.incrementAndGet();
            case "skipped" -> skippedImages.incrementAndGet();
            case "error" -> errors.incrementAndGet();
        }
    }
    
    public void printStats() {
        long total = totalProcessed.get();
        long downloaded = downloadedImages.get();
        long empty = emptyLinks.get();
        long error = errors.get();
        
        long elapsedSeconds = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();
        double rate = elapsedSeconds > 0 ? (double) total / elapsedSeconds : 0;
        
        logger.info("=== Статистика Netty парсера ===");
        logger.info("Обработано: {}", total);
        logger.info("Скачано: {}", downloaded);
        logger.info("Пустых: {}", empty);
        logger.info("Ошибок: {}", error);
        logger.info("Скорость: {:.2f} ссылок/сек", rate);
        logger.info("Время: {} сек", elapsedSeconds);
    }
    
    public void printNettyStats() {
        Map<String, Object> nettyStats = nettyProcessor.getPerformanceStats();
        logger.info("=== Статистика Netty ===");
        logger.info("Всего запросов: {}", nettyStats.get("totalRequests"));
        logger.info("Успешных: {}", nettyStats.get("successfulRequests"));
        logger.info("Ошибок: {}", nettyStats.get("failedRequests"));
        logger.info("Процент успеха: {}", nettyStats.get("successRate"));
        logger.info("Прочитано байт: {}", nettyStats.get("bytesRead"));
        logger.info("Ранних прерываний: {}", nettyStats.get("earlyTerminations"));
        logger.info("Пуллов соединений: {}", nettyStats.get("connectionPools"));
    }
    
    public void printFinalStats() {
        logger.info("=== Финальная статистика Netty парсера ===");
        printStats();
        printNettyStats();
        
        // Получаем статистику из БД
        Map<String, Map<String, Integer>> dbStats = databaseManager.getStatistics();
        if (!dbStats.isEmpty()) {
            logger.info("=== Статистика по хостам ===");
            dbStats.forEach((hosting, stats) -> {
                logger.info("{}: {} всего, {} скачано", 
                    hosting, stats.get("total"), stats.get("downloaded"));
            });
        }
    }
    
    public List<LinkResult> getRecentResults(int limit) {
        return databaseManager.getRecentResults(limit);
    }
    
    public void cleanupOldRecords(int days) {
        databaseManager.cleanupOldRecords(days);
    }
    
    /**
     * Тестирование с реальными ссылками из файла
     */
    public void testWithRealLinks(String filePath) {
        try {
            List<String> urls = java.nio.file.Files.readAllLines(java.nio.file.Path.of(filePath));
            logger.info("📖 Загружено {} ссылок из файла: {}", urls.size(), filePath);
            
            // Обрабатываем все ссылки параллельно
            List<CompletableFuture<LinkResult>> futures = urls.stream()
                .map(url -> processSingleUrlAsync(url.trim(), extractHosting(url)))
                .collect(java.util.stream.Collectors.toList());
            
            // Ждем завершения всех операций
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            logger.info("✅ Тестирование завершено!");
            printFinalStats();
            
        } catch (Exception e) {
            logger.error("💥 Ошибка тестирования с реальными ссылками: {}", e.getMessage());
        }
    }
    
    /**
     * Извлекает хостинг из URL
     */
    private String extractHosting(String url) {
        if (url.contains("ibb.co")) {
            return "imgbb";
        } else if (url.contains("postimg.cc")) {
            return "postimages";
        } else {
            return "unknown";
        }
    }
    
    @Override
    public void close() throws Exception {
        logger.info("🔄 Закрытие NettyImageParser...");
        
        if (resultProcessor != null) {
            resultProcessor.shutdown();
            if (!resultProcessor.awaitTermination(30, TimeUnit.SECONDS)) {
                resultProcessor.shutdownNow();
            }
        }
        
        if (nettyProcessor != null) {
            nettyProcessor.close();
        }
        
        if (databaseManager != null) {
            databaseManager.close();
        }
        
        logger.info("✅ NettyImageParser закрыт");
    }
}
