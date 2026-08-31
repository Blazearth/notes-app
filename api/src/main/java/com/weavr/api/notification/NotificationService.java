package com.weavr.api.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcClient jdbc;
    private final NotificationProperties props;
    private final RestClient http;

    public NotificationService(JdbcClient jdbc,
                               NotificationProperties props,
                               @Qualifier("notification") RestClient.Builder builder) {
        this.jdbc = jdbc;
        this.props = props;
        this.http = builder.baseUrl(props.expoPushUrl()).build();
    }

    /**
     * Registers or updates an Expo push token for a user.
     */
    @Transactional
    public void registerToken(UUID userId, String token, String platform) {
        if (token == null || token.isBlank()) {
            return;
        }
        String p = (platform == null || platform.isBlank()) ? "expo" : platform;
        jdbc.sql("""
                insert into user_push_tokens (user_id, token, platform, updated_at)
                values (?, ?, ?, now())
                on conflict (user_id, token) do update
                set platform = excluded.platform,
                    updated_at = now()
                """)
                .params(userId, token.trim(), p)
                .update();
        log.info("Registered push token for user {} ({})", userId, p);
    }

    /**
     * Unregisters a push token upon sign-out.
     */
    @Transactional
    public void unregisterToken(UUID userId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        jdbc.sql("delete from user_push_tokens where user_id = ? and token = ?")
                .params(userId, token.trim())
                .update();
        log.info("Unregistered push token for user {}", userId);
    }

    /**
     * Notifies all members of a Space (excluding the user who triggered the event, e.g. the joiner).
     */
    public void notifySpaceMembers(UUID spaceId, UUID excludeUserId, String title, String body, Map<String, Object> data) {
        if (!props.enabled()) {
            log.debug("Push notifications disabled; skipping space {} notification", spaceId);
            return;
        }

        try {
            List<String> tokens = jdbc.sql("""
                    select distinct upt.token
                    from space_members sm
                    join user_push_tokens upt on upt.user_id = sm.user_id
                    where sm.space_id = ?
                      and sm.user_id != ?
                    """)
                    .params(spaceId, excludeUserId)
                    .query(String.class)
                    .list();

            if (tokens.isEmpty()) {
                log.debug("No push tokens found for space {} members (excluding {})", spaceId, excludeUserId);
                return;
            }

            sendBatch(tokens, title, body, data, "spaces");
        } catch (Exception e) {
            log.warn("Failed to notify members of space {}: {}", spaceId, e.getMessage());
        }
    }

    /**
     * Sends a push notification to a specific user.
     */
    public void notifyUser(UUID userId, String title, String body, Map<String, Object> data) {
        if (!props.enabled()) {
            return;
        }

        try {
            List<String> tokens = jdbc.sql("select token from user_push_tokens where user_id = ?")
                    .param(userId)
                    .query(String.class)
                    .list();

            if (tokens.isEmpty()) {
                return;
            }

            sendBatch(tokens, title, body, data, "default");
        } catch (Exception e) {
            log.warn("Failed to send push notification to user {}: {}", userId, e.getMessage());
        }
    }

    private void sendBatch(List<String> tokens, String title, String body, Map<String, Object> data, String channelId) {
        List<Map<String, Object>> messages = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            messages.add(Map.of(
                    "to", token,
                    "title", title,
                    "body", body,
                    "data", data != null ? data : Map.of(),
                    "sound", "default",
                    "channelId", channelId
            ));
        }

        try {
            http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(messages)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Dispatched {} push notification(s) [title='{}', channel='{}']", tokens.size(), title, channelId);
        } catch (Exception e) {
            log.warn("Expo push notification delivery failed: {}", e.getMessage());
        }
    }
}
