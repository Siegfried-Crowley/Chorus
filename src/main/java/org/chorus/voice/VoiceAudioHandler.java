package org.chorus.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.chorus.entity.VoiceAllocation;
import org.chorus.entity.VoiceState;
import org.chorus.repository.VoiceAllocationRepository;
import org.chorus.repository.VoiceStateRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Optional;

/**
 * 真实音频中继的专用 WebSocket handler,注册于 {@code /ws/voice}(独立于网关 {@code /ws})。
 *
 * <p>控制帧(文本):{@code {"type":"join","token":"...","channelId":"..."}} 首帧必须 join,
 * 用 {@link VoiceAllocationRepository#findByToken} 鉴权(拿回 guildId/channelId/userId),
 * 再校验该用户仍处于此语音频道;此后可发 {@code leave / ping / pong / error}。
 * 二进制帧:整帧透明转发给频道内其他成员(前缀 8 字节大端发送者 userId)。
 * 未 join 就发二进制帧 → close(1001)。
 */
@Component
@RequiredArgsConstructor
public class VoiceAudioHandler extends AbstractWebSocketHandler {

    private final VoiceAudioRouter router;
    private final VoiceAllocationRepository allocationRepository;
    private final VoiceStateRepository voiceStateRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendErrorAndClose(session, 4401, "Malformed frame");
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "join" -> handleJoin(session, node);
            case "leave" -> {
                router.removeSession(session.getId());
                if (session.isOpen()) session.close(CloseStatus.NORMAL);
            }
            case "ping" -> {
                if (session.isOpen()) session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            }
            default -> { /* 未知控制帧忽略 */ }
        }
    }

    private void handleJoin(WebSocketSession session, JsonNode node) throws Exception {
        String token = node.path("token").asText(null);
        if (token == null || token.isBlank()) {
            sendErrorAndClose(session, 4401, "Missing token");
            return;
        }
        VoiceAllocation alloc = allocationRepository.findByToken(token).orElse(null);
        if (alloc == null) {
            sendErrorAndClose(session, 4401, "Invalid token");
            return;
        }
        // 校验 token 未过期(joinVoice 分配的 token 有效期为 30 秒)
        if (alloc.getExpiresAt() != null && alloc.getExpiresAt().isBefore(java.time.Instant.now())) {
            sendErrorAndClose(session, 4401, "Token expired");
            return;
        }
        // 校验该用户当前仍在语音频道(防止用旧 token 顶替别人位置)
        Optional<VoiceState> vs = voiceStateRepository
                .findByGuildIdAndUserId(alloc.getGuildId(), alloc.getUserId());
        if (vs.isEmpty() || !vs.get().getChannelId().equals(alloc.getChannelId())) {
            sendErrorAndClose(session, 4401, "Not in channel");
            return;
        }
        // 用 decorator 包装:发送超过 5s 或 512KB 积压即关闭该慢会话,中继不阻塞
        ConcurrentWebSocketSessionDecorator decorated =
                new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024);
        router.register(decorated, alloc.getUserId().toString(), alloc.getChannelId().toString());
        if (session.isOpen()) {
            session.sendMessage(new TextMessage("{\"type\":\"joined\",\"userId\":\""
                    + alloc.getUserId() + "\",\"channelId\":\"" + alloc.getChannelId() + "\"}"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        if (!router.isRegistered(session.getId())) {
            if (session.isOpen()) session.close(CloseStatus.GOING_AWAY); // 1001:未 join 即发音频
            return;
        }
        router.forward(session.getId(), message.getPayload().array());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        router.removeSession(session.getId());
    }

    private void sendErrorAndClose(WebSocketSession session, int code, String reason) throws Exception {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"error\",\"code\":" + code + ",\"message\":\"" + reason + "\"}"));
            session.close(new CloseStatus(code, reason));
        }
    }
}
