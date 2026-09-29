package org.chorus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoleCrudTest extends BaseIntegrationTest {

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
    void roleCrud_andEveryoneProtected() {
        String guildId = createGuild(aliceToken, "RoleGuild-" + System.nanoTime());

        // 初始只有 @everyone(role id == guild id)
        JsonNode roles = send(HttpMethod.GET, "/api/guilds/" + guildId + "/roles", aliceToken, null);
        assertThat(roles.size()).isEqualTo(1);
        assertThat(roles.get(0).path("id").asText()).isEqualTo(guildId);

        // 创建角色
        JsonNode created = post("/api/guilds/" + guildId + "/roles", aliceToken,
                Map.of("name", "Moderator", "color", 0xff0000,
                        "permissions", "2146958591", "hoist", true, "mentionable", true));
        assertThat(created.path("name").asText()).isEqualTo("Moderator");
        String roleId = created.path("id").asText();

        // 更新角色
        JsonNode updated = send(HttpMethod.PATCH, "/api/guilds/" + guildId + "/roles/" + roleId,
                aliceToken, Map.of("name", "Admin+"));
        assertThat(updated.path("name").asText()).isEqualTo("Admin+");

        // 无 MANAGE_ROLES 的成员不能创建角色
        String code = createInvite(aliceToken, guildId);
        post("/api/invites/" + code + "/join", bobToken, Map.of());
        ResponseEntity<String> denied = raw(HttpMethod.POST, "/api/guilds/" + guildId + "/roles",
                bobToken, Map.of("name", "Hacker"));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(denied.getBody()).path("error").asText()).contains("MANAGE_ROLES");

        // 删除角色
        ResponseEntity<String> deleted = raw(HttpMethod.DELETE,
                "/api/guilds/" + guildId + "/roles/" + roleId, aliceToken, null);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);

        // @everyone 不可删
        ResponseEntity<String> delEveryone = raw(HttpMethod.DELETE,
                "/api/guilds/" + guildId + "/roles/" + guildId, aliceToken, null);
        assertThat(delEveryone.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(delEveryone.getBody()).path("error").asText()).contains("Cannot delete @everyone");
    }

    @Test
    void assignRoles_toMember() {
        String guildId = createGuild(aliceToken, "Assign-" + System.nanoTime());
        String code = createInvite(aliceToken, guildId);
        post("/api/invites/" + code + "/join", bobToken, Map.of());

        JsonNode role = post("/api/guilds/" + guildId + "/roles", aliceToken,
                Map.of("name", "VIP"));
        String roleId = role.path("id").asText();

        ResponseEntity<String> assign = raw(HttpMethod.PUT,
                "/api/guilds/" + guildId + "/members/1000000000000002/roles",
                aliceToken, Map.of("role_ids", new String[]{roleId}));
        assertThat(assign.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 给不存在的角色分配 → 404 Role not found
        ResponseEntity<String> badRole = raw(HttpMethod.PUT,
                "/api/guilds/" + guildId + "/members/1000000000000002/roles",
                aliceToken, Map.of("role_ids", new String[]{"9999999999999999"}));
        assertThat(badRole.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
