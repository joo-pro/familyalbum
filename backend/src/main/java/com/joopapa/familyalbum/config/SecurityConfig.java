package com.joopapa.familyalbum.config;

import com.joopapa.familyalbum.auth.FamilyOAuth2UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, FamilyOAuth2UserService familyOAuth2UserService) throws Exception {
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
                        .requestMatchers(HttpMethod.POST, "/api/media/upload", "/api/media/upload-url", "/api/media/upload-complete", "/api/media/visibility").hasAnyRole("MOTHER", "FATHER")
                        .requestMatchers(HttpMethod.DELETE, "/api/media/**").hasAnyRole("MOTHER", "FATHER")
                        .requestMatchers(HttpMethod.POST, "/api/media/delete").hasAnyRole("MOTHER", "FATHER")
                        .requestMatchers("/api/admin/**").hasAnyRole("MOTHER", "FATHER")
                        .requestMatchers("/api/media/**", "/api/push/subscriptions").hasAnyRole("MOTHER", "FATHER", "FAMILY")
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
