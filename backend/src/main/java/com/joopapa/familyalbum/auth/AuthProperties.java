package com.joopapa.familyalbum.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(List<String> adminKakaoIds) {
    public boolean isBootstrapAdmin(String kakaoId) {
        return adminKakaoIds != null && adminKakaoIds.stream()
                .map(String::trim)
                .anyMatch(kakaoId::equals);
    }
}