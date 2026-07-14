package com.joopapa.familyalbum.auth;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final FamilyUserService familyUserService;

    public AuthController(FamilyUserService familyUserService) {
        this.familyUserService = familyUserService;
    }

    @GetMapping("/auth/me")
    AuthDtos.MeResponse me(Authentication authentication) {
        return familyUserService.currentUser(authentication);
    }

    @GetMapping("/admin/users")
    AuthDtos.UserListResponse listUsers() {
        return familyUserService.listUsers();
    }

    @PatchMapping("/admin/users/{userId}/role")
    AuthDtos.UserResponse updateRole(
            @PathVariable UUID userId,
            @Valid @RequestBody AuthDtos.UpdateUserRoleRequest request
    ) {
        return familyUserService.updateRole(userId, request.role());
    }
}