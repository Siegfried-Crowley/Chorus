package org.chorus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录限流 — 专用低阈值上下文(不干扰其它测试的共享 Spring 上下文):
 * app.rate-limit.login-per-minute=3,固定窗口按 IP 计数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.login-per-minute=3")
class RateLimitTest extends BaseIntegrationTest {

    @Test
    void login_rateLimited_afterThreeAttempts() {
        HttpHeaders headers = new HttpHeaders();
        // 固定伪造 IP,保证该桶内计数确定
        headers.set("X-Forwarded-For", "203.0.113.99");

        // 前 3 次放行(密码错误返回 401)
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> r = rest.exchange(
                    url("/api/auth/login"), HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", "alice@test.com", "password", "WRONG"), headers),
                    Map.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // 第 4 次 → 429
        ResponseEntity<Map> r = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "alice@test.com", "password", "WRONG"), headers),
                Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(429);
        assertThat(r.getBody()).containsKey("error");
    }
}
