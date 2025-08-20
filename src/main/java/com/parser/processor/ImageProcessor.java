package com.parser.processor;

import com.parser.config.ParserConfig;
import com.parser.model.LinkResult;
import okhttp3.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Random;

/**
 * Процессор для работы с изображениями
 */
public class ImageProcessor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessor.class);
    
    private final OkHttpClient httpClient;
    private final Map<String, String> contentTypeExtensions;
    private final Random random;
    
    public ImageProcessor() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(ParserConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(ParserConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(ParserConfig.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .protocols(Arrays.asList(Protocol.HTTP_1_1)) // Принудительно используем HTTP/1.1
                .build();
        
        this.contentTypeExtensions = new HashMap<>();
        this.random = new Random();
        initializeContentTypeExtensions();
    }
    
    private void initializeContentTypeExtensions() {
        contentTypeExtensions.put("image/jpeg", ".jpg");
        contentTypeExtensions.put("image/jpg", ".jpg");
        contentTypeExtensions.put("image/png", ".png");
        contentTypeExtensions.put("image/gif", ".gif");
        contentTypeExtensions.put("image/webp", ".webp");
        contentTypeExtensions.put("image/bmp", ".bmp");
        contentTypeExtensions.put("image/tiff", ".tiff");
        contentTypeExtensions.put("image/svg+xml", ".svg");
    }
    
    /**
     * Имитирует человеческое поведение - случайная задержка между запросами
     */
    private void humanDelay() {
        try {
            long delay = ParserConfig.MIN_DELAY_BETWEEN_REQUESTS_MS + 
                        random.nextInt((int)(ParserConfig.MAX_DELAY_BETWEEN_REQUESTS_MS - ParserConfig.MIN_DELAY_BETWEEN_REQUESTS_MS));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Возвращает случайный User-Agent для имитации разных браузеров
     */
    private String getRandomUserAgent() {
        return ParserConfig.USER_AGENTS[random.nextInt(ParserConfig.USER_AGENTS.length)];
    }
    
    /**
     * Создает HTTP запрос с человеческими заголовками
     */
    private Request.Builder createHumanRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", getRandomUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1");
    }
    
    /**
     * Создает HTTP запрос специально для скачивания изображений
     */
    private Request.Builder createImageRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .header("Referer", "https://postimg.cc/")
                .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache");
    }
    
    /**
     * Проверка доступности изображения
     */
    public ImageMetadata checkImage(String url, String hosting) {
        // Сначала пробуем HEAD запрос
        ImageMetadata result = checkImageWithHead(url, hosting);
        if (result != null) {
            return result;
        }
        
        // Если HEAD не сработал, пробуем GET запрос
        logger.info("🔄 HEAD не сработал, пробуем GET для: {}", url);
        return checkImageWithGet(url, hosting);
    }
    
    private ImageMetadata checkImageWithHead(String url, String hosting) {
        try {
            logger.info("🔍 Проверяю HEAD: {}", url);
            
            // Имитируем человеческое поведение
            humanDelay();
            
            Request request = createHumanRequest(url)
                    .head()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                logger.info("📡 HEAD ответ: {} - {}", response.code(), response.message());
                
                if (!response.isSuccessful()) {
                    logger.info("❌ HEAD HTTP ошибка: {} {}", response.code(), response.message());
                    return null;
                }
                
                ResponseBody body = response.body();
                if (body == null) {
                    logger.info("❌ HEAD пустой ответ");
                    return null;
                }
                
                // Проверяем, что это изображение
                String contentType = response.header("content-type");
                logger.info("📄 HEAD Content-Type: {}", contentType);
                
                if (contentType == null || !contentType.startsWith("image/")) {
                    logger.info("❌ HEAD не изображение: {}", contentType);
                    return null;
                }
                
                // Получаем размер файла
                String contentLength = response.header("content-length");
                Long fileSize = contentLength != null ? Long.parseLong(contentLength) : null;
                logger.info("📏 HEAD размер: {} bytes", fileSize != null ? fileSize : "неизвестен");
                
                // Проверяем размер файла
                if (fileSize != null && fileSize > ParserConfig.MAX_IMAGE_SIZE_MB * 1024 * 1024) {
                    logger.info("❌ HEAD файл слишком большой: {} bytes", fileSize);
                    return null;
                }
                
                // Получаем заголовки для определения возраста
                String lastModified = response.header("last-modified");
                String date = response.header("date");
                logger.info("📅 HEAD Last-Modified: {}", lastModified);
                logger.info("📅 HEAD Date: {}", date);
                
                logger.info("✅ HEAD изображение найдено! Тип: {}, Размер: {} bytes", contentType, fileSize);
                
                return new ImageMetadata(
                    contentType,
                    fileSize,
                    lastModified,
                    date,
                    response.headers().toMultimap()
                );
            }
        } catch (Exception e) {
            logger.error("💥 HEAD ошибка проверки {}: {}", url, e.getMessage());
        }
        
        return null;
    }
    
    private ImageMetadata checkImageWithGet(String url, String hosting) {
        return checkImageWithGet(url, hosting, 0);
    }
    
    private ImageMetadata checkImageWithGet(String url, String hosting, int depth) {
        try {
            logger.info("🔍 Проверяю GET (глубина {}): {}", depth, url);
            
            // Имитируем человеческое поведение
            humanDelay();
            
            Request request = createImageRequest(url)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                logger.info("📡 GET ответ: {} - {}", response.code(), response.message());
                
                if (!response.isSuccessful()) {
                    logger.info("❌ GET HTTP ошибка: {} {}", response.code(), response.message());
                    return null;
                }
                
                ResponseBody body = response.body();
                if (body == null) {
                    logger.info("❌ GET пустой ответ");
                    return null;
                }
                
                // Проверяем, что это изображение
                String contentType = response.header("content-type");
                logger.info("📄 GET Content-Type: {}", contentType);
                
                if (contentType == null || !contentType.startsWith("image/")) {
                    logger.info("❌ GET не изображение: {}", contentType);
                    
                    // Если это HTML страница, пробуем извлечь изображения из неё
                    if (contentType.contains("text/html")) {
                        logger.info("🌐 Обнаружена HTML страница, извлекаю изображения...");
                        return extractImagesFromHtml(url, hosting);
                    }
                    
                    return null;
                }
                
                // Получаем размер файла
                String contentLength = response.header("content-length");
                Long fileSize = contentLength != null ? Long.parseLong(contentLength) : null;
                logger.info("📏 GET размер: {} bytes", fileSize != null ? fileSize : "неизвестен");
                
                // Проверяем размер файла
                if (fileSize != null && fileSize > ParserConfig.MAX_IMAGE_SIZE_MB * 1024 * 1024) {
                    logger.info("❌ GET файл слишком большой: {} bytes", fileSize);
                    return null;
                }
                
                // Получаем заголовки для определения возраста
                String lastModified = response.header("last-modified");
                String date = response.header("date");
                logger.info("📅 GET Last-Modified: {}", lastModified);
                logger.info("📅 GET Date: {}", date);
                
                logger.info("✅ GET изображение найдено! Тип: {}, Размер: {} bytes", contentType, fileSize);
                
                return new ImageMetadata(
                    contentType,
                    fileSize,
                    lastModified,
                    date,
                    response.headers().toMultimap()
                );
            }
        } catch (Exception e) {
            logger.error("💥 GET ошибка проверки {}: {}", url, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Извлекает изображения из HTML страницы
     */
    private ImageMetadata extractImagesFromHtml(String pageUrl, String hosting) {
        return extractImagesFromHtml(pageUrl, hosting, 0);
    }
    
    private ImageMetadata extractImagesFromHtml(String pageUrl, String hosting, int depth) {
        // Защита от зацикливания
        if (depth > 2) {
            logger.warn("⚠️ Превышена глубина рекурсии при извлечении изображений из HTML");
            return null;
        }
        
        try (AdvancedHtmlImageExtractor extractor = new AdvancedHtmlImageExtractor()) {
            List<String> imageUrls = extractor.extractImageUrls(pageUrl);
            
            if (imageUrls.isEmpty()) {
                logger.info("❌ В HTML странице не найдено изображений");
                return null;
            }
            
            // Берем первое найденное изображение
            String firstImageUrl = imageUrls.get(0);
            logger.info("🖼️ Найдено изображение в HTML (глубина {}): {}", depth, firstImageUrl);
            
            // Проверяем, не пытаемся ли мы скачать ту же страницу
            if (firstImageUrl.equals(pageUrl)) {
                logger.warn("⚠️ Обнаружена циклическая ссылка: {}", firstImageUrl);
                return null;
            }
            
            // Проверяем это изображение и передаем URL изображения
            ImageMetadata metadata = checkImageWithGet(firstImageUrl, hosting, depth + 1);
            if (metadata != null) {
                // Создаем новые метаданные с URL изображения
                return new ImageMetadata(
                    metadata.getContentType(),
                    metadata.getFileSize(),
                    metadata.getLastModified(),
                    metadata.getDate(),
                    metadata.getHeaders(),
                    firstImageUrl // URL изображения
                );
            }
            return metadata;
            
        } catch (Exception e) {
            logger.error("💥 Ошибка извлечения изображений из HTML: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Скачивание изображения
     */
    public Path downloadImage(String url, String hosting, ImageMetadata metadata) {
        try {
            // Имитируем человеческое поведение
            humanDelay();
            
            // Используем URL изображения из метаданных, если он доступен
            String downloadUrl = metadata.getImageUrl() != null ? metadata.getImageUrl() : url;
            logger.info("⬇️ Скачиваю с URL: {} (исходный: {})", downloadUrl, url);
            
            // Определяем расширение файла
            String extension = getExtensionFromContentType(metadata.getContentType());
            if (extension == null) {
                extension = getExtensionFromUrl(downloadUrl);
                if (extension == null) {
                    extension = ".jpg"; // По умолчанию
                }
            }
            
            // Генерируем имя файла
            String filename = generateFilename(url, hosting, extension);
            Path filepath = ParserConfig.DOWNLOAD_DIR.resolve(filename);
            
            // Создаем директорию если не существует
            Files.createDirectories(ParserConfig.DOWNLOAD_DIR);
            
            // Используем curl для скачивания (так как OkHttp не работает с postimg.cc)
            String curlCommand = String.format(
                "curl -s -L \"%s\" -H \"User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\" " +
                "-H \"Accept: */*\" -H \"Referer: https://postimg.cc/\" -o \"%s\"",
                downloadUrl, filepath.toString()
            );
            
            logger.info("🔄 Выполняю curl команду: {}", curlCommand);
            
            Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", curlCommand});
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                logger.info("✅ Скачан файл: {}", filepath);
                return filepath;
            } else {
                logger.error("❌ Ошибка curl (код {}): {}", exitCode, curlCommand);
                return null;
            }
            
        } catch (Exception e) {
            logger.error("Ошибка скачивания {}: {}", url, e.getMessage());
            logger.error("Детали ошибки:", e);
        }
        
        return null;
    }
    
    /**
     * Проверка возраста изображения в днях
     */
    public Integer checkImageAge(ImageMetadata metadata) {
        try {
            // Пробуем получить дату из заголовков
            String lastModified = metadata.getLastModified();
            if (lastModified != null) {
                try {
                    // Парсим RFC 2822 формат
                    DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
                    LocalDateTime dt = LocalDateTime.parse(lastModified, formatter);
                    return (int) java.time.Duration.between(dt, LocalDateTime.now()).toDays();
                } catch (Exception e) {
                    // Игнорируем ошибки парсинга
                }
            }
            
            // Пробуем другие форматы даты
            String dateHeader = metadata.getDate();
            if (dateHeader != null) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
                    LocalDateTime dt = LocalDateTime.parse(dateHeader, formatter);
                    return (int) java.time.Duration.between(dt, LocalDateTime.now()).toDays();
                } catch (Exception e) {
                    // Игнорируем ошибки парсинга
                }
            }
            
            // Если не удалось определить возраст, считаем что изображение новое
            return 0;
            
        } catch (Exception e) {
            logger.debug("Ошибка определения возраста: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Определяет, нужно ли скачивать изображение
     */
    public boolean shouldDownloadImage(Integer ageDays) {
        if (ageDays == null) {
            // Если не удалось определить возраст, скачиваем
            return true;
        }
        
        return ageDays >= ParserConfig.MIN_IMAGE_AGE_DAYS;
    }
    
    /**
     * Полная обработка URL изображения
     */
    public LinkResult processImageUrl(String url, String hosting) {
        try {
            logger.info("🚀 Начинаю обработку: {}", url);
            
            // Проверяем доступность
            ImageMetadata metadata = checkImage(url, hosting);
            
            if (metadata == null) {
                logger.info("📭 Результат: ПУСТАЯ ссылка");
                return LinkResult.builder()
                        .url(url)
                        .hosting(hosting)
                        .status("empty")
                        .build();
            }
            
            // Проверяем возраст
            Integer ageDays = checkImageAge(metadata);
            logger.info("📅 Возраст изображения: {} дней", ageDays != null ? ageDays : "неизвестен");
            
            // Определяем, нужно ли скачивать
            if (!shouldDownloadImage(ageDays)) {
                logger.info("⏭️ Результат: ПРОПУЩЕНО (слишком новое, {} дней)", ageDays);
                return LinkResult.builder()
                        .url(url)
                        .hosting(hosting)
                        .status("skipped")
                        .imageAgeDays(ageDays)
                        .build();
            }
            
            // Скачиваем изображение
            logger.info("⬇️ Скачиваю изображение...");
            Path filePath = downloadImage(url, hosting, metadata);
            
            if (filePath != null) {
                logger.info("✅ Результат: СКАЧАНО в {}", filePath);
                return LinkResult.builder()
                        .url(url)
                        .hosting(hosting)
                        .status("downloaded")
                        .filePath(filePath)
                        .fileSize(metadata.getFileSize())
                        .imageAgeDays(ageDays)
                        .build();
            } else {
                logger.error("❌ Результат: ОШИБКА скачивания");
                return LinkResult.builder()
                        .url(url)
                        .hosting(hosting)
                        .status("error")
                        .imageAgeDays(ageDays)
                        .errorMessage("Ошибка скачивания")
                        .build();
            }
            
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            logger.error("💥 Ошибка обработки {}: {}", url, errorMsg);
            return LinkResult.builder()
                    .url(url)
                    .hosting(hosting)
                    .status("error")
                    .errorMessage(errorMsg)
                    .build();
        }
    }
    
    private String getExtensionFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        return contentTypeExtensions.get(contentType.toLowerCase());
    }
    
    private String getExtensionFromUrl(String url) {
        String path = url.toLowerCase();
        for (String ext : contentTypeExtensions.values()) {
            if (path.endsWith(ext)) {
                return ext;
            }
        }
        return null;
    }
    
    private String generateFilename(String url, String hosting, String extension) {
        // Извлекаем токен из URL
        String[] pathParts = url.split("/");
        String token = pathParts[pathParts.length - 1]; // Последняя часть пути - токен
        
        // Добавляем префикс хоста для организации файлов
        String filename = hosting + "_" + token + extension;
        
        // Проверяем, что файл не существует
        int counter = 1;
        String originalFilename = filename;
        Path filepath = ParserConfig.DOWNLOAD_DIR.resolve(filename);
        
        while (Files.exists(filepath)) {
            String name = FilenameUtils.getBaseName(originalFilename);
            String ext = FilenameUtils.getExtension(originalFilename);
            filename = name + "_" + counter + "." + ext;
            filepath = ParserConfig.DOWNLOAD_DIR.resolve(filename);
            counter++;
        }
        
        return filename;
    }
    
    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
    
    /**
     * Метаданные изображения
     */
    public static class ImageMetadata {
        private final String contentType;
        private final Long fileSize;
        private final String lastModified;
        private final String date;
        private final Map<String, java.util.List<String>> headers;
        private final String imageUrl; // URL изображения (может отличаться от исходного URL)
        
        public ImageMetadata(String contentType, Long fileSize, String lastModified, 
                           String date, Map<String, java.util.List<String>> headers) {
            this(contentType, fileSize, lastModified, date, headers, null);
        }
        
        public ImageMetadata(String contentType, Long fileSize, String lastModified, 
                           String date, Map<String, java.util.List<String>> headers, String imageUrl) {
            this.contentType = contentType;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.date = date;
            this.headers = headers;
            this.imageUrl = imageUrl;
        }
        
        public String getContentType() { return contentType; }
        public Long getFileSize() { return fileSize; }
        public String getLastModified() { return lastModified; }
        public String getDate() { return date; }
        public Map<String, java.util.List<String>> getHeaders() { return headers; }
        public String getImageUrl() { return imageUrl; }
    }
}
