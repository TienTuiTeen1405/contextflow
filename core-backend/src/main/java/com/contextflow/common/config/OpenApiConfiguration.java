package com.contextflow.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI contextFlowOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("ContextFlow Core API")
                                .version("0.1.0")
                                .description(
                                        "Modular backend APIs for ContextFlow. Generated directly from Spring controllers."));
    }
}
