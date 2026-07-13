package com.joopapa.familyalbum.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.push")
public record PushProperties(
        String vapidPublicKey,
        String vapidPrivateKey,
        String subject
) {
    public boolean enabled() {
        return StringUtils.hasText(vapidPublicKey) && StringUtils.hasText(vapidPrivateKey);
    }
}