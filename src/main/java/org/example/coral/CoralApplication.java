package org.example.coral;

import org.example.coral.sync.GitHubProperties;
import org.example.coral.sync.GoogleProperties;
import org.example.coral.sync.NotionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({GitHubProperties.class, GoogleProperties.class, NotionProperties.class})
public class CoralApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoralApplication.class, args);
    }

}
