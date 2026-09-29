package org.chorus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 消息增强集成测试：
 * reactions(加/删/去重)、pins(权限/流程)、typing、search、reply(message_reference)、mentions(@everyone/@用户)
 */
class MessageEnhancementTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper mapper;

    private String aliceToken;
    private String bobToken;
    private String charlieToken;

    @BeforeEach
    void setUp() {
        aliceToken = login("alice@test.com", "test123");
        bobToken = login("bob@test.com", "test123");
        charlieToken = login("charlie@test.com", "test123");
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

    private JsonNode send(HttpMethod method, String path, String token, Object body) {
        ResponseEntity<String> r = rest.exchange(url(path), method, authed(token, body), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(r.getBody());
    }

    private ResponseEntity<String> raw(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(url(path), method, authed(token, body), String.class);
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private String createGuild(String token, String name) {
        return post("/api/guilds", token, Map.of("name", name)).path("id").asText();
    }

    private String createChannel(String token, String guildId, String name) {
        return post("/api/channels", token,
                Map.of("guild_id", guildId, "name", name, "type", 0)).path("id").asText();
    }

    private String createInvite(String token, String guildId) {
        return post("/api/guilds/" + guildId + "/invites", token, Map.of()).path("code").asText();
    }

    private String joinGuild(String token, String code) {
        return post("/api/invites/" + code + "/join", token, Map.of()).path("guild_id").asText();
    }

    private String createMessage(String token, String channelId, String content) {
        return post("/api/channels/" + channelId + "/messages", token, Map.of("content", content))
                .path("id").asText();
    }

    // ========== 反应 ==========

    @Test
    void reaction_addRemoveAndDedup() throws Exception {
        String guildId = createGuild(aliceToken, "React-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "react");
        String msgId = createMessage(aliceToken, channelId, "react me");

        // bob 需先加入公会才能对频道消息点赞(越权防护:非成员 403)
        String code = createInvite(aliceToken, guildId);
        joinGuild(bobToken, code);

        // RestTemplate 会自行编码路径中的非 ASCII 字符,直接传原始 emoji
        // alice + bob 各自点赞
        JsonNode add1 = send(HttpMethod.PUT,
                "/api/channels/" + channelId + "/messages/" + msgId + "/reactions/👍",
                aliceToken, null);
        JsonNode reactions = add1.path("reactions");
        assertThat(reactions.path("👍").size()).isEqualTo(1);
        assertThat(reactions.path("👍").get(0).asText()).isEqualTo("1000000000000001");

        send(HttpMethod.PUT,
                "/api/channels/" + channelId + "/messages/" + msgId + "/reactions/👍",
                bobToken, null);
        // 重复点赞(idempotent,不重复计数)
        send(HttpMethod.PUT,
                "/api/channels/" + channelId + "/messages/" + msgId + "/reactions/👍",
                bobToken, null);

        JsonNode afterBob = send(HttpMethod.GET, "/api/channels/" + channelId + "/messages", aliceToken, null);
        JsonNode msg = afterBob.get(0);
        assertThat(msg.path("reactions").path("👍").size()).isEqualTo(2);

        // alice 取消点赞 → 剩 bob 一人
        JsonNode removed = send(HttpMethod.DELETE,
                "/api/channels/" + channelId + "/messages/" + msgId + "/reactions/👍",
                aliceToken, null);
        assertThat(removed.path("reactions").path("👍").size()).isEqualTo(1);
    }

    // ========== 置顶 ==========

    @Test
    void pin_requiresManageMessages() {
        String guildId = createGuild(aliceToken, "Pin-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "pins");
        String msgId = createMessage(aliceToken, channelId, "pin me");

        // bob 加入公会(普通成员,无 MANAGE_MESSAGES)
        String code = createInvite(aliceToken, guildId);
        joinGuild(bobToken, code);

        // 普通成员置顶 → 403 Missing MANAGE_MESSAGES
        ResponseEntity<String> denied = raw(HttpMethod.PUT,
                "/api/channels/" + channelId + "/messages/pins/" + msgId, bobToken, null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(denied.getBody()).path("error").asText()).contains("MANAGE_MESSAGES");

        // owner 可置顶 → 置顶列表有该消息
        JsonNode pinned = send(HttpMethod.PUT, "/api/channels/" + channelId + "/messages/pins/" + msgId,
                aliceToken, null);
        assertThat(pinned.path("pinned").asBoolean()).isTrue();

        JsonNode pins = send(HttpMethod.GET, "/api/channels/" + channelId + "/messages/pins", aliceToken, null);
        assertThat(pins.isArray()).isTrue();
        assertThat(pins.size()).isEqualTo(1);
        assertThat(pins.get(0).path("id").asText()).isEqualTo(msgId);

        // 取消置顶
        JsonNode unpinned = send(HttpMethod.DELETE, "/api/channels/" + channelId + "/messages/pins/" + msgId,
                aliceToken, null);
        assertThat(unpinned.path("pinned").asBoolean()).isFalse();
        JsonNode pinsEmpty = send(HttpMethod.GET, "/api/channels/" + channelId + "/messages/pins", aliceToken, null);
        assertThat(pinsEmpty.size()).isZero();
    }

    // ========== 输入中 ==========

    @Test
    void typing_returnsOk() {
        String guildId = createGuild(aliceToken, "Typing-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "chat");

        ResponseEntity<String> r = raw(HttpMethod.POST,
                "/api/channels/" + channelId + "/messages/typing", aliceToken, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ========== 搜索 ==========

    @Test
    void search_matchesContentAndChecksMembership() throws Exception {
        String guildId = createGuild(aliceToken, "Search-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "general");
        createMessage(aliceToken, channelId, "hello world one");
        createMessage(aliceToken, channelId, "goodbye two");

        // 命中内容
        ResponseEntity<String> r = rest.exchange(
                url("/api/guilds/" + guildId + "/messages/search?query="
                        + URLEncoder.encode("hello", StandardCharsets.UTF_8)),
                HttpMethod.GET, authed(aliceToken, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode results = parse(r.getBody());
        assertThat(results.isArray()).isTrue();
        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).path("content").asText()).contains("hello");

        // 空 query → 空结果
        ResponseEntity<String> empty = rest.exchange(
                url("/api/guilds/" + guildId + "/messages/search?query="),
                HttpMethod.GET, authed(aliceToken, null), String.class);
        assertThat(parse(empty.getBody()).size()).isZero();

        // 非成员 → 403 Not a member
        ResponseEntity<String> denied = rest.exchange(
                url("/api/guilds/" + guildId + "/messages/search?query=hello"),
                HttpMethod.GET, authed(charlieToken, null), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(denied.getBody()).path("error").asText()).contains("Not a member");
    }

    // ========== 回复 ==========

    @Test
    void reply_carriesMessageReference() throws Exception {
        String guildId = createGuild(aliceToken, "Reply-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "chat");
        String targetId = createMessage(aliceToken, channelId, "original");

        JsonNode reply = post("/api/channels/" + channelId + "/messages", aliceToken,
                Map.of("content", "a reply", "messageReference",
                        "{\"message_id\":\"" + targetId + "\",\"channel_id\":\"" + channelId + "\"}"));
        assertThat(reply.path("message_reference").path("message_id").asText()).isEqualTo(targetId);
    }

    // ========== 提及 ==========

    @Test
    void mentions_everyoneAndUser() {
        String guildId = createGuild(aliceToken, "Mention-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "chat");

        // owner 有 MENTION_EVERYONE → mentionEveryone=true;@Alice 命中成员用户名
        JsonNode msg = post("/api/channels/" + channelId + "/messages", aliceToken,
                Map.of("content", "@everyone hi @Alice"));
        assertThat(msg.path("mention_everyone").asBoolean()).isTrue();
        JsonNode mentions = msg.path("mentions");
        assertThat(mentions.path("everyone").asBoolean()).isTrue();
        assertThat(mentions.path("user_ids").isArray()).isTrue();
        assertThat(mentions.path("user_ids").toString()).contains("1000000000000001");
    }
}
