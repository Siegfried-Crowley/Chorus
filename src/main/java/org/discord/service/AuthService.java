package org.discord.service;

import lombok.RequiredArgsConstructor;
import org.discord.dto.request.ChangePasswordRequest;
import org.discord.dto.request.LoginRequest;
import org.discord.dto.request.RegisterRequest;
import org.discord.dto.request.VerifyEmailRequest;
import org.discord.dto.response.AuthResponse;
import org.discord.entity.User;
import org.discord.exception.BadRequestException;
import org.discord.exception.ForbiddenException;
import org.discord.exception.NotFoundException;
import org.discord.exception.UnauthorizedException;
import org.discord.repository.UserRepository;
import org.discord.util.JwtUtil;
import org.discord.util.SnowflakeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration MFA_TTL = Duration.ofMinutes(5);
    private static final Duration ATTEMPT_TTL = Duration.ofMinutes(10);
    private static final int MAX_CODE_ATTEMPTS = 5;

    /** 密码学安全随机源(替代 Math.random 生成验证码) */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final SnowflakeGenerator snowflake;
    private final CacheService cacheService;

    // ===== 注册(需邮箱验证) =====

    @Transactional
    public Map<String, Object> register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new BadRequestException("Email already registered");
        }

        User user = User.builder()
                .id(snowflake.nextId())
                .username(req.getUsername())
                .discriminator(String.format("%04d", (int)(Math.random() * 9999)))
                .email(req.getEmail())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .locale("zh-CN")
                .verified(false)
                .flags(0)
                .premiumType(0)
                .lastSeen(Instant.now())
                .createdAt(Instant.now())
                .build();

        userRepository.save(user);
        sendEmailCode(user);

        log.info("User registered (email verification pending): {}", req.getEmail());
        return Map.of(
                "requires_verification", true,
                "email", req.getEmail()
        );
    }

    /** 邮箱验证:校验验证码 → 置为已验证 → 自动登录 */
    @Transactional
    public AuthResponse verifyEmail(VerifyEmailRequest req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new NotFoundException("User not found"));

        // 尝试次数限流:防止暴力枚举 6 位验证码
        if (codeAttempts("email:" + user.getId()) >= MAX_CODE_ATTEMPTS) {
            throw new UnauthorizedException("Too many verification attempts, please register again");
        }

        String cached = cacheService.get(emailCodeKey(user.getId()));
        if (cached == null) {
            throw new UnauthorizedException("Verification code expired, please register again");
        }
        if (!cached.equals(req.getCode().trim())) {
            incrementCodeAttempts("email:" + user.getId());
            throw new UnauthorizedException("Invalid verification code");
        }

        user.setVerified(true);
        userRepository.save(user);
        cacheService.delete(emailCodeKey(user.getId()));
        cacheService.delete(codeAttemptsKey("email:" + user.getId()));

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return AuthResponse.builder()
                .token(token)
                .sessionId(UUID.randomUUID().toString())
                .user(buildUserInfo(user))
                .build();
    }

    // ===== 登录(邮箱验证 + 2FA) =====

    public Map<String, Object> login(LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }

        if (!user.isVerified()) {
            throw new ForbiddenException("Please verify your email first");
        }

        if (user.isMfaEnabled()) {
            // 2FA 已开启 → 不发 token,打印一次性验证码,返回 mfa_token
            sendMfaCode(user);
            String mfaToken = UUID.randomUUID().toString().replace("-", "");
            cacheService.set(mfaTokenKey(mfaToken), user.getId().toString(), MFA_TTL);
            return Map.of(
                    "requires_2fa", true,
                    "mfa_token", mfaToken,
                    "user", Map.of(
                            "id", user.getId().toString(),
                            "email", user.getEmail(),
                            "username", user.getUsername()
                    )
            );
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return Map.of(
                "token", token,
                "session_id", UUID.randomUUID().toString(),
                "user", buildUserInfo(user)
        );
    }

    /** 2FA 第二步:校验验证码 → 返回真实 token */
    public AuthResponse verify2fa(String mfaToken, String code) {
        String userIdStr = cacheService.get(mfaTokenKey(mfaToken));
        if (userIdStr == null) {
            throw new UnauthorizedException("Invalid mfa token");
        }
        Long userId = Long.parseLong(userIdStr);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        // 尝试次数限流:防止暴力枚举 2FA 验证码
        if (codeAttempts("2fa:" + userId) >= MAX_CODE_ATTEMPTS) {
            throw new UnauthorizedException("Too many 2FA attempts, please login again");
        }

        String cached = cacheService.get(mfaCodeKey(userId));
        if (cached == null || !cached.equals(code.trim())) {
            incrementCodeAttempts("2fa:" + userId);
            throw new UnauthorizedException("Invalid 2FA code");
        }

        cacheService.delete(mfaTokenKey(mfaToken));
        cacheService.delete(mfaCodeKey(userId));
        cacheService.delete(codeAttemptsKey("2fa:" + userId));

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return AuthResponse.builder()
                .token(token)
                .sessionId(UUID.randomUUID().toString())
                .user(buildUserInfo(user))
                .build();
    }

    // ===== 2FA 设置 =====

    /** 开启 2FA:立即生效,验证码打印到日志(无真实 TOTP 服务) */
    @Transactional
    public Map<String, Object> enable2fa(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.isMfaEnabled()) {
            throw new BadRequestException("2FA already enabled");
        }
        user.setMfaEnabled(true);
        userRepository.save(user);
        sendMfaCode(user);
        return Map.of("mfa_enabled", true, "message", "验证码已打印到后端日志");
    }

    @Transactional
    public Map<String, Object> disable2fa(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        user.setMfaEnabled(false);
        userRepository.save(user);
        cacheService.delete(mfaCodeKey(userId));
        return Map.of("mfa_enabled", false);
    }

    // ===== 改密码 =====

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);
    }

    public AuthResponse.UserInfo getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return buildUserInfo(user);
    }

    // ===== 验证码辅助 =====

    private void sendEmailCode(User user) {
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        cacheService.set(emailCodeKey(user.getId()), code, CODE_TTL);
        log.info("[邮箱验证码] user={} email={} code={}", user.getId(), user.getEmail(), code);
    }

    private void sendMfaCode(User user) {
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        cacheService.set(mfaCodeKey(user.getId()), code, MFA_TTL);
        log.info("[2FA 验证码] user={} email={} code={}", user.getId(), user.getEmail(), code);
    }

    // ===== 验证码尝试限流 =====

    private int codeAttempts(String key) {
        String v = cacheService.get(codeAttemptsKey(key));
        return v != null ? Integer.parseInt(v) : 0;
    }

    private void incrementCodeAttempts(String key) {
        int n = codeAttempts(key) + 1;
        cacheService.set(codeAttemptsKey(key), String.valueOf(n), ATTEMPT_TTL);
    }

    private String codeAttemptsKey(String key) {
        return "code_attempts:" + key;
    }

    private String emailCodeKey(Long userId) {
        return "verify_email:" + userId;
    }

    private String mfaCodeKey(Long userId) {
        return "2fa:" + userId;
    }

    private String mfaTokenKey(String mfaToken) {
        return "2fa_login:" + mfaToken;
    }

    private AuthResponse.UserInfo buildUserInfo(User user) {
        return AuthResponse.UserInfo.builder()
                .id(user.getId().toString())
                .username(user.getUsername())
                .discriminator(user.getDiscriminator())
                .globalName(user.getGlobalName())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .banner(user.getBanner())
                .accentColor(user.getAccentColor())
                .aboutMe(user.getAboutMe())
                .locale(user.getLocale())
                .verified(user.isVerified())
                .mfaEnabled(user.isMfaEnabled())
                .flags(user.getFlags())
                .premiumType(user.getPremiumType())
                .build();
    }
}
