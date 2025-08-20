package com.parser.processor;

import com.parser.config.ParserConfig;
import com.parser.model.LinkResult;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.channel.ChannelFuture;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Высокопроизводительный процессор изображений на основе Netty
 * Реализует стриминговый парсинг и максимальный параллелизм
 */
public class NettyImageProcessor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NettyImageProcessor.class);
    
    // Конфигурация производительности
    private static final int MAX_CONCURRENT_STREAMS = 100;  // Уменьшено для стабильности
    private static final int CONNECTION_POOL_SIZE = 10;     // Уменьшено для стабильности
    private static final int READ_TIMEOUT_SECONDS = 15;     // Увеличено для стабильности
    private static final int WRITE_TIMEOUT_SECONDS = 10;    // Увеличено для стабильности
    private static final int CONNECT_TIMEOUT_MS = 10000;    // Увеличено для стабильности
    private static final int MAX_CHUNK_SIZE = 8192; // 8KB чанки для стриминга
    
    // EventLoop группы для максимальной производительности
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    
    // Пул соединений по хостам
    private final Map<String, ConnectionPool> connectionPools;
    
    // Статистика производительности
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final AtomicLong earlyTerminations = new AtomicLong(0);
    
    // Пул User-Agent'ов для ротации
    private final String[] userAgents = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    };
    
    // Паттерны для быстрого поиска изображений в HTML
    private static final Pattern IMG_PATTERN = Pattern.compile(
        "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", 
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );
    
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
        "(?:https?:)?//[^\\s\"'<>]+?\\.(?:jpg|jpeg|png|gif|webp|bmp|svg)(?:\\?[^\\s\"'<>]*)?",
        Pattern.CASE_INSENSITIVE
    );
    
    public NettyImageProcessor() {
        // Создаем EventLoop группы с оптимизированными настройками
        int cpuCores = Runtime.getRuntime().availableProcessors();
        this.bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("netty-boss", true));
        this.workerGroup = new NioEventLoopGroup(cpuCores * 2, new DefaultThreadFactory("netty-worker", true));
        
        this.connectionPools = new ConcurrentHashMap<>();
        
        logger.info("🚀 NettyImageProcessor инициализирован с {} ядер CPU", cpuCores);
    }
    
    /**
     * Обработка URL изображения с максимальной производительностью
     */
    public CompletableFuture<LinkResult> processImageUrlAsync(String url, String hosting) {
        totalRequests.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("🎯 Обрабатываю: {}", url);
                
                // Парсим URL
                URI uri = URI.create(url);
                String host = uri.getHost();
                int port = uri.getPort() != -1 ? uri.getPort() : (uri.getScheme().equals("https") ? 443 : 80);
                boolean ssl = uri.getScheme().equals("https");
                
                // Получаем или создаем пул соединений для хоста
                ConnectionPool pool = connectionPools.computeIfAbsent(host, 
                    h -> new ConnectionPool(host, port, ssl, CONNECTION_POOL_SIZE));
                
                // Выполняем стриминговый GET запрос
                ImageMetadata metadata = performStreamingGet(pool, uri, hosting);
                
                if (metadata == null) {
                    failedRequests.incrementAndGet();
                    return LinkResult.builder()
                            .url(url)
                            .hosting(hosting)
                            .status("empty")
                            .build();
                }
                
                successfulRequests.incrementAndGet();
                
                // Проверяем возраст изображения
                Integer ageDays = checkImageAge(metadata);
                
                // Определяем, нужно ли скачивать
                if (!shouldDownloadImage(ageDays)) {
                    return LinkResult.builder()
                            .url(url)
                            .hosting(hosting)
                            .status("skipped")
                            .imageAgeDays(ageDays)
                            .build();
                }
                
                // Скачиваем изображение
                Path filePath = downloadImage(url, hosting, metadata);
                
                if (filePath != null) {
                    return LinkResult.builder()
                            .url(url)
                            .hosting(hosting)
                            .status("downloaded")
                            .filePath(filePath)
                            .fileSize(metadata.getFileSize())
                            .imageAgeDays(ageDays)
                            .build();
                } else {
                    return LinkResult.builder()
                            .url(url)
                            .hosting(hosting)
                            .status("error")
                            .imageAgeDays(ageDays)
                            .errorMessage("Ошибка скачивания")
                            .build();
                }
                
            } catch (Exception e) {
                failedRequests.incrementAndGet();
                logger.error("💥 Ошибка обработки {}: {}", url, e.getMessage());
                return LinkResult.builder()
                        .url(url)
                        .hosting(hosting)
                        .status("error")
                        .errorMessage(e.getMessage())
                        .build();
            }
        });
    }
    
    /**
     * Выполняет стриминговый GET запрос с ранним прерыванием
     */
    private ImageMetadata performStreamingGet(ConnectionPool pool, URI uri, String hosting) {
        try {
            // Создаем промис для результата
            Promise<ImageMetadata> promise = pool.getEventLoop().newPromise();
            
            // Получаем соединение из пула
            Channel channel = pool.acquireConnection();
            
            if (channel == null) {
                logger.warn("⚠️ Не удалось получить соединение для {}", uri);
                return null;
            }
            
            try {
                // Создаем HTTP запрос
                HttpRequest request = createOptimizedRequest(uri);
                
                // Создаем обработчик для стримингового парсинга
                StreamingImageHandler handler = new StreamingImageHandler(promise, hosting);
                
                // Добавляем обработчик в pipeline
                channel.pipeline().addLast(handler);
                
                // Отправляем запрос
                channel.writeAndFlush(request).addListener(future -> {
                    if (!future.isSuccess()) {
                        promise.setFailure(future.cause());
                    }
                });
                
                // Ждем результат с таймаутом
                if (!promise.await(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    promise.setFailure(new TimeoutException("Timeout waiting for response"));
                }
                
                return promise.get();
                
            } finally {
                // Возвращаем соединение в пул
                pool.releaseConnection(channel);
            }
            
        } catch (Exception e) {
            logger.error("💥 Ошибка стримингового GET: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Создает оптимизированный HTTP запрос
     */
    private HttpRequest createOptimizedRequest(URI uri) {
        String userAgent = userAgents[Thread.currentThread().hashCode() % userAgents.length];
        
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            uri.getPath() + (uri.getQuery() != null ? "?" + uri.getQuery() : ""),
            PooledByteBufAllocator.DEFAULT.buffer(0)
        );
        
        // Добавляем необходимые заголовки
        request.headers().set(HttpHeaderNames.HOST, uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : ""));
        request.headers().set(HttpHeaderNames.USER_AGENT, userAgent);
        request.headers().set(HttpHeaderNames.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8");
        request.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "en-US,en;q=0.9,ru;q=0.8");
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate, br");
        request.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        request.headers().set(HttpHeaderNames.UPGRADE_INSECURE_REQUESTS, "1");
        
        return request;
    }
    
    /**
     * Проверка возраста изображения
     */
    private Integer checkImageAge(ImageMetadata metadata) {
        try {
            String lastModified = metadata.getLastModified();
            if (lastModified != null) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
                    LocalDateTime dt = LocalDateTime.parse(lastModified, formatter);
                    return (int) java.time.Duration.between(dt, LocalDateTime.now()).toDays();
                } catch (Exception e) {
                    // Игнорируем ошибки парсинга
                }
            }
            
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
            
            return 0;
            
        } catch (Exception e) {
            logger.debug("Ошибка определения возраста: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Определяет, нужно ли скачивать изображение
     */
    private boolean shouldDownloadImage(Integer ageDays) {
        if (ageDays == null) {
            return true;
        }
        return ageDays >= ParserConfig.MIN_IMAGE_AGE_DAYS;
    }
    
    /**
     * Скачивание изображения
     */
    private Path downloadImage(String url, String hosting, ImageMetadata metadata) {
        try {
            String downloadUrl = metadata.getImageUrl() != null ? metadata.getImageUrl() : url;
            logger.debug("⬇️ Скачиваю: {}", downloadUrl);
            
            // Определяем расширение файла
            String extension = getExtensionFromContentType(metadata.getContentType());
            if (extension == null) {
                extension = getExtensionFromUrl(downloadUrl);
                if (extension == null) {
                    extension = ".jpg";
                }
            }
            
            // Генерируем имя файла
            String filename = generateFilename(url, hosting, extension);
            Path filepath = ParserConfig.DOWNLOAD_DIR.resolve(filename);
            
            // Создаем директорию если не существует
            Files.createDirectories(ParserConfig.DOWNLOAD_DIR);
            
            // Используем curl для скачивания (совместимость с существующим кодом)
            String curlCommand = String.format(
                "curl -s -L \"%s\" -H \"User-Agent: %s\" " +
                "-H \"Accept: */*\" -H \"Referer: %s\" -o \"%s\"",
                downloadUrl, 
                userAgents[0],
                getReferer(hosting),
                filepath.toString()
            );
            
            Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", curlCommand});
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                logger.debug("✅ Скачан: {}", filepath);
                return filepath;
            } else {
                logger.error("❌ Ошибка curl (код {}): {}", exitCode, curlCommand);
                return null;
            }
            
        } catch (Exception e) {
            logger.error("Ошибка скачивания {}: {}", url, e.getMessage());
            return null;
        }
    }
    
    private String getReferer(String hosting) {
        return switch (hosting) {
            case "postimages" -> "https://postimg.cc/";
            case "imgbb" -> "https://ru.imgbb.com/";
            default -> "https://example.com/";
        };
    }
    
    private String getExtensionFromContentType(String contentType) {
        if (contentType == null) return null;
        
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "image/tiff" -> ".tiff";
            case "image/svg+xml" -> ".svg";
            default -> null;
        };
    }
    
    private String getExtensionFromUrl(String url) {
        String path = url.toLowerCase();
        String[] extensions = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg"};
        
        for (String ext : extensions) {
            if (path.endsWith(ext)) {
                return ext;
            }
        }
        return null;
    }
    
    private String generateFilename(String url, String hosting, String extension) {
        String[] pathParts = url.split("/");
        String token = pathParts[pathParts.length - 1];
        String filename = hosting + "_" + token + extension;
        
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
    
    /**
     * Получение статистики производительности
     */
    public Map<String, Object> getPerformanceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequests", totalRequests.get());
        stats.put("successfulRequests", successfulRequests.get());
        stats.put("failedRequests", failedRequests.get());
        stats.put("bytesRead", bytesRead.get());
        stats.put("earlyTerminations", earlyTerminations.get());
        stats.put("connectionPools", connectionPools.size());
        
        double successRate = totalRequests.get() > 0 ? 
            (double) successfulRequests.get() / totalRequests.get() * 100 : 0;
        stats.put("successRate", String.format("%.2f%%", successRate));
        
        return stats;
    }
    
    @Override
    public void close() throws Exception {
        logger.info("🔄 Закрытие NettyImageProcessor...");
        
        // Закрываем все пулы соединений
        for (ConnectionPool pool : connectionPools.values()) {
            pool.close();
        }
        connectionPools.clear();
        
        // Закрываем EventLoop группы
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        
        logger.info("✅ NettyImageProcessor закрыт");
    }
    
    /**
     * Обработчик для стримингового парсинга изображений
     */
    private class StreamingImageHandler extends ChannelInboundHandlerAdapter {
        private final Promise<ImageMetadata> promise;
        private final String hosting;
        private final StringBuilder htmlBuffer;
        private final AtomicInteger bytesReceived;
        private boolean foundImage = false;
        private boolean isImageResponse = false;
        private String contentType;
        private Long contentLength;
        private String lastModified;
        private String date;
        
        public StreamingImageHandler(Promise<ImageMetadata> promise, String hosting) {
            this.promise = promise;
            this.hosting = hosting;
            this.htmlBuffer = new StringBuilder();
            this.bytesReceived = new AtomicInteger(0);
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpResponse) {
                handleHttpResponse(ctx, (HttpResponse) msg);
            } else if (msg instanceof HttpContent) {
                handleHttpContent(ctx, (HttpContent) msg);
            }
        }
        
        private void handleHttpResponse(ChannelHandlerContext ctx, HttpResponse response) {
            HttpHeaders headers = response.headers();
            
            // Проверяем статус код
            if (!response.status().equals(HttpResponseStatus.OK)) {
                if (!promise.isDone()) {
                    promise.setFailure(new RuntimeException("HTTP " + response.status().code()));
                }
                return;
            }
            
            // Получаем заголовки
            contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
            String contentLengthStr = headers.get(HttpHeaderNames.CONTENT_LENGTH);
            lastModified = headers.get(HttpHeaderNames.LAST_MODIFIED);
            date = headers.get(HttpHeaderNames.DATE);
            
            if (contentLengthStr != null) {
                try {
                    contentLength = Long.parseLong(contentLengthStr);
                } catch (NumberFormatException e) {
                    // Игнорируем
                }
            }
            
            // Проверяем, является ли ответ изображением
            if (contentType != null && contentType.startsWith("image/")) {
                isImageResponse = true;
                foundImage = true;
                
                // Сразу создаем метаданные для изображения
                ImageMetadata metadata = new ImageMetadata(
                    contentType, contentLength, lastModified, date, null
                );
                if (!promise.isDone()) {
                    promise.setSuccess(metadata);
                }
                
                // Прерываем чтение
                ctx.channel().config().setAutoRead(false);
                earlyTerminations.incrementAndGet();
            }
        }
        
        private void handleHttpContent(ChannelHandlerContext ctx, HttpContent content) {
            if (foundImage) {
                return; // Уже нашли изображение
            }
            
            ByteBuf buf = content.content();
            int readableBytes = buf.readableBytes();
            bytesReceived.addAndGet(readableBytes);
            bytesRead.addAndGet(readableBytes);
            
            // Читаем только первые 8KB для поиска изображений
            if (bytesReceived.get() > MAX_CHUNK_SIZE) {
                // Прерываем чтение после 8KB
                ctx.channel().config().setAutoRead(false);
                earlyTerminations.incrementAndGet();
                
                // Ищем изображения в накопленном HTML
                String html = htmlBuffer.toString();
                String imageUrl = findImageInHtml(html);
                
                if (imageUrl != null) {
                    ImageMetadata metadata = new ImageMetadata(
                        "text/html", null, lastModified, date, null, imageUrl
                    );
                    if (!promise.isDone()) {
                        promise.setSuccess(metadata);
                    }
                } else {
                    if (!promise.isDone()) {
                        promise.setFailure(new RuntimeException("No image found in HTML"));
                    }
                }
                return;
            }
            
            // Добавляем в буфер для парсинга
            String chunk = buf.toString(StandardCharsets.UTF_8);
            htmlBuffer.append(chunk);
            
            // Проверяем, не нашли ли мы уже изображение в этом чанке
            String imageUrl = findImageInHtml(chunk);
            if (imageUrl != null) {
                foundImage = true;
                
                ImageMetadata metadata = new ImageMetadata(
                    "text/html", null, lastModified, date, null, imageUrl
                );
                if (!promise.isDone()) {
                    promise.setSuccess(metadata);
                }
                
                // Прерываем чтение
                ctx.channel().config().setAutoRead(false);
                earlyTerminations.incrementAndGet();
            }
        }
        
        private String findImageInHtml(String html) {
            // Сначала ищем теги img
            Matcher imgMatcher = IMG_PATTERN.matcher(html);
            if (imgMatcher.find()) {
                String src = imgMatcher.group(1);
                if (src != null && !src.trim().isEmpty()) {
                    return src;
                }
            }
            
            // Затем ищем прямые ссылки на изображения
            Matcher urlMatcher = IMAGE_URL_PATTERN.matcher(html);
            if (urlMatcher.find()) {
                return urlMatcher.group();
            }
            
            return null;
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            promise.setFailure(cause);
            ctx.close();
        }
    }
    
    /**
     * Пул соединений для хоста
     */
    private class ConnectionPool {
        private final String host;
        private final int port;
        private final boolean ssl;
        private final int maxConnections;
        private final EventLoop eventLoop;
        private final Queue<Channel> availableConnections;
        private final AtomicInteger activeConnections;
        private final Bootstrap bootstrap;
        private volatile boolean closed = false;
        
        public ConnectionPool(String host, int port, boolean ssl, int maxConnections) {
            this.host = host;
            this.port = port;
            this.ssl = ssl;
            this.maxConnections = maxConnections;
            this.eventLoop = workerGroup.next();
            this.availableConnections = new ConcurrentLinkedQueue<>();
            this.activeConnections = new AtomicInteger(0);
            
            this.bootstrap = createBootstrap();
        }
        
        private Bootstrap createBootstrap() {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(eventLoop)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_RCVBUF, 65536)
                    .option(ChannelOption.SO_SNDBUF, 65536)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
            
            if (ssl) {
                try {
                    SslContext sslContext = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build();
                                         bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                         @Override
                         protected void initChannel(SocketChannel ch) throws Exception {
                             ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
                             ch.pipeline().addLast(new HttpClientCodec());
                             ch.pipeline().addLast(new HttpContentDecompressor()); // Поддержка gzip
                             ch.pipeline().addLast(new HttpObjectAggregator(65536));
                             ch.pipeline().addLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));
                             ch.pipeline().addLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS));
                         }
                     });
                } catch (Exception e) {
                    logger.error("Ошибка создания SSL контекста: {}", e.getMessage());
                }
            } else {
                                 bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     protected void initChannel(SocketChannel ch) throws Exception {
                         ch.pipeline().addLast(new HttpClientCodec());
                         ch.pipeline().addLast(new HttpContentDecompressor()); // Поддержка gzip
                         ch.pipeline().addLast(new HttpObjectAggregator(65536));
                         ch.pipeline().addLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));
                         ch.pipeline().addLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS));
                     }
                 });
            }
            
            return bootstrap;
        }
        
        public Channel acquireConnection() {
            if (closed) {
                return null;
            }
            
            // Пробуем взять существующее соединение
            Channel channel = availableConnections.poll();
            if (channel != null && channel.isActive()) {
                return channel;
            }
            
            // Создаем новое соединение если не превышен лимит
            if (activeConnections.get() < maxConnections) {
                try {
                    ChannelFuture future = bootstrap.connect(host, port);
                    if (future.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        if (future.isSuccess()) {
                            Channel newChannel = future.channel();
                            activeConnections.incrementAndGet();
                            return newChannel;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Ошибка создания соединения к {}:{}: {}", host, port, e.getMessage());
                }
            }
            
            return null;
        }
        
        public void releaseConnection(Channel channel) {
            if (channel == null || closed) {
                return;
            }
            
            if (channel.isActive()) {
                availableConnections.offer(channel);
            } else {
                activeConnections.decrementAndGet();
            }
        }
        
        public EventLoop getEventLoop() {
            return eventLoop;
        }
        
        public void close() {
            closed = true;
            
            // Закрываем все соединения
            Channel channel;
            while ((channel = availableConnections.poll()) != null) {
                channel.close();
                activeConnections.decrementAndGet();
            }
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
        private final Map<String, List<String>> headers;
        private final String imageUrl;
        
        public ImageMetadata(String contentType, Long fileSize, String lastModified, 
                           String date, Map<String, List<String>> headers) {
            this(contentType, fileSize, lastModified, date, headers, null);
        }
        
        public ImageMetadata(String contentType, Long fileSize, String lastModified, 
                           String date, Map<String, List<String>> headers, String imageUrl) {
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
        public Map<String, List<String>> getHeaders() { return headers; }
        public String getImageUrl() { return imageUrl; }
    }
}
