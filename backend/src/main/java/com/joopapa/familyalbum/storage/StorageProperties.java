package com.joopapa.familyalbum.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String provider,
        String region,
        String bucket,
        String endpoint,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl
) {
}
