package com.weavr.api.notification;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NotificationServiceTest {

    private JdbcClient jdbc;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private NotificationProperties props;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcClient.class);
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        props = new NotificationProperties(true, "https://exp.host/--/api/v2/push/send", Duration.ofSeconds(5));
        service = new NotificationService(jdbc, props, builder);
    }

    @Test
    void registerTokenExecutesUpsert() {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("insert into user_push_tokens"))).thenReturn(statement);
        when(statement.params(any(), any(), any())).thenReturn(statement);

        UUID userId = UUID.randomUUID();
        service.registerToken(userId, "ExponentPushToken[abc12345]", "android");

        verify(statement).update();
    }

    @Test
    void unregisterTokenDeletesRow() {
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("delete from user_push_tokens"))).thenReturn(statement);
        when(statement.params(any(), any())).thenReturn(statement);

        UUID userId = UUID.randomUUID();
        service.unregisterToken(userId, "ExponentPushToken[abc12345]");

        verify(statement).update();
    }

    @SuppressWarnings("unchecked")
    @Test
    void notifySpaceMembersDispatchesPushToExpo() {
        UUID spaceId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();

        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("from space_members sm"))).thenReturn(select);
        when(select.params(eq(spaceId), eq(joinerId))).thenReturn(select);

        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(select.query(String.class)).thenReturn(querySpec);
        when(querySpec.list()).thenReturn(List.of("ExponentPushToken[token1]", "ExponentPushToken[token2]"));

        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\"},{\"status\":\"ok\"}]}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> service.notifySpaceMembers(
                spaceId,
                joinerId,
                "Tokyo Trip",
                "Alex joined Tokyo Trip",
                Map.of("type", "space", "spaceId", spaceId.toString())
        )).doesNotThrowAnyException();

        server.verify();
    }

    @SuppressWarnings("unchecked")
    @Test
    void notifyUserSendsPushWhenTokenPresent() {
        UUID userId = UUID.randomUUID();

        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(contains("select token from user_push_tokens"))).thenReturn(select);
        when(select.param(eq(userId))).thenReturn(select);

        JdbcClient.MappedQuerySpec<String> querySpec = mock(JdbcClient.MappedQuerySpec.class);
        when(select.query(String.class)).thenReturn(querySpec);
        when(querySpec.list()).thenReturn(List.of("ExponentPushToken[token1]"));

        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\"}]}", MediaType.APPLICATION_JSON));

        service.notifyUser(userId, "Reminder", "Don't forget your workout", Map.of("type", "save"));

        server.verify();
    }
}
