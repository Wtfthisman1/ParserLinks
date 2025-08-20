package com.parser.parser;

import com.parser.config.ParserConfig;
import com.parser.database.DatabaseManager;
import com.parser.generator.LinkGenerator;
import com.parser.model.LinkResult;
import com.parser.processor.ImageProcessor;
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
 * Основной класс парсера изображений
 */
public class ImageParser implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ImageParser.class);
    
    private final DatabaseManager databaseManager;
    private final LinkGenerator linkGenerator;
    private final ExecutorService executorService;
    private final Semaphore semaphore;
    
    // Статистика
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong emptyLinks = new AtomicLong(0);
    private final AtomicLong downloadedImages = new AtomicLong(0);
    private final AtomicLong skippedImages = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final LocalDateTime startTime = LocalDateTime.now();
    
    public ImageParser() throws SQLException {
        this.databaseManager = new DatabaseManager();
        this.linkGenerator = new LinkGenerator();
        this.executorService = Executors.newFixedThreadPool(ParserConfig.MAX_CONCURRENT_REQUESTS);
        this.semaphore = new Semaphore(ParserConfig.MAX_CONCURRENT_REQUESTS);
    }
    
    /**
     * Обработка одной ссылки
     */
    public LinkResult processSingleUrl(String url, String hosting) {
        try {
            // Проверяем, не обрабатывали ли мы уже эту ссылку
            if (databaseManager.isUrlProcessed(url)) {
                logger.info("⏭️ URL уже обработан: {}", url);
                return LinkResult.builder()
                        .url(url)
                        .hosting(hosting)
                        .status("skipped")
                        .build();
            }
            
            logger.info("🎯 [{}] Обрабатываю ссылку #{}", hosting, totalProcessed.get() + 1);
            
            // Обрабатываем изображение
            try (ImageProcessor processor = new ImageProcessor()) {
                LinkResult result = processor.processImageUrl(url, hosting);
                
                // Сохраняем в базу данных
                databaseManager.saveResult(result);
                
                // Обновляем статистику
                updateStats(result.getStatus());
                
                // Показываем итоговую статистику каждые 100 ссылок
                long total = totalProcessed.get();
                if (total % 100 == 0 && total > 0) {
                    logger.info("📊 Промежуточная статистика: обработано {}, скачано {}, пустых {}, ошибок {}", 
                        total, downloadedImages.get(), emptyLinks.get(), errors.get());
                }
                
                return result;
            }
            
        } catch (Exception e) {
            logger.error("💥 Ошибка обработки {}: {}", url, e.getMessage());
            LinkResult result = LinkResult.builder()
                    .url(url)
                    .hosting(hosting)
                    .status("error")
                    .errorMessage(e.getMessage())
                    .build();
            
            databaseManager.saveResult(result);
            updateStats("error");
            return result;
        }
    }
    
    /**
     * Обработка батча ссылок
     */
    public List<LinkResult> processUrlsBatch(List<String> urls, String hosting) {
        List<Future<LinkResult>> futures = urls.stream()
                .map(url -> executorService.submit(() -> processSingleUrl(url, hosting)))
                .toList();
        
        return futures.stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        logger.error("Ошибка в задаче: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(result -> result != null)
                .toList();
    }
    
    /**
     * Непрерывный парсинг с генерацией ссылок
     */
    public void runContinuousParsing(String hostingName, int batchSize) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("Запуск непрерывного парсинга для {}", hostingName);
        
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
                }
                
                // Небольшая пауза между батчами
                Thread.sleep(100);
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
        
        logger.info("Запуск парсинга {} ссылок для {}", totalUrls, hostingName);
        
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
                
                // Небольшая пауза между батчами
                Thread.sleep(100);
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
        
        logger.info("Обработка {} пользовательских ссылок для {}", urls.size(), hostingName);
        
        try {
            // Обрабатываем ссылки батчами
            int batchSize = ParserConfig.MAX_CONCURRENT_REQUESTS;
            for (int i = 0; i < urls.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, urls.size());
                List<String> batchUrls = urls.subList(i, endIndex);
                
                List<LinkResult> results = processUrlsBatch(batchUrls, hostingName);
                
                // Небольшая пауза между батчами
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            logger.info("Получен сигнал остановки");
            Thread.currentThread().interrupt();
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
        
        logger.info("=== Статистика ===");
        logger.info("Обработано: {}", total);
        logger.info("Скачано: {}", downloaded);
        logger.info("Пустых: {}", empty);
        logger.info("Ошибок: {}", error);
        logger.info("Скорость: {:.2f} ссылок/сек", rate);
        logger.info("Время: {} сек", elapsedSeconds);
    }
    
    public void printFinalStats() {
        logger.info("=== Финальная статистика ===");
        printStats();
        
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
     * Запуск краулера с настраиваемым размером токена
     */
    public void runCrawler(String hostingName, int tokenLength) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("🕷️ Запуск краулера для {} с размером токена {}", hostingName, 
                   tokenLength > 0 ? tokenLength : "авто");
        
        try {
            // Если указан размер токена, обновляем конфигурацию
            if (tokenLength > 0) {
                ParserConfig.HostingConfig config = ParserConfig.HOSTINGS.get(hostingName);
                // Создаем новую конфигурацию с обновленным размером токена
                ParserConfig.HOSTINGS.put(hostingName, new ParserConfig.HostingConfig(
                    config.getName(), config.getBaseUrl(), tokenLength, 
                    config.getTokenChars(), config.getCheckPath(), config.getDownloadPath()
                ));
                logger.info("📝 Обновлен размер токена для {}: {}", hostingName, tokenLength);
            }
            
            // Запускаем непрерывный парсинг
            runContinuousParsing(hostingName, 100);
            
        } catch (Exception e) {
            logger.error("💥 Ошибка краулера: {}", e.getMessage());
        }
    }
    
    /**
     * Умный парсинг с приоритизацией
     */
    public void runSmartParsing(String hostingName, int count) {
        if (!ParserConfig.HOSTINGS.containsKey(hostingName)) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        logger.info("🧠 Запуск умного парсинга {} ссылок для {}", count, hostingName);
        
        try {
            // Генерируем умные ссылки
            List<String> urls = linkGenerator.generateHostingSpecificLinks(hostingName, count);
            logger.info("🎯 Сгенерировано {} умных ссылок", urls.size());
            
            // Обрабатываем ссылки батчами
            int batchSize = ParserConfig.MAX_CONCURRENT_REQUESTS;
            for (int i = 0; i < urls.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, urls.size());
                List<String> batchUrls = urls.subList(i, endIndex);
                
                List<LinkResult> results = processUrlsBatch(batchUrls, hostingName);
                
                // Показываем прогресс
                if ((i + batchSize) % 100 == 0 || i + batchSize >= urls.size()) {
                    logger.info("📊 Прогресс: {}/{} ({}%)", 
                        Math.min(i + batchSize, urls.size()), urls.size(),
                        Math.round((double)(i + batchSize) / urls.size() * 100));
                }
                
                // Небольшая пауза между батчами
                Thread.sleep(200);
            }
            
        } catch (Exception e) {
            logger.error("💥 Ошибка умного парсинга: {}", e.getMessage());
        }
    }
    
    @Override
    public void close() throws Exception {
        if (executorService != null) {
            executorService.shutdown();
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        }
        
        if (databaseManager != null) {
            databaseManager.close();
        }
    }
}
