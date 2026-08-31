package com.weavr.api.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "weavr.notifications")
public record NotificationProperties(
        boolean enabled,
        String expoPushUrl,
        Duration timeout
) {
    public NotificationProperties {
        if (expoPushUrl == null || expoPushUrl.isBlank()) {
            expoPushUrl = "https://exp.host/--/api/v2/push/send";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }
}
