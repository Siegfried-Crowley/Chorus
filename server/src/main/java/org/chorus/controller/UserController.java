package org.chorus.controller;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.User;
import org.chorus.service.AttachmentService;
import org.chorus.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final AttachmentService attachmentService;

    @PatchMapping("/me")
    public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody Map<String, Object> body,
                                                             Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = userService.updateProfile(userId,
                (String) body.get("username"),
                (String) body.get("global_name"),
                (String) body.get("about_me"));
        return ResponseEntity.ok(userService.toPublicJson(user));
    }

    @PostMapping("/me/avatar")
    public ResponseEntity<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file,
                                                            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Map<String, Object> meta = attachmentService.store(file);
        User user = userService.updateAvatar(userId, (String) meta.get("url"));
        Map<String, Object> result = new HashMap<>(userService.toPublicJson(user));
        result.put("avatar_url", meta.get("url"));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getProfile(@PathVariable Long userId) {
        return ResponseEntity.ok(userService.toPublicJson(userService.getUser(userId)));
    }
}
