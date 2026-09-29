package org.chorus.controller;

import lombok.RequiredArgsConstructor;
import org.chorus.service.MessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/guilds/{guildId}/messages")
@RequiredArgsConstructor
public class GuildMessageController {
    private final MessageService messageService;

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> search(
            @PathVariable Long guildId,
            @RequestParam String query,
            @RequestParam(required = false) Long channel_id,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<Map<String, Object>> result = messageService
                .searchMessages(guildId, channel_id, query, userId).stream()
                .map(messageService::toJson).toList();
        return ResponseEntity.ok(result);
    }
}
