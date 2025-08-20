package com.parser.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Менеджер для управления повторными попытками выполнения операций
 */
public class RetryManager {
    private static final Logger logger = LoggerFactory.getLogger(RetryManager.class);
    
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double backoffMultiplier;
    private final Predicate<Exception> retryableException;
    
    public RetryManager(int maxAttempts, long initialDelayMs, long maxDelayMs, 
                       double backoffMultiplier, Predicate<Exception> retryableException) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.retryableException = retryableException;
    }
    
    /**
     * Выполняет операцию с повторными попытками
     */
    public <T> T execute(Callable<T> operation) throws Exception {
        Exception lastException = null;
        long currentDelay = initialDelayMs;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.call();
            } catch (Exception e) {
                lastException = e;
                
                if (attempt == maxAttempts) {
                    logger.error("Операция не удалась после {} попыток", maxAttempts, e);
                    throw e;
                }
                
                if (!retryableException.test(e)) {
                    logger.warn("Неповторяемая ошибка, прерываем попытки", e);
                    throw e;
                }
                
                logger.warn("Попытка {} не удалась, повтор через {} мс: {}", 
                    attempt, currentDelay, e.getMessage());
                
                try {
                    Thread.sleep(currentDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Операция прервана", ie);
                }
                
                currentDelay = Math.min(currentDelay * (long) backoffMultiplier, maxDelayMs);
            }
        }
        
        throw lastException;
    }
    
    /**
     * Выполняет операцию без возвращаемого значения
     */
    public void execute(Runnable operation) throws Exception {
        execute(() -> {
            operation.run();
            return null;
        });
    }
    
    /**
     * Создает RetryManager для сетевых операций
     */
    public static RetryManager forNetworkOperations() {
        return new RetryManager(
            3,                    // maxAttempts
            1000,                 // initialDelayMs
            10000,                // maxDelayMs
            2.0,                  // backoffMultiplier
            RetryManager::isRetryableNetworkException
        );
    }
    
    /**
     * Создает RetryManager для операций с базой данных
     */
    public static RetryManager forDatabaseOperations() {
        return new RetryManager(
            3,                    // maxAttempts
            500,                  // initialDelayMs
            5000,                 // maxDelayMs
            1.5,                  // backoffMultiplier
            RetryManager::isRetryableDatabaseException
        );
    }
    
    /**
     * Проверяет, является ли исключение повторяемым для сетевых операций
     */
    private static boolean isRetryableNetworkException(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        // Повторяемые сетевые ошибки
        return e instanceof java.net.SocketTimeoutException ||
               e instanceof java.net.ConnectException ||
               e instanceof java.net.NoRouteToHostException ||
               e instanceof java.net.UnknownHostException ||
               message.contains("timeout") ||
               message.contains("connection") ||
               message.contains("network") ||
               message.contains("temporary") ||
               message.contains("retry");
    }
    
    /**
     * Проверяет, является ли исключение повторяемым для операций с БД
     */
    private static boolean isRetryableDatabaseException(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        // Повторяемые ошибки БД
        return e instanceof java.sql.SQLTransientException ||
               e instanceof java.sql.SQLNonTransientConnectionException ||
               message.contains("connection") ||
               message.contains("timeout") ||
               message.contains("busy") ||
               message.contains("locked") ||
               message.contains("database is locked");
    }
    
    /**
     * Builder для создания RetryManager с кастомными настройками
     */
    public static class Builder {
        private int maxAttempts = 3;
        private long initialDelayMs = 1000;
        private long maxDelayMs = 10000;
        private double backoffMultiplier = 2.0;
        private Predicate<Exception> retryableException = e -> true;
        
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }
        
        public Builder initialDelay(long delay, TimeUnit unit) {
            this.initialDelayMs = unit.toMillis(delay);
            return this;
        }
        
        public Builder maxDelay(long delay, TimeUnit unit) {
            this.maxDelayMs = unit.toMillis(delay);
            return this;
        }
        
        public Builder backoffMultiplier(double multiplier) {
            this.backoffMultiplier = multiplier;
            return this;
        }
        
        public Builder retryableException(Predicate<Exception> predicate) {
            this.retryableException = predicate;
            return this;
        }
        
        public RetryManager build() {
            return new RetryManager(maxAttempts, initialDelayMs, maxDelayMs, 
                                  backoffMultiplier, retryableException);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
