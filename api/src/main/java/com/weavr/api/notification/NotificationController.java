package com.weavr.api.notification;

import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    record PushTokenRequest(
            @NotBlank(message = "token is required")
            String token,
            String platform
    ) {
    }

    record UnregisterTokenRequest(
            @NotBlank(message = "token is required")
            String token
    ) {
    }

    @PostMapping("/push-token")
    ResponseEntity<Void> registerPushToken(@CurrentUser UUID userId,
                                           @Valid @RequestBody PushTokenRequest request) {
        notifications.registerToken(userId, request.token(), request.platform());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/push-token")
    ResponseEntity<Void> unregisterPushToken(@CurrentUser UUID userId,
                                             @Valid @RequestBody UnregisterTokenRequest request) {
        notifications.unregisterToken(userId, request.token());
        return ResponseEntity.noContent().build();
    }
}
