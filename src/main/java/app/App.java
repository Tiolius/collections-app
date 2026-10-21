package app;

import java.io.File;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        // Папка для базы должна существовать до подключения к SQLite
        String dataDir = System.getenv().getOrDefault("DATA_DIR", "/data");
        new File(dataDir).mkdirs();
        SpringApplication.run(App.class, args);
    }
}
