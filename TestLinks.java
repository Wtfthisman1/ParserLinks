import okhttp3.*;
import java.util.concurrent.TimeUnit;

public class TestLinks {
    public static void main(String[] args) {
        String[] urls = {
            "https://gekkk.co/i/c72403bd2e3557296ce268a0a06e75f7",
            "https://gekkk.co/i/0da63862bed5f47dd3fa84fb21b6084",
            "https://gekkk.co/i/e4a57f8206a5b2a046644193caa7b672",
            "https://gekkk.co/i/f09f106afbf54c11654089225045391d",
            "https://gekkk.co/i/0d9eb7bcef0b4279dddbf71c24d40851"
        };
        
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        
        for (String url : urls) {
            System.out.println("\n=== Проверка: " + url + " ===");
            
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .head()
                        .build();
                
                try (Response response = client.newCall(request).execute()) {
                    System.out.println("Код ответа: " + response.code());
                    System.out.println("Content-Type: " + response.header("content-type"));
                    System.out.println("Content-Length: " + response.header("content-length"));
                    System.out.println("Last-Modified: " + response.header("last-modified"));
                    System.out.println("Date: " + response.header("date"));
                    
                    if (response.isSuccessful()) {
                        String contentType = response.header("content-type");
                        if (contentType != null && contentType.startsWith("image/")) {
                            System.out.println("✅ Изображение найдено!");
                        } else {
                            System.out.println("❌ Не изображение или нет Content-Type");
                        }
                    } else {
                        System.out.println("❌ HTTP ошибка: " + response.code());
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ Ошибка: " + e.getMessage());
            }
        }
        
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
