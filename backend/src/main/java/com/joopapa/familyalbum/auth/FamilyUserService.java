package com.joopapa.familyalbum.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FamilyUserService {
    private final FamilyUserRepository familyUserRepository;

    public FamilyUserService(FamilyUserRepository familyUserRepository) {
        this.familyUserRepository = familyUserRepository;
    }

    @Transactional(readOnly = true)
    public AuthDtos.MeResponse currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof OAuth2User oauthUser)) {
            return AuthDtos.MeResponse.anonymous();
        }
        String kakaoId = firstText(oauthUser.getAttribute("kakaoId"), oauthUser.getAttribute("id"));
        if (kakaoId == null) {
            return AuthDtos.MeResponse.anonymous();
        }
        return familyUserRepository.findByKakaoId(kakaoId)
                .map(AuthDtos.MeResponse::from)
                .orElseGet(AuthDtos.MeResponse::anonymous);
    }
    private static String firstText(Object first, Object second) {
        if (first instanceof String text && !text.isBlank()) return text;
        if (second instanceof String text && !text.isBlank()) return text;
        if (first instanceof Number number) return String.valueOf(number);
        if (second instanceof Number number) return String.valueOf(number);
        return null;
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserListResponse listUsers() {
        List<AuthDtos.UserResponse> users = familyUserRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(AuthDtos.UserResponse::from)
                .toList();
        return new AuthDtos.UserListResponse(users);
    }

    @Transactional
    public AuthDtos.UserResponse updateRole(UUID userId, FamilyUserRole role) {
        FamilyUser user = familyUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        user.grantRole(role);
        return AuthDtos.UserResponse.from(user);
    }
}