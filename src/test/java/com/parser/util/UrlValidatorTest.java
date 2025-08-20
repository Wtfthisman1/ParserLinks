package com.parser.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

/**
 * Тесты для класса UrlValidator
 */
public class UrlValidatorTest {
    
    @Test
    public void testIsValidUrl() {
        // Валидные URL
        assertTrue(UrlValidator.isValidUrl("https://example.com"));
        assertTrue(UrlValidator.isValidUrl("http://test.org/path"));
        assertTrue(UrlValidator.isValidUrl("https://ru.imgbb.com/abc123"));
        assertTrue(UrlValidator.isValidUrl("https://postimages.org/ru/def456"));
        
        // Невалидные URL
        assertFalse(UrlValidator.isValidUrl(null));
        assertFalse(UrlValidator.isValidUrl(""));
        assertFalse(UrlValidator.isValidUrl("not-a-url"));
        assertFalse(UrlValidator.isValidUrl("ftp://example.com"));
        assertFalse(UrlValidator.isValidUrl("https://"));
    }
    
    @Test
    public void testIsImageUrl() {
        // URL изображений
        assertTrue(UrlValidator.isImageUrl("https://example.com/image.jpg"));
        assertTrue(UrlValidator.isImageUrl("https://example.com/photo.png"));
        assertTrue(UrlValidator.isImageUrl("https://ru.imgbb.com/abc123"));
        assertTrue(UrlValidator.isImageUrl("https://postimages.org/ru/def456"));
        
        // Не изображения
        assertFalse(UrlValidator.isImageUrl("https://example.com/page.html"));
        assertFalse(UrlValidator.isImageUrl("https://example.com/script.js"));
        assertFalse(UrlValidator.isImageUrl("not-a-url"));
    }
    
    @Test
    public void testIsLogo() {
        // Логотипы
        assertTrue(UrlValidator.isLogo("https://example.com/logo.png"));
        assertTrue(UrlValidator.isLogo("https://example.com/favicon.ico"));
        assertTrue(UrlValidator.isLogo("https://example.com/header-logo.jpg"));
        assertTrue(UrlValidator.isLogo("https://example.com/nav-icon.svg"));
        
        // Не логотипы
        assertFalse(UrlValidator.isLogo("https://example.com/photo.jpg"));
        assertFalse(UrlValidator.isLogo("https://example.com/image.png"));
        assertFalse(UrlValidator.isLogo("not-a-url"));
    }
    
    @Test
    public void testValidateAndNormalizePath() {
        // Валидные пути
        Path validPath = UrlValidator.validateAndNormalizePath("downloads", "image.jpg");
        assertNotNull(validPath);
        assertTrue(validPath.toString().contains("image.jpg"));
        
        // Путь с недопустимыми символами
        Path sanitizedPath = UrlValidator.validateAndNormalizePath("downloads", "image<>:\"/\\|?*.jpg");
        assertNotNull(sanitizedPath);
        String sanitizedPathStr = sanitizedPath.toString();
        System.out.println("Sanitized path: " + sanitizedPathStr);
        assertTrue(sanitizedPathStr.contains("image_____") && sanitizedPathStr.contains(".jpg"));
    }
    
    @Test
    public void testValidateAndNormalizePath_ThrowsException() {
        // Пустое имя файла
        assertThrows(IllegalArgumentException.class, () -> {
            UrlValidator.validateAndNormalizePath("downloads", "");
        });
        
        // Null имя файла
        assertThrows(IllegalArgumentException.class, () -> {
            UrlValidator.validateAndNormalizePath("downloads", null);
        });
        
        // Path traversal попытка
        assertThrows(SecurityException.class, () -> {
            UrlValidator.validateAndNormalizePath("downloads", "../../../etc/passwd");
        });
        
        // Path traversal попытка с обратными слешами
        assertThrows(SecurityException.class, () -> {
            UrlValidator.validateAndNormalizePath("downloads", "..\\..\\..\\windows\\system32\\config");
        });
    }
    
    @Test
    public void testResolveUrl() {
        // Абсолютные URL
        assertEquals("https://example.com/image.jpg", 
            UrlValidator.resolveUrl("https://example.com/page.html", "https://example.com/image.jpg"));
        
        // Относительные URL
        assertEquals("https://example.com/image.jpg", 
            UrlValidator.resolveUrl("https://example.com/page.html", "/image.jpg"));
        
        assertEquals("https://example.com/path/image.jpg", 
            UrlValidator.resolveUrl("https://example.com/path/page.html", "image.jpg"));
        
        // Null значения
        assertEquals("relative.jpg", UrlValidator.resolveUrl(null, "relative.jpg"));
        assertEquals(null, UrlValidator.resolveUrl("https://example.com", null));
    }
    
    @Test
    public void testExtractDomain() {
        assertEquals("example.com", UrlValidator.extractDomain("https://example.com/page"));
        assertEquals("ru.imgbb.com", UrlValidator.extractDomain("https://ru.imgbb.com/abc123"));
        assertEquals("postimages.org", UrlValidator.extractDomain("https://postimages.org/ru/def456"));
        assertEquals(null, UrlValidator.extractDomain("not-a-url"));
        assertEquals(null, UrlValidator.extractDomain(null));
    }
    
    @Test
    public void testIsSameDomain() {
        assertTrue(UrlValidator.isSameDomain("https://example.com/page", "example.com"));
        assertTrue(UrlValidator.isSameDomain("https://ru.imgbb.com/abc123", "ru.imgbb.com"));
        assertFalse(UrlValidator.isSameDomain("https://example.com/page", "other.com"));
        assertFalse(UrlValidator.isSameDomain("not-a-url", "example.com"));
        assertFalse(UrlValidator.isSameDomain(null, "example.com"));
    }
}
