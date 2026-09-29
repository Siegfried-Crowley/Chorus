package org.chorus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.chorus.entity.User;
import org.chorus.repository.UserRepository;
import org.chorus.service.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InviteIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CacheService cacheService;

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

    private String createInvite(String token, String guildId) {
        return post("/api/guilds/" + guildId + "/invites", token, Map.of())
                .path("code").asText();
    }

    @Test
    void joinInvite_addsMember_andPreviewWorks() {
        String guildId = createGuild(aliceToken, "InviteGuild-" + System.nanoTime());
        String code = createInvite(aliceToken, guildId);

        // 预览
        JsonNode preview = raw(HttpMethod.GET, "/api/invites/" + code, aliceToken, null).getBody() != null
                ? parse(raw(HttpMethod.GET, "/api/invites/" + code, aliceToken, null).getBody())
                : null;
        assertThat(preview).isNotNull();
        assertThat(preview.path("guild_id").asText()).isEqualTo(guildId);
        assertThat(preview.path("guild_name").asText()).startsWith("InviteGuild");

        // 加入
        JsonNode joined = post("/api/invites/" + code + "/join", bobToken, Map.of());
        assertThat(joined.path("guild_id").asText()).isEqualTo(guildId);
        assertThat(joined.path("name").asText()).startsWith("InviteGuild");

        // 已在公会 → 重复加入失败
        ResponseEntity<String> dup = raw(HttpMethod.POST, "/api/invites/" + code + "/join", bobToken, Map.of());
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(dup.getBody()).path("error").asText()).contains("Already a member");
    }

    @Test
    void bannedUser_cannotJoin() {
        String guildId = createGuild(aliceToken, "BanInvite-" + System.nanoTime());
        String code = createInvite(aliceToken, guildId);

        // alice 禁了 bob → bob 无法通过邀请加入
        send(HttpMethod.PUT, "/api/guilds/" + guildId + "/members/1000000000000002/ban",
                aliceToken, Map.of("reason", "spam"));

        ResponseEntity<String> r = raw(HttpMethod.POST, "/api/invites/" + code + "/join", bobToken, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(r.getBody()).path("error").asText()).contains("Banned from guild");
    }

    @Test
    void invite_maxUsesReached() throws Exception {
        String guildId = createGuild(aliceToken, "MaxUses-" + System.nanoTime());
        String code = post("/api/guilds/" + guildId + "/invites", aliceToken,
                Map.of("max_uses", 1)).path("code").asText();

        // charlie 加入,次数用完
        post("/api/invites/" + code + "/join", charlieToken, Map.of());

        // 需要第 4 个用户验证次数上限 —— 注册一个新用户(注册后需邮箱验证)
        String email = "uses-" + System.nanoTime() + "@test.com";
        JsonNode reg = post("/api/auth/register",
                aliceToken, Map.of("username", "UsesUser", "email", email, "password", "pass123"));
        User u = userRepository.findByEmail(email).orElseThrow();
        String verifyCode = cacheService.get("verify_email:" + u.getId());
        HttpEntity<Map<String, Object>> verifyEntity = new HttpEntity<>(
                Map.of("email", email, "code", verifyCode));
        ResponseEntity<String> verified = rest.postForEntity(
                url("/api/auth/verify-email"), verifyEntity, String.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newToken = parse(verified.getBody()).path("token").asText();

        ResponseEntity<String> r = raw(HttpMethod.POST, "/api/invites/" + code + "/join", newToken, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(r.getBody()).path("error").asText()).contains("max uses");
    }

    @Test
    void invite_expired() throws Exception {
        String guildId = createGuild(aliceToken, "Expire-" + System.nanoTime());
        String code = post("/api/guilds/" + guildId + "/invites", aliceToken,
                Map.of("max_age", 1)).path("code").asText();

        Thread.sleep(1200);
        ResponseEntity<String> r = raw(HttpMethod.POST, "/api/invites/" + code + "/join", bobToken, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(r.getBody()).path("error").asText()).contains("expired");
    }

    @Test
    void listAndDeleteInvite() {
        String guildId = createGuild(aliceToken, "ListInv-" + System.nanoTime());
        String code = createInvite(aliceToken, guildId);

        JsonNode list = send(HttpMethod.GET, "/api/guilds/" + guildId + "/invites", aliceToken, null);
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("code").asText()).isEqualTo(code);

        // 能创建邀请的成员即可查看列表(与 createInvite 的 CREATE_INSTANT_INVITE 一致):
        // 若要求更高的 MANAGE_GUILD,普通成员会"创建成功但列表无变化",误以为失败而重复创建
        post("/api/invites/" + code + "/join", bobToken, Map.of());
        ResponseEntity<String> asMember = raw(HttpMethod.GET, "/api/guilds/" + guildId + "/invites", bobToken, null);
        assertThat(asMember.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(asMember.getBody()).size()).isGreaterThanOrEqualTo(1);

        // 安全边界:非成员依旧无权查看邀请列表
        ResponseEntity<String> nonMember = raw(HttpMethod.GET, "/api/guilds/" + guildId + "/invites", charlieToken, null);
        assertThat(nonMember.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 删除邀请
        ResponseEntity<String> del = raw(HttpMethod.DELETE, "/api/invites/" + code, aliceToken, null);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 预览已删除邀请 → 404 Invite not found
        ResponseEntity<String> gone = raw(HttpMethod.GET, "/api/invites/" + code, aliceToken, null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
