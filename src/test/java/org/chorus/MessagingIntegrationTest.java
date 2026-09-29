package org.chorus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper mapper;

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        aliceToken = login("alice@test.com", "test123");
        bobToken = login("bob@test.com", "test123");
    }

    private HttpEntity<Object> authed(String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return new HttpEntity<>(body, h);
    }

    private JsonNode post(String path, String token, Object body) {
        return send(HttpMethod.POST, path, token, body);
    }

    private JsonNode patch(String path, String token, Object body) {
        return send(HttpMethod.PATCH, path, token, body);
    }

    private JsonNode send(HttpMethod method, String path, String token, Object body) {
        ResponseEntity<String> r = rest.exchange(url(path), method, authed(token, body), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(r.getBody());
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String createGuild(String token, String name) {
        return post("/api/guilds", token, Map.of("name", name)).path("id").asText();
    }

    private String createChannel(String token, String guildId, String name, int type) {
        return post("/api/channels", token,
                Map.of("guild_id", guildId, "name", name, "type", type)).path("id").asText();
    }

    @Test
    void guildMessageCrud() {
        String guildId = createGuild(aliceToken, "TestGuild-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "general", 0);

        JsonNode msg = post("/api/channels/" + channelId + "/messages",
                aliceToken, Map.of("content", "hello"));
        assertThat(msg.path("id").asText()).isNotEmpty();
        String msgId = msg.path("id").asText();

        JsonNode edited = patch("/api/channels/" + channelId + "/messages/" + msgId,
                aliceToken, Map.of("content", "edited!"));
        assertThat(edited.path("content").asText()).isEqualTo("edited!");

        ResponseEntity<String> list = rest.exchange(url("/api/channels/" + channelId + "/messages"),
                HttpMethod.GET, authed(aliceToken, null), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> del = rest.exchange(url("/api/channels/" + channelId + "/messages/" + msgId),
                HttpMethod.DELETE, authed(aliceToken, null), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void dmFlow_openChannelAndSend() {
        JsonNode dm = post("/api/dm/channels", aliceToken, Map.of("user_id", "1000000000000002"));
        String dmId = dm.path("id").asText();
        assertThat(dm.path("recipient").path("username").asText()).isEqualTo("Bob");

        JsonNode dm2 = post("/api/dm/channels", aliceToken, Map.of("user_id", "1000000000000002"));
        assertThat(dm2.path("id").asText()).isEqualTo(dmId);

        post("/api/channels/" + dmId + "/messages", aliceToken, Map.of("content", "hi bob"));

        ResponseEntity<String> list = rest.exchange(url("/api/dm/channels"),
                HttpMethod.GET, authed(aliceToken, null), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        String charlieToken = login("charlie@test.com", "test123");
        ResponseEntity<String> intrude = rest.exchange(url("/api/channels/" + dmId + "/messages"),
                HttpMethod.POST, authed(charlieToken, Map.of("content", "intrude")), String.class);
        assertThat(intrude.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void attachment_messagePersistsUrl() {
        String guildId = createGuild(aliceToken, "AttGuild-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "files", 0);

        String attachments = "[{\"url\":\"/uploads/x.png\",\"filename\":\"x.png\",\"content_type\":\"image/png\",\"size\":10}]";
        JsonNode msg = post("/api/channels/" + channelId + "/messages",
                aliceToken, Map.of("content", "see image", "attachments", attachments));
        assertThat(msg.path("attachments").path(0).path("filename").asText()).isEqualTo("x.png");
    }
}
