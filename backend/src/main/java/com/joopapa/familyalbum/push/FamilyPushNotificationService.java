package com.joopapa.familyalbum.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joopapa.familyalbum.media.MediaAsset;
import com.joopapa.familyalbum.media.MediaType;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class FamilyPushNotificationService {
    private static final Logger log = LoggerFactory.getLogger(FamilyPushNotificationService.class);

    private final FamilyPushSubscriptionRepository pushSubscriptionRepository;
    private final PushProperties pushProperties;
    private final ObjectMapper objectMapper;
    private final Executor mediaProcessingExecutor;

    public FamilyPushNotificationService(
            FamilyPushSubscriptionRepository pushSubscriptionRepository,
            PushProperties pushProperties,
            ObjectMapper objectMapper,
            @Qualifier("mediaProcessingExecutor") Executor mediaProcessingExecutor
    ) {
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.pushProperties = pushProperties;
        this.objectMapper = objectMapper;
        this.mediaProcessingExecutor = mediaProcessingExecutor;
    }

    public PushDtos.PublicKeyResponse publicKey() {
        return new PushDtos.PublicKeyResponse(pushProperties.enabled(), pushProperties.vapidPublicKey());
    }

    @Transactional
    public void saveSubscription(PushDtos.SubscriptionRequest request, String userAgent) {
        FamilyPushSubscription subscription = pushSubscriptionRepository.findByEndpoint(request.endpoint())
                .orElseGet(() -> new FamilyPushSubscription(
                        request.endpoint(),
                        request.keys().p256dh(),
                        request.keys().auth(),
                        normalizeUserAgent(userAgent)
                ));
        subscription.updateKeys(request.keys().p256dh(), request.keys().auth(), normalizeUserAgent(userAgent));
        pushSubscriptionRepository.save(subscription);
    }

    public void notifyNewMedia(MediaAsset asset) {
        if (!pushProperties.enabled()) {
            return;
        }
        String payload = newMediaPayload(asset);
        mediaProcessingExecutor.execute(() -> sendToActiveSubscriptions(payload));
    }

    @Transactional(readOnly = true)
    protected List<FamilyPushSubscription> activeSubscriptions() {
        return pushSubscriptionRepository.findByActiveTrue();
    }

    private void sendToActiveSubscriptions(String payload) {
        List<FamilyPushSubscription> subscriptions = activeSubscriptions();
        if (subscriptions.isEmpty()) {
            return;
        }

        PushService pushService;
        try {
            pushService = new PushService()
                    .setPublicKey(pushProperties.vapidPublicKey())
                    .setPrivateKey(pushProperties.vapidPrivateKey())
                    .setSubject(pushProperties.subject());
        } catch (Exception exception) {
            log.warn("Web push service configuration failed", exception);
            return;
        }

        for (FamilyPushSubscription subscription : subscriptions) {
            sendOne(pushService, subscription, payload);
        }
    }

    private void sendOne(PushService pushService, FamilyPushSubscription subscription, String payload) {
        try {
            Notification notification = new Notification(
                    subscription.getEndpoint(),
                    subscription.getP256dh(),
                    subscription.getAuth(),
                    payload.getBytes(StandardCharsets.UTF_8)
            );
            HttpResponse response = pushService.send(notification);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                deactivate(subscription);
            } else if (status >= 400) {
                log.warn("Web push delivery failed with status {} for endpoint {}", status, subscription.getEndpoint());
            }
        } catch (Exception exception) {
            log.warn("Web push delivery failed for endpoint {}", subscription.getEndpoint(), exception);
        }
    }

    @Transactional
    protected void deactivate(FamilyPushSubscription subscription) {
        subscription.deactivate();
        pushSubscriptionRepository.save(subscription);
    }

    private String newMediaPayload(MediaAsset asset) {
        String typeLabel = asset.getMediaType() == MediaType.VIDEO ? "동영상" : "사진";
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", "새 기록이 올라왔어요",
                    "body", typeLabel + "이 지웅이 성장일기에 추가됐어요.",
                    "url", "/"
            ));
        } catch (JsonProcessingException exception) {
            return "{\"title\":\"새 기록이 올라왔어요\",\"body\":\"지웅이 성장일기에 새 기록이 추가됐어요.\",\"url\":\"/\"}";
        }
    }

    private static String normalizeUserAgent(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return null;
        }
        return userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
    }
}