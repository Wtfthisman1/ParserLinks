package com.parser.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Конфигурация парсера фотохостингов
 */
public class ParserConfig {
    
    // Настройки производительности
    public static final int MAX_CONCURRENT_REQUESTS = 5;   // Уменьшено для стабильности
    public static final int REQUEST_TIMEOUT_SECONDS = 30;  // Увеличено для медленных сайтов
    public static final int RETRY_ATTEMPTS = 3;            // Увеличено количество попыток
    public static final long RETRY_DELAY_MS = 3000;        // Увеличено время между попытками
    
    // Настройки для имитации человеческого поведения
    public static final long MIN_DELAY_BETWEEN_REQUESTS_MS = 1000; // Увеличена минимальная задержка
    public static final long MAX_DELAY_BETWEEN_REQUESTS_MS = 5000; // Увеличена максимальная задержка
    public static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/121.0"
    };
    
    // Настройки фильтрации
    public static final int MIN_IMAGE_AGE_DAYS = 0;  // Временно 0 для тестирования (обычно 7)
    public static final int MAX_IMAGE_SIZE_MB = 50;
    
    // Настройки сохранения
    public static final Path DOWNLOAD_DIR = Paths.get("downloads");
    public static final Path DB_PATH = Paths.get("parser_data.db");
    
    // Настройки логирования
    public static final String LOG_LEVEL = "INFO";
    public static final Path LOG_FILE = Paths.get("parser.log");
    
    // Конфигурация фотохостингов
    public static final Map<String, HostingConfig> HOSTINGS = new ConcurrentHashMap<>();
    
    static {
        // ibb.co (ImgBB)
        HOSTINGS.put("imgbb", new HostingConfig(
            "ibb.co",
            "https://ibb.co",
            8,
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "/",
            "/"
        ));
        
        // postimg.cc (Postimages)
        HOSTINGS.put("postimages", new HostingConfig(
            "postimg.cc",
            "https://postimg.cc",
            8,
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "/",
            "/"
        ));
    }
    
    /**
     * Конфигурация для конкретного фотохостинга
     */
    public static class HostingConfig {
        private final String name;
        private final String baseUrl;
        private final int tokenLength;
        private final String tokenChars;
        private final String checkPath;
        private final String downloadPath;
        
        public HostingConfig(String name, String baseUrl, int tokenLength, 
                           String tokenChars, String checkPath, String downloadPath) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.tokenLength = tokenLength;
            this.tokenChars = tokenChars;
            this.checkPath = checkPath;
            this.downloadPath = downloadPath;
        }
        
        public String getName() { return name; }
        public String getBaseUrl() { return baseUrl; }
        public int getTokenLength() { return tokenLength; }
        public String getTokenChars() { return tokenChars; }
        public String getCheckPath() { return checkPath; }
        public String getDownloadPath() { return downloadPath; }
        
        public String buildUrl(String token) {
            return baseUrl + checkPath + token;
        }
    }
}
