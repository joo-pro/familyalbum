package com.joopapa.familyalbum.auth;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record MeResponse(
            boolean authenticated,
            boolean approved,
            boolean admin,
            UserResponse user
    ) {
        static MeResponse anonymous() {
            return new MeResponse(false, false, false, null);
        }

        static MeResponse from(FamilyUser user) {
            return new MeResponse(true, user.isApproved(), user.getRole().isParent(), UserResponse.from(user));
        }
    }

    public record UserResponse(
            UUID id,
            String kakaoId,
            String nickname,
            String profileImageUrl,
            FamilyUserRole role,
            boolean approved,
            Instant createdAt,
            Instant approvedAt,
            Instant lastLoginAt
    ) {
        static UserResponse from(FamilyUser user) {
            return new UserResponse(
                    user.getId(),
                    user.getKakaoId(),
                    user.getNickname(),
                    user.getProfileImageUrl(),
                    user.getRole(),
                    user.isApproved(),
                    user.getCreatedAt(),
                    user.getApprovedAt(),
                    user.getLastLoginAt()
            );
        }
    }

    public record UserListResponse(List<UserResponse> users) {
    }

    public record UpdateUserRoleRequest(@NotNull FamilyUserRole role) {
    }
}