package org.chorus.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.chorus.dto.request.ChangePasswordRequest;
import org.chorus.dto.request.LoginRequest;
import org.chorus.dto.request.RegisterRequest;
import org.chorus.dto.request.TwoFactorVerifyRequest;
import org.chorus.dto.request.VerifyEmailRequest;
import org.chorus.dto.response.AuthResponse;
import org.chorus.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.ok(authService.register(req));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        return ResponseEntity.ok(authService.verifyEmail(req));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/2fa/verify")
    public ResponseEntity<AuthResponse> verify2fa(@Valid @RequestBody TwoFactorVerifyRequest req) {
        return ResponseEntity.ok(authService.verify2fa(req.getMfaToken(), req.getCode()));
    }

    @PostMapping("/2fa/enable")
    public ResponseEntity<Map<String, Object>> enable2fa(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(authService.enable2fa(userId));
    }

    @PostMapping("/2fa/disable")
    public ResponseEntity<Map<String, Object>> disable2fa(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(authService.disable2fa(userId));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                              Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long userId = (Long) auth.getPrincipal();
        authService.changePassword(userId, req);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse.UserInfo> me(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(authService.getCurrentUser(userId));
    }
}
