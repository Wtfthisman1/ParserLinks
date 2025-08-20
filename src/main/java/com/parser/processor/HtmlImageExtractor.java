package com.parser.processor;

import com.parser.config.ParserConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Random;

/**
 * Извлекает ссылки на изображения из HTML страниц
 */
public class HtmlImageExtractor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(HtmlImageExtractor.class);
    
    private final OkHttpClient httpClient;
    private final Random random;
    
    public HtmlImageExtractor() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.random = new Random();
    }
    
    /**
     * Извлекает ссылки на изображения из HTML страницы
     */
    public List<String> extractImageUrls(String pageUrl) {
        List<String> imageUrls = new ArrayList<>();
        
        try {
            logger.info("🌐 Парсинг HTML страницы: {}", pageUrl);
            
            // Скачиваем HTML страницу с человеческими заголовками (без сжатия)
            Request request = new Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", ParserConfig.USER_AGENTS[random.nextInt(ParserConfig.USER_AGENTS.length)])
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                    .header("DNT", "1")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.info("❌ Не удалось загрузить страницу: {} {}", response.code(), response.message());
                    return imageUrls;
                }
                
                ResponseBody body = response.body();
                if (body == null) {
                    logger.info("❌ Пустой ответ от страницы");
                    return imageUrls;
                }
                
                String html = body.string();
                logger.info("📄 HTML размер: {} символов", html.length());
                
                // Показываем первые 500 символов HTML для отладки
                logger.info("📄 HTML начало: {}", html.substring(0, Math.min(500, html.length())));
                
                // Парсим HTML
                Document doc = Jsoup.parse(html, pageUrl);
                
                // ПРИОРИТЕТ 1: Ищем в мета-тегах (обычно содержат основное изображение)
                Elements metaElements = doc.select("meta[property='og:image'], meta[name='twitter:image']");
                logger.info("🔍 Найдено {} мета-тегов с изображениями", metaElements.size());
                
                // Дополнительно ищем все мета-теги для отладки
                Elements allMetaElements = doc.select("meta");
                logger.info("🔍 Всего мета-тегов: {}", allMetaElements.size());
                for (Element meta : allMetaElements) {
                    String property = meta.attr("property");
                    String name = meta.attr("name");
                    String content = meta.attr("content");
                    if ((property != null && (property.contains("image") || property.contains("og"))) ||
                        (name != null && name.contains("image"))) {
                        logger.info("🔍 Найден мета-тег: property='{}', name='{}', content='{}'", property, name, content);
                    }
                }
                
                for (Element meta : metaElements) {
                    String content = meta.attr("content");
                    if (content != null && !content.trim().isEmpty()) {
                        String absoluteUrl = resolveUrl(pageUrl, content);
                        if (isImageUrl(absoluteUrl)) {
                            imageUrls.add(absoluteUrl);
                            logger.info("🏆 ПРИОРИТЕТНОЕ изображение из мета-тега: {}", absoluteUrl);
                        }
                    }
                }
                
                // ПРИОРИТЕТ 2: Ищем большие изображения в img тегах (исключаем логотипы)
                Elements imgElements = doc.select("img[src]");
                logger.info("🖼️ Найдено {} img тегов", imgElements.size());
                
                // Показываем все img теги для отладки
                for (Element img : imgElements) {
                    String src = img.attr("src");
                    String id = img.attr("id");
                    String alt = img.attr("alt");
                    logger.info("🖼️ Найден img: src='{}', id='{}', alt='{}'", src, id, alt);
                }
                
                for (Element img : imgElements) {
                    String src = img.attr("src");
                    if (src != null && !src.trim().isEmpty()) {
                        String absoluteUrl = resolveUrl(pageUrl, src);
                        if (isImageUrl(absoluteUrl) && !isLogo(absoluteUrl)) {
                            imageUrls.add(absoluteUrl);
                            logger.info("✅ Найдено изображение: {}", absoluteUrl);
                        }
                    }
                }
                
                // ПРИОРИТЕТ 3: Ищем ссылки на изображения в других тегах
                Elements linkElements = doc.select("a[href]");
                logger.info("🔗 Найдено {} ссылок", linkElements.size());
                
                for (Element link : linkElements) {
                    String href = link.attr("href");
                    if (href != null && !href.trim().isEmpty()) {
                        String absoluteUrl = resolveUrl(pageUrl, href);
                        if (isImageUrl(absoluteUrl) && !isLogo(absoluteUrl)) {
                            imageUrls.add(absoluteUrl);
                            logger.info("✅ Найдена ссылка на изображение: {}", absoluteUrl);
                        }
                    }
                }
                
                logger.info("📊 Всего найдено {} изображений", imageUrls.size());
                
            }
        } catch (Exception e) {
            logger.error("💥 Ошибка парсинга HTML {}: {}", pageUrl, e.getMessage());
        }
        
        return imageUrls;
    }
    
    /**
     * Преобразует относительный URL в абсолютный
     */
    private String resolveUrl(String baseUrl, String relativeUrl) {
        try {
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, relativeUrl);
            return resolved.toString();
        } catch (Exception e) {
            logger.warn("Не удалось разрешить URL: {} + {}", baseUrl, relativeUrl);
            return relativeUrl;
        }
    }
    
    /**
     * Проверяет, является ли URL ссылкой на изображение
     */
    private boolean isImageUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
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
    private boolean isLogo(String url) {
        if (url == null || url.trim().isEmpty()) {
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
     * Закрывает ресурсы
     */
    public void close() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
}
