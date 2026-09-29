package org.chorus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemberManagementTest extends BaseIntegrationTest {

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

    private String createInvite(String token, String guildId) {
        return post("/api/guilds/" + guildId + "/invites", token, Map.of()).path("code").asText();
    }

    @Test
    void kick_removesMember_andRequiresPermission() {
        String guildId = createGuild(aliceToken, "KickGuild-" + System.nanoTime());
        String code = createInvite(aliceToken, guildId);
        post("/api/invites/" + code + "/join", bobToken, Map.of());
        // charlie 也加入(普通成员,无 KICK_MEMBERS)
        post("/api/invites/" + code + "/join", charlieToken, Map.of());

        // charlie 无 KICK_MEMBERS 权限
        ResponseEntity<String> denied = raw(HttpMethod.PUT,
                "/api/guilds/" + guildId + "/members/1000000000000002/kick", charlieToken, Map.of());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(denied.getBody()).path("error").asText()).contains("KICK_MEMBERS");

        // alice(owner)踢掉 bob
        ResponseEntity<String> kicked = raw(HttpMethod.PUT,
                "/api/guilds/" + guildId + "/members/1000000000000002/kick", aliceToken, Map.of());
        assertThat(kicked.getStatusCode()).isEqualTo(HttpStatus.OK);

        // bob 不再是成员
        JsonNode members = send(HttpMethod.GET, "/api/guilds/" + guildId + "/members", aliceToken, null);
        boolean stillThere = false;
        for (JsonNode m : members) {
            if (m.path("user_id").asText().equals("1000000000000002")) stillThere = true;
        }
        assertThat(stillThere).isFalse();

        // 踢 owner 失败
        ResponseEntity<String> kickOwner = raw(HttpMethod.PUT,
                "/api/guilds/" + guildId + "/members/1000000000000001/kick", aliceToken, Map.of());
        assertThat(kickOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ban_unban_flow() {
        String guildId = createGuild(aliceToken, "BanGuild-" + System.nanoTime());

        ResponseEntity<String> banned = raw(HttpMethod.PUT,
                "/api/guilds/" + guildId + "/members/1000000000000002/ban",
                aliceToken, Map.of("reason", "rule violation"));
        assertThat(banned.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode bans = send(HttpMethod.GET, "/api/guilds/" + guildId + "/bans", aliceToken, null);
        assertThat(bans.size()).isEqualTo(1);
        assertThat(bans.get(0).path("user_id").asText()).isEqualTo("1000000000000002");
        assertThat(bans.get(0).path("reason").asText()).isEqualTo("rule violation");

        // 解禁
        ResponseEntity<String> unbanned = raw(HttpMethod.DELETE,
                "/api/guilds/" + guildId + "/bans/1000000000000002", aliceToken, null);
        assertThat(unbanned.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode bansAfter = send(HttpMethod.GET, "/api/guilds/" + guildId + "/bans", aliceToken, null);
        assertThat(bansAfter.size()).isZero();

        // ban owner 失败
        ResponseEntity<String> banOwner = raw(HttpMethod.PUT,
                "/api/guilds/" + guildId + "/members/1000000000000001/ban", aliceToken, Map.of());
        assertThat(banOwner.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ownerProtection_andGuildDelete() {
        String guildId = createGuild(aliceToken, "OwnerGuild-" + System.nanoTime());
        String code = createInvite(aliceToken, guildId);
        post("/api/invites/" + code + "/join", bobToken, Map.of());

        // 非 owner 不能删公会
        ResponseEntity<String> denied = raw(HttpMethod.DELETE, "/api/guilds/" + guildId, bobToken, null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // owner 不能离开公会
        ResponseEntity<String> ownerLeave = raw(HttpMethod.DELETE,
                "/api/guilds/" + guildId + "/members/me", aliceToken, null);
        assertThat(ownerLeave.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(ownerLeave.getBody()).path("error").asText()).contains("Owner cannot leave");

        // 普通成员可以离开
        ResponseEntity<String> leave = raw(HttpMethod.DELETE,
                "/api/guilds/" + guildId + "/members/me", bobToken, null);
        assertThat(leave.getStatusCode()).isEqualTo(HttpStatus.OK);

        // owner 删除公会
        ResponseEntity<String> deleted = raw(HttpMethod.DELETE, "/api/guilds/" + guildId, aliceToken, null);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> gone = raw(HttpMethod.GET, "/api/guilds/" + guildId, aliceToken, null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void auditLog_recordsRoleAndChannelChanges() {
        String guildId = createGuild(aliceToken, "AuditGuild-" + System.nanoTime());

        // 建角色 → 建频道 → 改名 → 删除角色,分别应记入审计
        String roleId = post("/api/guilds/" + guildId + "/roles", aliceToken,
                Map.of("name", "Moderator", "color", 0xFF0000)).path("id").asText();
        post("/api/channels", aliceToken,
                Map.of("guild_id", guildId, "name", "general", "type", 0));
        send(HttpMethod.PATCH, "/api/guilds/" + guildId + "/roles/" + roleId, aliceToken,
                Map.of("name", "Admin"));

        ResponseEntity<String> audit = raw(HttpMethod.GET, "/api/guilds/" + guildId + "/audit-log",
                aliceToken, null);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entries = parse(audit.getBody());
        assertThat(entries.size()).isGreaterThanOrEqualTo(3);
        // 最近一条是角色更新(ROLE_UPDATE=11)
        assertThat(entries.get(0).path("type").asInt()).isEqualTo(11);
        assertThat(entries.get(0).path("details").asText()).contains("Admin");
        // 存在角色创建记录(ROLE_CREATE=10)
        boolean hasCreate = false;
        for (JsonNode e : entries) {
            if (e.path("type").asInt() == 10 && e.path("details").asText().contains("Moderator")) {
                hasCreate = true;
            }
        }
        assertThat(hasCreate).isTrue();
    }

    @Test
    void auditLog_requiresMembership() {
        String guildId = createGuild(aliceToken, "AuditDeny-" + System.nanoTime());
        // bob 不是成员 → 403
        ResponseEntity<String> denied = raw(HttpMethod.GET, "/api/guilds/" + guildId + "/audit-log",
                bobToken, null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
