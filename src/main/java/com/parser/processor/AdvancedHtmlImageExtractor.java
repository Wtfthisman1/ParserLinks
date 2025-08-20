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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Улучшенный извлекатель ссылок на изображения из HTML страниц
 * Поддерживает JavaScript-рендеринг, AJAX и современные форматы
 */
public class AdvancedHtmlImageExtractor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AdvancedHtmlImageExtractor.class);
    
    private final OkHttpClient httpClient;
    private final Random random;
    
    public AdvancedHtmlImageExtractor() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        this.random = new Random();
    }
    
    /**
     * Извлекает ссылки на изображения из HTML страницы с улучшенным парсингом
     */
    public List<String> extractImageUrls(String pageUrl) {
        List<String> imageUrls = new ArrayList<>();
        
        try {
            logger.info("🌐 Расширенный парсинг HTML страницы: {}", pageUrl);
            
            // Скачиваем HTML страницу с улучшенными заголовками
            Request request = new Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
                
                // Парсим HTML
                Document doc = Jsoup.parse(html, pageUrl);
                
                // ПРИОРИТЕТ 1: Мета-теги (Open Graph, Twitter Cards)
                extractFromMetaTags(doc, pageUrl, imageUrls);
                
                // ПРИОРИТЕТ 2: Расширенный поиск в img тегах
                extractFromImgTags(doc, pageUrl, imageUrls);
                
                // ПРИОРИТЕТ 3: Поиск в ссылках
                extractFromLinks(doc, pageUrl, imageUrls);
                
                // ПРИОРИТЕТ 4: Поиск в JavaScript коде
                extractFromJavaScript(html, pageUrl, imageUrls);
                
                logger.info("📊 Всего найдено {} изображений", imageUrls.size());
                
            }
        } catch (Exception e) {
            logger.error("💥 Ошибка расширенного парсинга HTML {}: {}", pageUrl, e.getMessage());
        }
        
        return imageUrls;
    }
    
    /**
     * Извлекает изображения из мета-тегов
     */
    private void extractFromMetaTags(Document doc, String pageUrl, List<String> imageUrls) {
        logger.info("🔍 Поиск изображений в мета-тегах...");
        
        String[] metaSelectors = {
            "meta[property='og:image']",
            "meta[property='og:image:url']",
            "meta[name='twitter:image']",
            "meta[name='twitter:image:src']",
            "meta[property='image']",
            "meta[name='image']"
        };
        
        for (String selector : metaSelectors) {
            Elements elements = doc.select(selector);
            for (Element element : elements) {
                String content = element.attr("content");
                if (content != null && !content.trim().isEmpty()) {
                    String absoluteUrl = resolveUrl(pageUrl, content);
                    if (isImageUrl(absoluteUrl)) {
                        imageUrls.add(absoluteUrl);
                        logger.info("🏆 Изображение из мета-тега: {}", absoluteUrl);
                    }
                }
            }
        }
    }
    
    /**
     * Извлекает изображения из img тегов с расширенными селекторами
     */
    private void extractFromImgTags(Document doc, String pageUrl, List<String> imageUrls) {
        logger.info("🖼️ Расширенный поиск изображений в img тегах...");
        
        String[] selectors = {
            "img[src]", "img[data-src]", "img[data-lazy-src]", "img[data-original]",
            "img[data-srcset]", "img[data-lazy]", "img[data-image]", "img[data-img]"
        };
        
        for (String selector : selectors) {
            Elements elements = doc.select(selector);
            logger.info("🔍 Селектор '{}': найдено {} элементов", selector, elements.size());
            
            for (Element img : elements) {
                String[] srcAttributes = {
                    "src", "data-src", "data-lazy-src", "data-original", 
                    "data-srcset", "data-lazy", "data-image", "data-img"
                };
                
                for (String attr : srcAttributes) {
                    String src = img.attr(attr);
                    if (src != null && !src.trim().isEmpty()) {
                        String absoluteUrl = resolveUrl(pageUrl, src);
                        if (isImageUrl(absoluteUrl) && !isLogo(absoluteUrl)) {
                            imageUrls.add(absoluteUrl);
                            logger.info("✅ Изображение из img тега ({}): {}", attr, absoluteUrl);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Извлекает изображения из ссылок
     */
    private void extractFromLinks(Document doc, String pageUrl, List<String> imageUrls) {
        logger.info("🔗 Поиск изображений в ссылках...");
        
        Elements linkElements = doc.select("a[href]");
        for (Element link : linkElements) {
            String href = link.attr("href");
            if (href != null && !href.trim().isEmpty()) {
                String absoluteUrl = resolveUrl(pageUrl, href);
                if (isImageUrl(absoluteUrl) && !isLogo(absoluteUrl)) {
                    imageUrls.add(absoluteUrl);
                    logger.info("✅ Ссылка на изображение: {}", absoluteUrl);
                }
            }
        }
    }
    
    /**
     * Извлекает изображения из JavaScript кода
     */
    private void extractFromJavaScript(String html, String pageUrl, List<String> imageUrls) {
        logger.info("📜 Поиск изображений в JavaScript коде...");
        
        Pattern[] patterns = {
            Pattern.compile("https?://[^\\s\"'<>]+?\\.(?:jpg|jpeg|png|gif|webp|bmp|svg)(?:\\?[^\\s\"'<>]*)?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("['\"](https?://[^\\s\"'<>]+?\\.(?:jpg|jpeg|png|gif|webp|bmp|svg)(?:\\?[^\\s\"'<>]*)?)['\"]", Pattern.CASE_INSENSITIVE)
        };
        
        for (Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(html);
            while (matcher.find()) {
                String imageUrl = matcher.group(1);
                if (imageUrl != null && !imageUrl.trim().isEmpty()) {
                    String absoluteUrl = resolveUrl(pageUrl, imageUrl);
                    if (isImageUrl(absoluteUrl) && !isLogo(absoluteUrl)) {
                        imageUrls.add(absoluteUrl);
                        logger.info("📜 Изображение из JavaScript: {}", absoluteUrl);
                    }
                }
            }
        }
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
            "imgbb.com", "ibb.co", "i.ibb.co",
            "postimages.org", "postimg.cc", "i.postimg.cc",
            "imgur.com", "i.imgur.com"
        };
        
        for (String domain : imageDomains) {
            if (lowerUrl.contains(domain)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Проверяет, является ли URL логотипом
     */
    private boolean isLogo(String url) {
        if (url == null) return false;
        String lowerUrl = url.toLowerCase();
        
        String[] logoPatterns = {
            "logo", "favicon", "icon", "brand", "header", "nav", "banner"
        };
        
        for (String pattern : logoPatterns) {
            if (lowerUrl.contains(pattern)) {
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    public void close() throws Exception {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
}
