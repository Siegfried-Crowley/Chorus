package org.chorus.controller;

import lombok.RequiredArgsConstructor;
import org.chorus.service.DmService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dm")
@RequiredArgsConstructor
public class DmController {
    private final DmService dmService;

    @GetMapping("/channels")
    public ResponseEntity<List<Map<String, Object>>> listChannels(Authentication auth) {
        return ResponseEntity.ok(dmService.listDmChannels((Long) auth.getPrincipal()));
    }

    @PostMapping("/channels")
    public ResponseEntity<Map<String, Object>> openDm(@RequestBody Map<String, String> body,
                                                       Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long otherUserId = Long.parseLong(body.get("user_id"));
        return ResponseEntity.ok(dmService.openDm(userId, otherUserId));
    }
}
