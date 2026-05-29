package org.example.coral;

import org.example.coral.sync.GitHubProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(GitHubProperties.class)
public class CoralApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoralApplication.class, args);
    }

}
