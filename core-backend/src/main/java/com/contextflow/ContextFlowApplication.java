package com.contextflow;

import com.contextflow.common.config.AiServiceProperties;
import com.contextflow.common.config.WebProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({AiServiceProperties.class, WebProperties.class})
public class ContextFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContextFlowApplication.class, args);
    }
}
