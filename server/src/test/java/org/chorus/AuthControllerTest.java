package org.chorus;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest extends BaseIntegrationTest {

    @Test
    void login_withValidCredentials_returnsToken() {
        ResponseEntity<Map> r = rest.postForEntity(
                url("/api/auth/login"),
                Map.of("email", "alice@test.com", "password", "test123"),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("token");
        assertThat(((Map) r.getBody().get("user")).get("username")).isEqualTo("Alice");
    }

    @Test
    void login_withWrongPassword_returns401Json() {
        ResponseEntity<Map> r = rest.postForEntity(
                url("/api/auth/login"),
                Map.of("email", "alice@test.com", "password", "WRONG"),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody()).containsKey("error");
    }

    @Test
    void register_requiresEmailVerification() {
        String email = "newuser-" + System.nanoTime() + "@test.com";
        ResponseEntity<Map> r = rest.postForEntity(
                url("/api/auth/register"),
                Map.of("username", "Newbie", "email", email, "password", "pass123"),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 注册后需要邮箱验证,不直接发 token
        assertThat(r.getBody()).containsEntry("requires_verification", true);
        assertThat(r.getBody()).containsKey("email");
        assertThat(r.getBody()).doesNotContainKey("token");
    }

    @Test
    void me_withToken_returnsUser() {
        String token = login("alice@test.com", "test123");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Map> r = rest.exchange(url("/api/auth/me"), HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("username")).isEqualTo("Alice");
    }

    @Test
    void me_withoutToken_isUnauthorized() {
        ResponseEntity<Map> r = rest.getForEntity(url("/api/auth/me"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
