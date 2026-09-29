package org.discord.controller;

import lombok.RequiredArgsConstructor;
import org.discord.dto.request.CreateMessageRequest;
import org.discord.entity.Message;
import org.discord.gateway.GatewayWebSocketHandler;
import org.discord.service.MessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/channels/{channelId}/messages")
@RequiredArgsConstructor
public class MessageController {
    private final MessageService messageService;
    private final GatewayWebSocketHandler gatewayHandler;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @PathVariable Long channelId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<Message> messages = messageService.getMessages(channelId, userId, limit, before, after);
        List<Map<String, Object>> result = messages.stream()
                .map(messageService::toJson)
                .toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createMessage(
            @PathVariable Long channelId,
            @RequestBody CreateMessageRequest req,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Message message = messageService.createMessage(
                channelId, userId, req.getContent(), req.getNonce(),
                req.getMessageReference(), req.getAttachments());
        // 实时广播 MESSAGE_CREATE 给公会成员 / DM 成员
        gatewayHandler.dispatchMessage(channelId, message);
        return ResponseEntity.ok(messageService.toJson(message));
    }

    @PatchMapping("/{messageId}")
    public ResponseEntity<Map<String, Object>> updateMessage(
            @PathVariable Long channelId,
            @PathVariable Long messageId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Message message = messageService.updateMessage(
                channelId, messageId, userId, body.get("content"));
        Map<String, Object> json = messageService.toJson(message);
        gatewayHandler.dispatchChannelEvent(channelId, "MESSAGE_UPDATE", json, null);
        return ResponseEntity.ok(json);
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable Long channelId,
            @PathVariable Long messageId,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        messageService.deleteMessage(channelId, messageId, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("channel_id", channelId.toString());
        data.put("id", messageId.toString());
        gatewayHandler.dispatchChannelEvent(channelId, "MESSAGE_DELETE", data, null);
        return ResponseEntity.ok().build();
    }

    // ========== 反应 ==========

    @PutMapping("/{messageId}/reactions/{emoji}")
    public ResponseEntity<Map<String, Object>> addReaction(
            @PathVariable Long channelId,
            @PathVariable Long messageId,
            @PathVariable String emoji,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String decoded = URLDecoder.decode(emoji, StandardCharsets.UTF_8);
        Message message = messageService.addReaction(channelId, messageId, userId, decoded);
        Map<String, Object> data = new HashMap<>();
        data.put("channel_id", channelId.toString());
        data.put("message_id", messageId.toString());
        data.put("emoji", decoded);
        data.put("user_id", userId.toString());
        gatewayHandler.dispatchChannelEvent(channelId, "MESSAGE_REACTION_ADD", data, userId);
        return ResponseEntity.ok(messageService.toJson(message));
    }

    @DeleteMapping("/{messageId}/reactions/{emoji}")
    public ResponseEntity<Map<String, Object>> removeReaction(
            @PathVariable Long channelId,
            @PathVariable Long messageId,
            @PathVariable String emoji,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String decoded = URLDecoder.decode(emoji, StandardCharsets.UTF_8);
        Message message = messageService.removeReaction(channelId, messageId, userId, decoded);
        Map<String, Object> data = new HashMap<>();
        data.put("channel_id", channelId.toString());
        data.put("message_id", messageId.toString());
        data.put("emoji", decoded);
        data.put("user_id", userId.toString());
        gatewayHandler.dispatchChannelEvent(channelId, "MESSAGE_REACTION_REMOVE", data, userId);
        return ResponseEntity.ok(messageService.toJson(message));
    }

    // ========== 置顶 ==========

    @GetMapping("/pins")
    public ResponseEntity<List<Map<String, Object>>> getPins(@PathVariable Long channelId,
                                                              Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<Map<String, Object>> result = messageService.getPinnedMessages(channelId, userId).stream()
                .map(messageService::toJson).toList();
        return ResponseEntity.ok(result);
    }

    @PutMapping("/pins/{messageId}")
    public ResponseEntity<Map<String, Object>> pinMessage(
            @PathVariable Long channelId,
            @PathVariable Long messageId,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Message message = messageService.pinMessage(channelId, messageId, userId);
        broadcastPinsUpdate(channelId);
        return ResponseEntity.ok(messageService.toJson(message));
    }

    @DeleteMapping("/pins/{messageId}")
    public ResponseEntity<Map<String, Object>> unpinMessage(
            @PathVariable Long channelId,
            @PathVariable Long messageId,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Message message = messageService.unpinMessage(channelId, messageId, userId);
        broadcastPinsUpdate(channelId);
        return ResponseEntity.ok(messageService.toJson(message));
    }

    private void broadcastPinsUpdate(Long channelId) {
        Map<String, Object> data = new HashMap<>();
        data.put("channel_id", channelId.toString());
        gatewayHandler.dispatchChannelEvent(channelId, "CHANNEL_PINS_UPDATE", data, null);
    }

    // ========== 输入中 ==========

    @PostMapping("/typing")
    public ResponseEntity<Void> typing(@PathVariable Long channelId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        messageService.validateTyping(channelId, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("channel_id", channelId.toString());
        data.put("user_id", userId.toString());
        gatewayHandler.dispatchChannelEvent(channelId, "TYPING_START", data, null);
        return ResponseEntity.ok().build();
    }
}
