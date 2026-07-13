package com.joopapa.familyalbum.push;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
public class PushController {
    private final FamilyPushNotificationService pushNotificationService;

    public PushController(FamilyPushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    @GetMapping("/public-key")
    PushDtos.PublicKeyResponse publicKey() {
        return pushNotificationService.publicKey();
    }

    @PostMapping("/subscriptions")
    PushDtos.SubscriptionResponse subscribe(
            @Valid @RequestBody PushDtos.SubscriptionRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent
    ) {
        pushNotificationService.saveSubscription(request, userAgent);
        return new PushDtos.SubscriptionResponse(true);
    }
}