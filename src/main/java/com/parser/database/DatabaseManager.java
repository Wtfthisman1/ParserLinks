package com.parser.database;

import com.parser.config.ParserConfig;
import com.parser.model.LinkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Менеджер базы данных SQLite
 */
public class DatabaseManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    
    private final Connection connection;
    private final Path dbPath;
    
    public DatabaseManager() throws SQLException {
        this.dbPath = ParserConfig.DB_PATH;
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initializeDatabase();
    }
    
    private void initializeDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Таблица для результатов обработки ссылок
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS link_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    url TEXT UNIQUE NOT NULL,
                    hosting TEXT NOT NULL,
                    status TEXT NOT NULL,
                    file_path TEXT,
                    file_size INTEGER,
                    image_age_days INTEGER,
                    error_message TEXT,
                    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            // Таблица для статистики
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS statistics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    hosting TEXT NOT NULL,
                    total_processed INTEGER DEFAULT 0,
                    empty_links INTEGER DEFAULT 0,
                    downloaded_images INTEGER DEFAULT 0,
                    skipped_images INTEGER DEFAULT 0,
                    errors INTEGER DEFAULT 0,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            // Индексы для ускорения запросов
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_url ON link_results(url)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_status ON link_results(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hosting ON link_results(hosting)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_processed_at ON link_results(processed_at)");
        }
    }
    
    public void saveResult(LinkResult result) {
        String sql = """
            INSERT OR REPLACE INTO link_results 
            (url, hosting, status, file_path, file_size, image_age_days, error_message, processed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, result.getUrl());
            pstmt.setString(2, result.getHosting());
            pstmt.setString(3, result.getStatus());
            pstmt.setString(4, result.getFilePath() != null ? result.getFilePath().toString() : null);
            pstmt.setLong(5, result.getFileSize() != null ? result.getFileSize() : 0);
            pstmt.setInt(6, result.getImageAgeDays() != null ? result.getImageAgeDays() : 0);
            pstmt.setString(7, result.getErrorMessage());
            pstmt.setTimestamp(8, Timestamp.valueOf(result.getProcessedAt()));
            
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Ошибка сохранения результата: {}", e.getMessage());
        }
    }
    
    public boolean isUrlProcessed(String url) {
        String sql = "SELECT 1 FROM link_results WHERE url = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, url);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Ошибка проверки URL: {}", e.getMessage());
            return false;
        }
    }
    
    public Map<String, Map<String, Integer>> getStatistics() {
        Map<String, Map<String, Integer>> stats = new HashMap<>();
        String sql = """
            SELECT 
                hosting,
                COUNT(*) as total,
                SUM(CASE WHEN status = 'empty' THEN 1 ELSE 0 END) as empty,
                SUM(CASE WHEN status = 'downloaded' THEN 1 ELSE 0 END) as downloaded,
                SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) as skipped,
                SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) as errors
            FROM link_results 
            GROUP BY hosting
        """;
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String hosting = rs.getString("hosting");
                Map<String, Integer> hostingStats = new HashMap<>();
                hostingStats.put("total", rs.getInt("total"));
                hostingStats.put("empty", rs.getInt("empty"));
                hostingStats.put("downloaded", rs.getInt("downloaded"));
                hostingStats.put("skipped", rs.getInt("skipped"));
                hostingStats.put("errors", rs.getInt("errors"));
                
                stats.put(hosting, hostingStats);
            }
        } catch (SQLException e) {
            logger.error("Ошибка получения статистики: {}", e.getMessage());
        }
        
        return stats;
    }
    
    public List<LinkResult> getRecentResults(int limit) {
        List<LinkResult> results = new ArrayList<>();
        String sql = """
            SELECT url, hosting, status, file_path, file_size, 
                   image_age_days, error_message, processed_at
            FROM link_results 
            ORDER BY processed_at DESC 
            LIMIT ?
        """;
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    LinkResult result = LinkResult.builder()
                        .url(rs.getString("url"))
                        .hosting(rs.getString("hosting"))
                        .status(rs.getString("status"))
                        .filePath(rs.getString("file_path") != null ? 
                                Path.of(rs.getString("file_path")) : null)
                        .fileSize(rs.getLong("file_size"))
                        .imageAgeDays(rs.getInt("image_age_days"))
                        .errorMessage(rs.getString("error_message"))
                        .processedAt(rs.getTimestamp("processed_at").toLocalDateTime())
                        .build();
                    
                    results.add(result);
                }
            }
        } catch (SQLException e) {
            logger.error("Ошибка получения последних результатов: {}", e.getMessage());
        }
        
        return results;
    }
    
    public void cleanupOldRecords(int days) {
        String sql = "DELETE FROM link_results WHERE processed_at < datetime('now', '-' || ? || ' days')";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, days);
            int deletedCount = pstmt.executeUpdate();
            logger.info("Удалено {} старых записей", deletedCount);
        } catch (SQLException e) {
            logger.error("Ошибка очистки старых записей: {}", e.getMessage());
        }
    }
    
    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
