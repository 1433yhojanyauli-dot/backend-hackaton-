package com.goeFast.GeoFastPe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(
        String baseUrl,
        String apiKey,
        String model
) {
}
