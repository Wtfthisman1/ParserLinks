package com.parser.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Утилиты для валидации URL и файловых путей
 */
public class UrlValidator {
    
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])?$");
    
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
        "(\\.\\./|\\.\\.\\\\)");
    
    /**
     * Проверяет, является ли строка валидным URL
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        
        try {
            new URI(url);
            return URL_PATTERN.matcher(url).matches();
        } catch (URISyntaxException e) {
            return false;
        }
    }
    
    /**
     * Проверяет, является ли URL ссылкой на изображение
     */
    public static boolean isImageUrl(String url) {
        if (!isValidUrl(url)) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        
        // Проверяем расширения файлов
        String[] imageExtensions = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".ico"};
        for (String ext : imageExtensions) {
            if (lowerUrl.contains(ext)) {
                return true;
            }
        }
        
        // Проверяем домены известных фотохостингов
        String[] imageDomains = {
            "imgbb.com", "postimages.org", "postimg.cc", "gekkk.co",
            "imgur.com", "flickr.com", "500px.com", "unsplash.com"
        };
        
        for (String domain : imageDomains) {
            if (lowerUrl.contains(domain)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Проверяет, является ли URL логотипом сайта
     */
    public static boolean isLogo(String url) {
        if (!isValidUrl(url)) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        
        // Исключаем логотипы и иконки
        String[] logoPatterns = {
            "logo", "favicon", "icon", "brand", "header", "nav"
        };
        
        for (String pattern : logoPatterns) {
            if (lowerUrl.contains(pattern)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Валидирует и нормализует файловый путь
     */
    public static Path validateAndNormalizePath(String basePath, String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("Имя файла не может быть пустым");
        }
        
        // Проверяем на path traversal
        if (PATH_TRAVERSAL_PATTERN.matcher(fileName).find()) {
            throw new SecurityException("Обнаружена попытка path traversal: " + fileName);
        }
        
        // Удаляем недопустимые символы
        String sanitizedFileName = fileName.replaceAll("[<>:\"/\\|?*]", "_");
        
        // Создаем путь
        Path base = Paths.get(basePath);
        Path filePath = base.resolve(sanitizedFileName);
        
        // Проверяем, что итоговый путь находится в базовой директории
        if (!filePath.normalize().startsWith(base.normalize())) {
            throw new SecurityException("Попытка доступа к файлу вне базовой директории");
        }
        
        return filePath;
    }
    
    /**
     * Преобразует относительный URL в абсолютный
     */
    public static String resolveUrl(String baseUrl, String relativeUrl) {
        if (baseUrl == null || relativeUrl == null) {
            return relativeUrl;
        }
        
        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(relativeUrl);
            return resolved.toString();
        } catch (URISyntaxException e) {
            return relativeUrl;
        }
    }
    
    /**
     * Извлекает домен из URL
     */
    public static String extractDomain(String url) {
        if (!isValidUrl(url)) {
            return null;
        }
        
        try {
            URI uri = new URI(url);
            return uri.getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
    
    /**
     * Проверяет, принадлежит ли URL к указанному домену
     */
    public static boolean isSameDomain(String url, String domain) {
        String urlDomain = extractDomain(url);
        return urlDomain != null && urlDomain.equalsIgnoreCase(domain);
    }
}
