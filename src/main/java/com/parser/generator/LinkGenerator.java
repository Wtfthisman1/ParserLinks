package com.parser.generator;

import com.parser.config.ParserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Генератор ссылок для различных фотохостингов
 */
public class LinkGenerator {
    private static final Logger logger = LoggerFactory.getLogger(LinkGenerator.class);
    private final Random random = new Random();
    
    /**
     * Генерация ссылок для указанного хостинга
     */
    public List<String> generateLinks(String hostingName, int count) {
        ParserConfig.HostingConfig hosting = ParserConfig.HOSTINGS.get(hostingName);
        if (hosting == null) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        return Stream.generate(() -> generateToken(hosting))
                .limit(count)
                .map(hosting::buildUrl)
                .collect(Collectors.toList());
    }
    
    /**
     * Умная генерация ссылок с различными паттернами
     */
    public List<String> generateSmartLinks(String hostingName, int count) {
        ParserConfig.HostingConfig hosting = ParserConfig.HOSTINGS.get(hostingName);
        if (hosting == null) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        List<String> links = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String token = generateSmartToken(hosting, i);
            links.add(hosting.buildUrl(token));
        }
        return links;
    }
    
    /**
     * Генерация ссылок по заданному паттерну
     */
    public List<String> generateFromPattern(String hostingName, String pattern, int count) {
        ParserConfig.HostingConfig hosting = ParserConfig.HOSTINGS.get(hostingName);
        if (hosting == null) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        List<String> links = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String token = pattern
                    .replace("{index}", String.valueOf(i))
                    .replace("{random}", generateRandomToken(hosting));
            
            // Обрезаем до нужной длины
            if (token.length() > hosting.getTokenLength()) {
                token = token.substring(0, hosting.getTokenLength());
            } else if (token.length() < hosting.getTokenLength()) {
                token = token + generateRandomToken(hosting).substring(0, 
                        hosting.getTokenLength() - token.length());
            }
            
            links.add(hosting.buildUrl(token));
        }
        return links;
    }
    
    private String generateToken(ParserConfig.HostingConfig hosting) {
        return generateRandomToken(hosting);
    }
    
    private String generateSmartToken(ParserConfig.HostingConfig hosting, int index) {
        // Выбираем случайную стратегию
        int strategy = random.nextInt(4);
        
        return switch (strategy) {
            case 0 -> generateRandomToken(hosting);
            case 1 -> generateTimestampToken(hosting, index);
            case 2 -> generateHashToken(hosting, index);
            case 3 -> generateSequentialToken(hosting, index);
            default -> generateRandomToken(hosting);
        };
    }
    
    private String generateRandomToken(ParserConfig.HostingConfig hosting) {
        StringBuilder token = new StringBuilder();
        String chars = hosting.getTokenChars();
        
        for (int i = 0; i < hosting.getTokenLength(); i++) {
            token.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return token.toString();
    }
    
    private String generateTimestampToken(ParserConfig.HostingConfig hosting, int index) {
        try {
            long timestamp = Instant.now().getEpochSecond() + index;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            String result = hexString.toString();
            
            // Фильтруем по разрешенным символам
            StringBuilder filteredResult = new StringBuilder();
            String allowedChars = hosting.getTokenChars();
            for (char c : result.toCharArray()) {
                if (allowedChars.indexOf(c) != -1) {
                    filteredResult.append(c);
                    if (filteredResult.length() >= hosting.getTokenLength()) {
                        break;
                    }
                }
            }
            
            // Если не хватает символов, дополняем случайными
            while (filteredResult.length() < hosting.getTokenLength()) {
                filteredResult.append(allowedChars.charAt(random.nextInt(allowedChars.length())));
            }
            
            return filteredResult.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.warn("MD5 не найден, используем случайную генерацию");
            return generateRandomToken(hosting);
        }
    }
    
    private String generateHashToken(ParserConfig.HostingConfig hosting, int index) {
        try {
            // Используем MD5 для более короткого хеша
            MessageDigest md = MessageDigest.getInstance("MD5");
            String input = String.valueOf(index) + System.currentTimeMillis();
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            String result = hexString.toString();
            
            // Фильтруем по разрешенным символам
            StringBuilder filteredResult = new StringBuilder();
            String allowedChars = hosting.getTokenChars();
            for (char c : result.toCharArray()) {
                if (allowedChars.indexOf(c) != -1) {
                    filteredResult.append(c);
                    if (filteredResult.length() >= hosting.getTokenLength()) {
                        break;
                    }
                }
            }
            
            // Если не хватает символов, дополняем случайными
            while (filteredResult.length() < hosting.getTokenLength()) {
                filteredResult.append(allowedChars.charAt(random.nextInt(allowedChars.length())));
            }
            
            return filteredResult.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.warn("MD5 не найден, используем случайную генерацию");
            return generateRandomToken(hosting);
        }
    }
    
    private String generateSequentialToken(ParserConfig.HostingConfig hosting, int index) {
        String base = String.valueOf(index);
        if (base.length() < hosting.getTokenLength()) {
            // Дополняем случайными символами
            StringBuilder token = new StringBuilder(base);
            String chars = hosting.getTokenChars();
            
            while (token.length() < hosting.getTokenLength()) {
                token.append(chars.charAt(random.nextInt(chars.length())));
            }
            return token.toString();
        } else {
            return base.substring(0, hosting.getTokenLength());
        }
    }
    
    /**
     * Генерация ссылок на основе реальных паттернов
     */
    public List<String> generateRealisticLinks(String hostingName, int count) {
        ParserConfig.HostingConfig hosting = ParserConfig.HOSTINGS.get(hostingName);
        if (hosting == null) {
            throw new IllegalArgumentException("Неподдерживаемый хостинг: " + hostingName);
        }
        
        List<String> links = new ArrayList<>();
        
        // Для imgbb используем более короткие токены (обычно 7 символов)
        if ("imgbb".equals(hostingName)) {
            for (int i = 0; i < count; i++) {
                String token = generateShortToken(hosting, i);
                links.add(hosting.buildUrl(token));
            }
        }
        // Для postimages используем более длинные токены (обычно 8 символов)
        else if ("postimages".equals(hostingName)) {
            for (int i = 0; i < count; i++) {
                String token = generateLongToken(hosting, i);
                links.add(hosting.buildUrl(token));
            }
        }
        
        return links;
    }
    
    private String generateShortToken(ParserConfig.HostingConfig hosting, int index) {
        // Используем комбинацию цифр и букв в более коротком формате
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder token = new StringBuilder();
        
        // Начинаем с цифр для более реалистичных ссылок
        if (index < 1000) {
            token.append(String.format("%03d", index));
        } else {
            token.append(String.valueOf(index % 1000));
        }
        
        // Дополняем случайными символами
        while (token.length() < hosting.getTokenLength()) {
            token.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return token.toString();
    }
    
    private String generateLongToken(ParserConfig.HostingConfig hosting, int index) {
        // Для postimages используем более длинные токены с хешированием
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String input = "postimages_" + index + "_" + System.currentTimeMillis();
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            String result = hexString.toString();
            
            // Фильтруем по разрешенным символам
            StringBuilder filteredResult = new StringBuilder();
            String allowedChars = hosting.getTokenChars();
            for (char c : result.toCharArray()) {
                if (allowedChars.indexOf(c) != -1) {
                    filteredResult.append(c);
                    if (filteredResult.length() >= hosting.getTokenLength()) {
                        break;
                    }
                }
            }
            
            // Если не хватает символов, дополняем случайными
            while (filteredResult.length() < hosting.getTokenLength()) {
                filteredResult.append(allowedChars.charAt(random.nextInt(allowedChars.length())));
            }
            
            return filteredResult.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.warn("MD5 не найден, используем случайную генерацию");
            return generateRandomToken(hosting);
        }
    }
    
    /**
     * Генерация ссылок для конкретного хостинга с учетом его особенностей
     */
    public List<String> generateHostingSpecificLinks(String hostingName, int count) {
        return switch (hostingName) {
            case "imgbb" -> generateImgbbLinks(count);
            case "postimages" -> generatePostimagesLinks(count);
            default -> generateSmartLinks(hostingName, count);
        };
    }
    
    private List<String> generateImgbbLinks(int count) {
        // ImgBB использует короткие токены (7 символов, alphanumeric)
        return Stream.generate(() -> {
            String token = generateRandomToken(ParserConfig.HOSTINGS.get("imgbb"));
            return ParserConfig.HOSTINGS.get("imgbb").buildUrl(token);
        }).limit(count).collect(Collectors.toList());
    }
    
    private List<String> generatePostimagesLinks(int count) {
        // PostImages использует короткие токены (8 символов, alphanumeric)
        return Stream.generate(() -> {
            String token = generateRandomToken(ParserConfig.HOSTINGS.get("postimages"));
            return ParserConfig.HOSTINGS.get("postimages").buildUrl(token);
        }).limit(count).collect(Collectors.toList());
    }
}
