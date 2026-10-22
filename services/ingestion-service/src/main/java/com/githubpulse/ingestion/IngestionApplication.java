package com.githubpulse.ingestion;

import com.githubpulse.ingestion.config.IngestionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }
}
