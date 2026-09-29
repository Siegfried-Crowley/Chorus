package org.discord.controller;

import lombok.RequiredArgsConstructor;
import org.discord.gateway.GatewayWebSocketHandler;
import org.discord.service.VoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceController {
    private final VoiceService voiceService;
    private final GatewayWebSocketHandler gatewayHandler;

    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> joinVoice(@RequestBody Map<String, String> body,
                                                          Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long guildId = Long.parseLong(body.get("guild_id"));
        Long channelId = Long.parseLong(body.get("channel_id"));
        String sessionId = body.get("session_id");

        Map<String, Object> result = voiceService.joinVoice(guildId, channelId, userId, sessionId, null, null);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/leave")
    public ResponseEntity<Void> leaveVoice(@RequestBody Map<String, String> body,
                                            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long guildId = Long.parseLong(body.get("guild_id"));
        voiceService.leaveVoice(guildId, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/mute")
    public ResponseEntity<Void> mute(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long guildId = Long.parseLong(body.get("guild_id").toString());
        boolean mute = (boolean) body.get("mute");
        voiceService.updateSelfMute(guildId, userId, mute);
        gatewayHandler.broadcastVoiceState(guildId, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/deaf")
    public ResponseEntity<Void> deaf(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long guildId = Long.parseLong(body.get("guild_id").toString());
        boolean deaf = (boolean) body.get("deaf");
        voiceService.updateSelfDeaf(guildId, userId, deaf);
        gatewayHandler.broadcastVoiceState(guildId, userId);
        return ResponseEntity.ok().build();
    }
}
