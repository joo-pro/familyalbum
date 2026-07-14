package com.joopapa.familyalbum.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FamilyOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final FamilyUserRepository familyUserRepository;
    private final AuthProperties authProperties;

    public FamilyOAuth2UserService(FamilyUserRepository familyUserRepository, AuthProperties authProperties) {
        this.familyUserRepository = familyUserRepository;
        this.authProperties = authProperties;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauthUser = delegate.loadUser(userRequest);
        Map<String, Object> attributes = new HashMap<>(oauthUser.getAttributes());
        String kakaoId = String.valueOf(attributes.get("id"));
        KakaoProfile profile = extractProfile(attributes);

        FamilyUser user = familyUserRepository.findByKakaoId(kakaoId)
                .orElseGet(() -> new FamilyUser(kakaoId, profile.nickname(), profile.profileImageUrl()));
        user.refreshProfile(profile.nickname(), profile.profileImageUrl());
        if (authProperties.isBootstrapAdmin(kakaoId) && user.getRole() != FamilyUserRole.FATHER) {
            user.grantRole(FamilyUserRole.FATHER);
        }
        familyUserRepository.save(user);

        attributes.put("familyUserId", user.getId().toString());
        attributes.put("kakaoId", user.getKakaoId());
        attributes.put("nickname", user.getNickname());
        attributes.put("profileImageUrl", user.getProfileImageUrl());
        attributes.put("role", user.getRole().name());
        attributes.put("approved", user.isApproved());

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                attributes,
                "id"
        );
    }

    @SuppressWarnings("unchecked")
    private static KakaoProfile extractProfile(Map<String, Object> attributes) {
        Map<String, Object> properties = valueAsMap(attributes.get("properties"));
        Map<String, Object> kakaoAccount = valueAsMap(attributes.get("kakao_account"));
        Map<String, Object> profile = valueAsMap(kakaoAccount.get("profile"));

        String nickname = firstText(properties.get("nickname"), profile.get("nickname"), "가족");
        String profileImageUrl = firstText(properties.get("profile_image"), profile.get("profile_image_url"), null);
        return new KakaoProfile(nickname, profileImageUrl);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> valueAsMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String firstText(Object first, Object second, String fallback) {
        if (first instanceof String text && !text.isBlank()) return text;
        if (second instanceof String text && !text.isBlank()) return text;
        return fallback;
    }

    private record KakaoProfile(String nickname, String profileImageUrl) {
    }
}