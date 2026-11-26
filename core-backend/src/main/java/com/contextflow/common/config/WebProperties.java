package com.contextflow.common.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("contextflow.web")
public class WebProperties {
    @NotEmpty private List<String> allowedOrigins;
}
