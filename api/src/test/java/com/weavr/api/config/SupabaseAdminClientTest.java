package com.weavr.api.config;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Binds {@link MockRestServiceServer} to the injected {@code RestClient.Builder}
 * — the same pattern {@code GeminiClientTest} established — so this exercises
 * the real request shape rather than a mocked HTTP layer.
 */
class SupabaseAdminClientTest {

    private static final SupabaseProperties PROPS =
            new SupabaseProperties("https://abcdefgh.supabase.co", "authenticated",
                    "test-service-key", "screenshots");

    private MockRestServiceServer server;
    private SupabaseAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SupabaseAdminClient(PROPS, builder);
    }

    @Test
    void deletesTheAuthUserWithTheServiceKeyOnBothHeaders() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("https://abcdefgh.supabase.co/auth/v1/admin/users/" + userId))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer test-service-key"))
                .andExpect(header("apikey", "test-service-key"))
                .andRespond(withNoContent());

        client.deleteUser(userId);

        server.verify();
    }

    /**
     * A retried account-deletion call finds the user already gone — the
     * second call must finish cleanly, not throw and block the retry that
     * exists precisely to close this out.
     */
    @Test
    void aMissingUserIsTreatedAsAlreadyDeleted() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("https://abcdefgh.supabase.co/auth/v1/admin/users/" + userId))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatCode(() -> client.deleteUser(userId)).doesNotThrowAnyException();
    }

    @Test
    void aServerErrorPropagatesRatherThanBeingSwallowed() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("https://abcdefgh.supabase.co/auth/v1/admin/users/" + userId))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.deleteUser(userId))
                .isInstanceOf(HttpServerErrorException.class);
    }
}
