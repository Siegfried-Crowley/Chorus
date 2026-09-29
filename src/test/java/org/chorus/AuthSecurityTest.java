package org.chorus;

import org.chorus.entity.User;
import org.chorus.repository.UserRepository;
import org.chorus.service.CacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 账号/安全:
 * - 邮箱验证流程(注册→未验证不能登录→验证码验证→可登录)
 * - 改密码(旧密码校验,改后旧密码失效)
 * - 2FA 两步登录(enable→login 返回 requires_2fa→验证码→真 token)
 */
class AuthSecurityTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CacheService cacheService;

    /** 注册唯一邮箱,返回 {email, userId} */
    private Map<String, Object> registerUser(String prefix) {
        String email = prefix + "-" + System.nanoTime() + "@test.com";
        ResponseEntity<Map> r = rest.postForEntity(
                url("/api/auth/register"),
                Map.of("username", prefix, "email", email, "password", "pass123"),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        User user = userRepository.findByEmail(email).orElseThrow();
        return Map.of("email", email, "userId", user.getId());
    }

    private String emailCode(Long userId) {
        return cacheService.get("verify_email:" + userId);
    }

    private String mfaCode(Long userId) {
        return cacheService.get("2fa:" + userId);
    }

    private String verifyAndGetToken(String email, String code) {
        ResponseEntity<Map> r = rest.postForEntity(
                url("/api/auth/verify-email"),
                Map.of("email", email, "code", code),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("token");
    }

    @Test
    void emailVerificationFlow() {
        Map<String, Object> reg = registerUser("verifyflow");
        String email = (String) reg.get("email");
        Long userId = (Long) reg.get("userId");

        // 未验证 → 登录 403
        ResponseEntity<Map> denied = rest.postForEntity(
                url("/api/auth/login"),
                Map.of("email", email, "password", "pass123"),
                Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody()).containsEntry("error", "Please verify your email first");

        // 用缓存里的验证码验证 → 自动登录拿到 token
        String code = emailCode(userId);
        assertThat(code).isNotBlank();
        String token = verifyAndGetToken(email, code);
        assertThat(token).isNotBlank();

        // 验证后登录正常
        ResponseEntity<Map> ok = rest.postForEntity(
                url("/api/auth/login"),
                Map.of("email", email, "password", "pass123"),
                Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsKey("token");
    }

    @Test
    void wrongVerificationCode_isUnauthorized() {
        Map<String, Object> reg = registerUser("badcode");
        String email = (String) reg.get("email");
        ResponseEntity<Map> r = rest.postForEntity(
                url("/api/auth/verify-email"),
                Map.of("email", email, "code", "000000"),
                Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody()).containsEntry("error", "Invalid verification code");
    }

    @Test
    void changePassword_requiresOldPassword() {
        Map<String, Object> reg = registerUser("changepw");
        String email = (String) reg.get("email");
        Long userId = (Long) reg.get("userId");
        String token = verifyAndGetToken(email, emailCode(userId));

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);

        // 旧密码错误 → 401
        ResponseEntity<Map> bad = rest.exchange(
                url("/api/auth/change-password"), HttpMethod.POST,
                new HttpEntity<>(Map.of("oldPassword", "WRONG", "newPassword", "newpass456"), h), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 正确改密
        ResponseEntity<Map> ok = rest.exchange(
                url("/api/auth/change-password"), HttpMethod.POST,
                new HttpEntity<>(Map.of("oldPassword", "pass123", "newPassword", "newpass456"), h), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 旧密码失效,新密码可用
        ResponseEntity<Map> oldLogin = rest.postForEntity(
                url("/api/auth/login"), Map.of("email", email, "password", "pass123"), Map.class);
        assertThat(oldLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> newLogin = rest.postForEntity(
                url("/api/auth/login"), Map.of("email", email, "password", "newpass456"), Map.class);
        assertThat(newLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(newLogin.getBody()).containsKey("token");
    }

    @Test
    void twoFactor_twoStepLogin() {
        Map<String, Object> reg = registerUser("mfaflow");
        String email = (String) reg.get("email");
        Long userId = (Long) reg.get("userId");
        String token = verifyAndGetToken(email, emailCode(userId));

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);

        // 开启 2FA
        ResponseEntity<Map> enabled = rest.exchange(
                url("/api/auth/2fa/enable"), HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enabled.getBody()).containsEntry("mfa_enabled", true);

        // 再次开启 → 400 already
        ResponseEntity<Map> again = rest.exchange(
                url("/api/auth/2fa/enable"), HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getBody()).containsEntry("error", "2FA already enabled");

        // 登录 → 返回 requires_2fa + mfa_token,不含 token
        ResponseEntity<Map> step1 = rest.postForEntity(
                url("/api/auth/login"), Map.of("email", email, "password", "pass123"), Map.class);
        assertThat(step1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(step1.getBody()).containsEntry("requires_2fa", true);
        assertThat(step1.getBody()).containsKey("mfa_token");
        assertThat(step1.getBody()).doesNotContainKey("token");

        // 用缓存里的验证码完成第二步 → 拿到真 token
        String mfaToken = (String) step1.getBody().get("mfa_token");
        String code = mfaCode(userId);
        assertThat(code).isNotBlank();
        ResponseEntity<Map> step2 = rest.postForEntity(
                url("/api/auth/2fa/verify"),
                Map.of("mfaToken", mfaToken, "code", code),
                Map.class);
        assertThat(step2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(step2.getBody()).containsKey("token");

        // mfa_token 单次有效:成功后复用 → 401 Invalid mfa token
        ResponseEntity<Map> reuse = rest.postForEntity(
                url("/api/auth/2fa/verify"),
                Map.of("mfaToken", mfaToken, "code", code),
                Map.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody()).containsEntry("error", "Invalid mfa token");

        // 重新登录拿新 mfa_token,错误验证码 → 401 Invalid 2FA code
        ResponseEntity<Map> login2 = rest.postForEntity(
                url("/api/auth/login"), Map.of("email", email, "password", "pass123"), Map.class);
        String freshToken = (String) login2.getBody().get("mfa_token");
        ResponseEntity<Map> bad = rest.postForEntity(
                url("/api/auth/2fa/verify"),
                Map.of("mfaToken", freshToken, "code", "000000"),
                Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody()).containsEntry("error", "Invalid 2FA code");
    }
}
