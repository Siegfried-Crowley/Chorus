package org.chorus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权回归测试 — 覆盖本轮修复的越权漏洞与安全加固点:
 * 消息读取越权、频道/成员/角色枚举、DM 入侵、权限覆盖自授权、搜索 LIKE 通配符转义、附件魔数校验。
 */
class AuthorizationRegressionTest extends BaseIntegrationTest {

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

    // ========== 越权读取 ==========

    @Test
    void nonMember_cannotReadMessagesOrChannel() {
        String guildId = createGuild(aliceToken, "AuthzGuild-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "secret");
        createMessage(aliceToken, channelId, "classified");

        // bob 不是成员:读消息历史 → 403
        ResponseEntity<String> readMsgs = raw(HttpMethod.GET,
                "/api/channels/" + channelId + "/messages", bobToken, null);
        assertThat(readMsgs.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // bob 不是成员:查看频道详情 → 403
        ResponseEntity<String> readChannel = raw(HttpMethod.GET,
                "/api/channels/" + channelId, bobToken, null);
        assertThat(readChannel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void nonMember_cannotEnumerateGuildData() {
        String guildId = createGuild(aliceToken, "EnumGuild-" + System.nanoTime());
        createChannel(aliceToken, guildId, "general");
        post("/api/guilds/" + guildId + "/roles", aliceToken, Map.of("name", "Admin"));

        // bob 不是成员:列出成员 / 角色 → 403
        assertThat(raw(HttpMethod.GET, "/api/guilds/" + guildId + "/members", bobToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(raw(HttpMethod.GET, "/api/guilds/" + guildId + "/roles", bobToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(raw(HttpMethod.GET, "/api/guilds/" + guildId, bobToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void nonDmMember_cannotReadDmMessages() {
        // alice 与 bob 建立 DM
        String dmId = post("/api/dm/channels", aliceToken, Map.of("user_id", "1000000000000002"))
                .path("id").asText();
        post("/api/channels/" + dmId + "/messages", aliceToken, Map.of("content", "private note"));

        // charlie 不在该 DM → 读消息 403
        ResponseEntity<String> intrude = raw(HttpMethod.GET,
                "/api/channels/" + dmId + "/messages", charlieToken, null);
        assertThat(intrude.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticated_requestIsRejected() {
        HttpHeaders h = new HttpHeaders();
        ResponseEntity<String> r = rest.exchange(
                url("/api/channels/1/messages"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ========== 权限覆盖自授权 ==========

    @Test
    void nonManager_cannotModifyChannelOverwrites() {
        String guildId = createGuild(aliceToken, "OwGuild-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "general");
        joinGuild(bobToken, createInvite(aliceToken, guildId));

        // 普通成员 bob 不能创建 overwrite 给自己加权限 → 403
        ResponseEntity<String> denied = raw(HttpMethod.POST,
                "/api/channels/" + channelId + "/permissions",
                bobToken, Map.of("type", 1, "target_id", "1000000000000002", "allow", "1024"));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 普通成员不能查看权限覆盖列表 → 403
        ResponseEntity<String> listDenied = raw(HttpMethod.GET,
                "/api/channels/" + channelId + "/permissions", bobToken, null);
        assertThat(listDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // owner 可以创建
        ResponseEntity<String> ok = raw(HttpMethod.POST,
                "/api/channels/" + channelId + "/permissions",
                aliceToken, Map.of("type", 1, "target_id", "1000000000000002", "allow", "1024"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ========== 搜索 LIKE 通配符转义 ==========

    @Test
    void search_treatsWildcardsLiterally() {
        String guildId = createGuild(aliceToken, "LikeGuild-" + System.nanoTime());
        String channelId = createChannel(aliceToken, guildId, "general");
        createMessage(aliceToken, channelId, "alpha_beta");
        createMessage(aliceToken, channelId, "alphaXbeta");

        // 未转义时 %alpha_beta% 会同时命中 alpha_beta 与 alphaXbeta(下划线是单字符通配符);
        // 修复后应只命中含字面下划线的消息
        ResponseEntity<String> r = rest.exchange(
                url("/api/guilds/" + guildId + "/messages/search?query="
                        + URLEncoder.encode("alpha_beta", StandardCharsets.UTF_8)),
                HttpMethod.GET, authed(aliceToken, null), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode results = parse(r.getBody());
        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).path("content").asText()).isEqualTo("alpha_beta");
    }

    // ========== 附件魔数校验 ==========

    @Test
    void upload_rejectsContentThatDoesNotMatchType() {
        // 声称是 PNG,但字节是纯文本 → 400
        byte[] fakePng = "this is definitely not a png".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> bad = uploadBytes(aliceToken, "fake.png", "image/png", fakePng);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void upload_acceptsValidPng() {
        // 最小合法 PNG 头(8 字节魔数 + 填充)
        byte[] png = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
        };
        ResponseEntity<String> ok = uploadBytes(aliceToken, "pic.png", "image/png", png);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parse(ok.getBody());
        assertThat(body.path("content_type").asText()).isEqualTo("image/png");
        assertThat(body.path("url").asText()).startsWith("/uploads/");
    }

    @Test
    void upload_rejectsUnsupportedType() {
        byte[] exe = new byte[]{'M', 'Z', 0x00, 0x00, 0x00, 0x00};
        ResponseEntity<String> bad = uploadBytes(aliceToken, "tool.exe", "application/x-msdownload", exe);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> uploadBytes(String token, String filename, String mime, byte[] bytes) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token);
        return rest.exchange(url("/api/uploads"), HttpMethod.POST, new HttpEntity<>(form, h), String.class);
    }
}
