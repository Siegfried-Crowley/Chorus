package org.chorus;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验安全配置的关键回归点：
 * - /api/auth/**、/api/voice/** 已从 permitAll 收紧为需要登录
 * - GlobalExceptionHandler 接住业务异常返回 JSON，而不是被 /error 拦成 403 空响应
 */
class SecurityConfigTest extends BaseIntegrationTest {

    @Test
    void voiceEndpoints_requireAuthentication() {
        // 无 token → 401（此前 /api/voice/** 被错误地 permitAll;匿名访问统一返回 401）
        ResponseEntity<Void> r = rest.postForEntity(url("/api/voice/join"), Map.of(), Void.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoints_requireToken() {
        // 无凭证 → 401
        ResponseEntity<Void> r = rest.getForEntity(url("/api/guilds"), Void.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongPassword_returnsJsonNot403Html() {
        // 关键回归：业务异常应返回 JSON 401（Invalid credentials），而不是被安全层拦成 403 空响应
        ResponseEntity<Map> r = rest.postForEntity(
                url("/api/auth/login"),
                Map.of("email", "alice@test.com", "password", "WRONG"),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody()).containsEntry("error", "Invalid credentials");
    }

    @Test
    void authWithToken_works() {
        String token = login("alice@test.com", "test123");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Object> r = rest.exchange(url("/api/guilds"), HttpMethod.GET, new HttpEntity<>(h), Object.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
