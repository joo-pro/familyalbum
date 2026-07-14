package com.joopapa.familyalbum.config;

import com.joopapa.familyalbum.auth.FamilyOAuth2UserService;
import com.joopapa.familyalbum.auth.FamilyUserRepository;
import com.joopapa.familyalbum.auth.FamilyUserRole;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, FamilyOAuth2UserService familyOAuth2UserService, FamilyUserRepository familyUserRepository) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/health",
                                "/api/auth/me",
                                "/api/admin/media/backfill-web-assets",
                                "/api/push/public-key",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/app-config.json",
                                "/favicon.ico",
                                "/icon.png",
                                "/manifest.webmanifest",
                                "/sw.js"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/media/upload", "/api/media/upload-url", "/api/media/upload-complete", "/api/media/visibility").access(hasCurrentRole(familyUserRepository, FamilyUserRole.MOTHER, FamilyUserRole.FATHER))
                        .requestMatchers(HttpMethod.DELETE, "/api/media/**").access(hasCurrentRole(familyUserRepository, FamilyUserRole.MOTHER, FamilyUserRole.FATHER))
                        .requestMatchers(HttpMethod.POST, "/api/media/delete").access(hasCurrentRole(familyUserRepository, FamilyUserRole.MOTHER, FamilyUserRole.FATHER))
                        .requestMatchers("/api/admin/**").access(hasCurrentRole(familyUserRepository, FamilyUserRole.MOTHER, FamilyUserRole.FATHER))
                        .requestMatchers("/api/media/**", "/api/push/subscriptions").access(hasCurrentRole(familyUserRepository, FamilyUserRole.MOTHER, FamilyUserRole.FATHER, FamilyUserRole.FAMILY))
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) -> response.sendError(HttpServletResponse.SC_FORBIDDEN))
                )
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo.userService(familyOAuth2UserService))
                        .defaultSuccessUrl("/", true)
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .deleteCookies("JSESSIONID")
                        .invalidateHttpSession(true)
                )
                .build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> hasCurrentRole(FamilyUserRepository familyUserRepository, FamilyUserRole... roles) {
        Set<FamilyUserRole> allowedRoles = Set.copyOf(Arrays.asList(roles));
        return (Supplier<org.springframework.security.core.Authentication> authenticationSupplier, RequestAuthorizationContext context) -> {
            org.springframework.security.core.Authentication authentication = authenticationSupplier.get();
            if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof OAuth2User oauthUser)) {
                return new AuthorizationDecision(false);
            }
            String kakaoId = firstText(oauthUser.getAttribute("kakaoId"), oauthUser.getAttribute("id"));
            boolean granted = kakaoId != null && familyUserRepository.findByKakaoId(kakaoId)
                    .map(user -> allowedRoles.contains(user.getRole()))
                    .orElse(false);
            return new AuthorizationDecision(granted);
        };
    }

    private static String firstText(Object first, Object second) {
        if (first instanceof String text && !text.isBlank()) return text;
        if (second instanceof String text && !text.isBlank()) return text;
        if (first instanceof Number number) return String.valueOf(number);
        if (second instanceof Number number) return String.valueOf(number);
        return null;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
