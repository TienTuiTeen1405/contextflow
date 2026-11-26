package com.contextflow.identity.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("demo")
public class DemoJsonConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictDemoJson() {
        return builder -> {
            builder.featuresToDisable(
                    MapperFeature.ALLOW_COERCION_OF_SCALARS,
                    DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            builder.featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
            builder.postConfigurer(mapper -> {
                var text = mapper.coercionConfigFor(LogicalType.Textual);
                for (var shape :
                        new CoercionInputShape[] {
                            CoercionInputShape.Integer,
                            CoercionInputShape.Float,
                            CoercionInputShape.Boolean
                        }) text.setCoercion(shape, CoercionAction.Fail);
            });
        };
    }
}
