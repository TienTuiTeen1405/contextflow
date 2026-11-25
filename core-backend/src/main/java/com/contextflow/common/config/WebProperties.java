package com.contextflow.common.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("contextflow.web")
public record WebProperties(@NotEmpty List<String> allowedOrigins) {}
