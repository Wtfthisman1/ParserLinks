package com.parser.model;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Результат обработки ссылки
 */
public class LinkResult {
    private final String url;
    private final String hosting;
    private final String status; // 'empty', 'downloaded', 'skipped', 'error'
    private final Path filePath;
    private final Long fileSize;
    private final Integer imageAgeDays;
    private final String errorMessage;
    private final LocalDateTime processedAt;
    
    public LinkResult(String url, String hosting, String status, Path filePath, 
                     Long fileSize, Integer imageAgeDays, String errorMessage, 
                     LocalDateTime processedAt) {
        this.url = url;
        this.hosting = hosting;
        this.status = status;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.imageAgeDays = imageAgeDays;
        this.errorMessage = errorMessage;
        this.processedAt = processedAt;
    }
    
    // Геттеры
    public String getUrl() { return url; }
    public String getHosting() { return hosting; }
    public String getStatus() { return status; }
    public Path getFilePath() { return filePath; }
    public Long getFileSize() { return fileSize; }
    public Integer getImageAgeDays() { return imageAgeDays; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    
    // Билдер для удобного создания объектов
    public static class Builder {
        private String url;
        private String hosting;
        private String status;
        private Path filePath;
        private Long fileSize;
        private Integer imageAgeDays;
        private String errorMessage;
        private LocalDateTime processedAt = LocalDateTime.now();
        
        public Builder url(String url) {
            this.url = url;
            return this;
        }
        
        public Builder hosting(String hosting) {
            this.hosting = hosting;
            return this;
        }
        
        public Builder status(String status) {
            this.status = status;
            return this;
        }
        
        public Builder filePath(Path filePath) {
            this.filePath = filePath;
            return this;
        }
        
        public Builder fileSize(Long fileSize) {
            this.fileSize = fileSize;
            return this;
        }
        
        public Builder imageAgeDays(Integer imageAgeDays) {
            this.imageAgeDays = imageAgeDays;
            return this;
        }
        
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }
        
        public Builder processedAt(LocalDateTime processedAt) {
            this.processedAt = processedAt;
            return this;
        }
        
        public LinkResult build() {
            return new LinkResult(url, hosting, status, filePath, fileSize, 
                                imageAgeDays, errorMessage, processedAt);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public String toString() {
        return String.format("LinkResult{url='%s', hosting='%s', status='%s', filePath=%s, ageDays=%d}",
                           url, hosting, status, filePath, imageAgeDays);
    }
}
