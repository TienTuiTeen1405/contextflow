package com.contextflow.common.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("contextflow.ai")
public record AiServiceProperties(@NotNull URI baseUrl) {}
