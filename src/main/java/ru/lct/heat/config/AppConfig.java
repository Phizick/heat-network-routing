package ru.lct.heat.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AppConfig {

    /** Пул фоновой обработки загрузок: запрос возвращает id сразу, разбор идёт здесь. */
    @Bean
    public ThreadPoolTaskExecutor ingestExecutor(HeatProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getIngest().getWorkers());
        executor.setMaxPoolSize(props.getIngest().getWorkers());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-");
        executor.initialize();
        return executor;
    }

    /** Пул расчётов: ограничивает одновременную нагрузку (до 50 пользователей на 16 ГБ). */
    @Bean
    public ThreadPoolTaskExecutor solveExecutor(HeatProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getSolver().getWorkers());
        executor.setMaxPoolSize(props.getSolver().getWorkers());
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("solve-");
        executor.initialize();
        return executor;
    }

    /** Каталоги временного диска: multipart-буфер Tomcat и принятые файлы. */
    @Bean
    public Path uploadDir(HeatProperties props) throws IOException {
        Path root = Paths.get(props.getIngest().getTempDir());
        Files.createDirectories(root.resolve("multipart"));
        return Files.createDirectories(root.resolve("uploads"));
    }
}
