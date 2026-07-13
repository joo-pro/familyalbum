package com.joopapa.familyalbum.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class PushDtos {
    private PushDtos() {
    }

    public record PublicKeyResponse(boolean enabled, String publicKey) {
    }

    public record SubscriptionRequest(
            @NotBlank String endpoint,
            @NotNull @Valid SubscriptionKeys keys
    ) {
    }

    public record SubscriptionKeys(
            @NotBlank String p256dh,
            @NotBlank String auth
    ) {
    }

    public record SubscriptionResponse(boolean subscribed) {
    }
}