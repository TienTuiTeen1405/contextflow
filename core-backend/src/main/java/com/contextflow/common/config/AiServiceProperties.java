package com.contextflow.common.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("contextflow.ai")
public class AiServiceProperties {
    @NotNull private URI baseUrl;
}
