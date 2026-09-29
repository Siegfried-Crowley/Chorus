package org.chorus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.chorus.voice.VoiceAudioRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实音频中继集成测试:两个二进制 WebSocket 客户端(Alice/Bob)走 /ws/voice。
 * 断言:转发且无回声、muted 丢弃、deaf 跳过、伪造 token 报错关连接、leave 清空频道。
 */
class VoiceAudioRelayIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private VoiceAudioRouter voiceAudioRouter;

    private String aliceToken;
    private String bobToken;
    private String guildId;
    private String voiceChannelId;
    private String aliceAllocToken;
    private String bobAllocToken;

    private static final String ALICE_ID = "1000000000000001";
    private static final String BOB_ID = "1000000000000002";

    @BeforeEach
    void setUp() {
        aliceToken = login("alice@test.com", "test123");
        bobToken = login("bob@test.com", "test123");

        // alice 建公会 → 拿到默认语音频道
        guildId = post("/api/guilds", aliceToken, Map.of("name", "VoiceGuild-" + System.nanoTime()))
                .path("id").asText();
        JsonNode channels = get("/api/guilds/" + guildId + "/channels", aliceToken);
        for (JsonNode c : channels) {
            if (c.path("type").asInt() == 2) {
                voiceChannelId = c.path("id").asText();
                break;
            }
        }
        assertThat(voiceChannelId).isNotNull();

        // bob 通过邀请加入
        String code = post("/api/guilds/" + guildId + "/invites", aliceToken, Map.of())
                .path("code").asText();
        post("/api/invites/" + code + "/join", bobToken, Map.of());

        // 两人都通过 REST 加入语音频道 → 拿到各自的语音分配 token(与 JWT 不同,join 帧用它)
        aliceAllocToken = joinVoice(aliceToken);
        bobAllocToken = joinVoice(bobToken);
    }

    private String joinVoice(String token) {
        return post("/api/voice/join", token, Map.of("guild_id", guildId, "channel_id", voiceChannelId, "session_id", "s-" + token))
                .path("token").asText();
    }

    private void setMute(String token, boolean mute) {
        post("/api/voice/mute", token, Map.of("guild_id", guildId, "mute", mute));
    }

    private void setDeaf(String token, boolean deaf) {
        post("/api/voice/deaf", token, Map.of("guild_id", guildId, "deaf", deaf));
    }

    @Test
    void audioRelay_forwardsWithoutEcho_mutesSkipsDeaf_rejectsBadTokenAndClearsOnLeave() throws Exception {
        AudioClient alice = new AudioClient(port, aliceAllocToken, voiceChannelId);
        AudioClient bob = new AudioClient(port, bobAllocToken, voiceChannelId);
        awaitUntil(alice::hasJoined, "alice joined");
        awaitUntil(bob::hasJoined, "bob joined");

        // 1) 转发且无回声:前缀 = 发送者 userId(8 字节大端),发送者自己收不到
        byte[] payload1 = "hello-audio".getBytes(StandardCharsets.UTF_8);
        alice.sendBinary(payload1);
        awaitUntil(() -> bob.binary().size() >= 1, "bob receives alice frame");
        assertThat(bob.binary().get(0)).isEqualTo(prefixed(ALICE_ID, payload1));
        assertThat(alice.binary()).isEmpty(); // 无回声

        // 2) muted 发送者被服务端丢弃
        setMute(aliceToken, true);
        alice.sendBinary("muted-1".getBytes(StandardCharsets.UTF_8));
        alice.sendBinary("muted-2".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(400);
        assertThat(bob.binary().size()).isEqualTo(1);

        // 3) unmute 后恢复转发
        setMute(aliceToken, false);
        byte[] payload2 = "resumed".getBytes(StandardCharsets.UTF_8);
        alice.sendBinary(payload2);
        awaitUntil(() -> bob.binary().size() >= 2, "bob receives after unmute");
        assertThat(bob.binary().get(1)).isEqualTo(prefixed(ALICE_ID, payload2));

        // 4) deaf 接收者被跳过
        setDeaf(bobToken, true);
        alice.sendBinary("deaf-1".getBytes(StandardCharsets.UTF_8));
        alice.sendBinary("deaf-2".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(400);
        assertThat(bob.binary().size()).isEqualTo(2);

        // 5) 伪造 token → error 帧 + 连接被关
        AudioClient mallory = new AudioClient(port, "forged-token", voiceChannelId);
        awaitUntil(mallory::closed, "bad token closed");
        assertThat(mallory.text()).anyMatch(t -> t.contains("\"error\"") && t.contains("4401"));

        // 6) 未 join 就发二进制 → close(1001)
        AudioClient stray = new AudioClient(port, "nope", voiceChannelId, true);
        stray.sendBinary("no-join".getBytes(StandardCharsets.UTF_8));
        awaitUntil(stray::closed, "unregistered binary closed");

        // 7) leave 清空频道:alice 离开后剩 bob(1 个会话),bob 离开后清空
        alice.leave();
        awaitUntil(() -> voiceAudioRouter.memberCount() == 1, "alice removed from router");
        bob.leave();
        awaitUntil(() -> voiceAudioRouter.memberCount() == 0, "router empty after leave");
    }

    // ===== WS 客户端 =====

    private static StandardWebSocketClient newClient() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024);
        container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
        return new StandardWebSocketClient(container);
    }

    /** 建立到 /ws/voice 的连接并(可选)发出 join 帧 */
    private static class AudioClient {
        final CollectingHandler handler = new CollectingHandler();
        final WebSocketSession session;

        AudioClient(int port, String token, String channelId) throws Exception {
            this.session = newClient()
                    .doHandshake(handler, "ws://localhost:" + port + "/ws/voice")
                    .get(5, TimeUnit.SECONDS);
            session.sendMessage(new TextMessage(
                    "{\"type\":\"join\",\"token\":\"" + token + "\",\"channelId\":\"" + channelId + "\"}"));
        }

        /** 只连接、不发 join 帧(测未注册二进制帧) */
        AudioClient(int port, String token, String channelId, boolean skipJoin) throws Exception {
            this.session = newClient()
                    .doHandshake(handler, "ws://localhost:" + port + "/ws/voice")
                    .get(5, TimeUnit.SECONDS);
        }

        void sendBinary(byte[] payload) throws Exception {
            session.sendMessage(new BinaryMessage(payload));
        }

        void leave() throws Exception {
            session.sendMessage(new TextMessage("{\"type\":\"leave\"}"));
        }

        List<byte[]> binary() { return handler.binary; }
        List<String> text() { return handler.text; }
        boolean hasJoined() { return handler.text.stream().anyMatch(t -> t.contains("\"joined\"")); }
        boolean closed() { return handler.closed; }
    }

    static class CollectingHandler extends TextWebSocketHandler {
        final List<byte[]> binary = new CopyOnWriteArrayList<>();
        final List<String> text = new CopyOnWriteArrayList<>();
        volatile boolean closed = false;

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            ByteBuffer b = message.getPayload();
            byte[] arr = new byte[b.remaining()];
            b.get(arr);
            binary.add(arr);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            text.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = true;
        }
    }

    // ===== 辅助 =====

    private static byte[] prefixed(String userId, byte[] payload) {
        byte[] prefix = new byte[8];
        ByteBuffer.wrap(prefix).putLong(Long.parseLong(userId));
        byte[] out = Arrays.copyOf(prefix, 8 + payload.length);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static void awaitUntil(Supplier<Boolean> cond, String msg) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting: " + msg);
    }

    private HttpEntity<Object> authed(String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return new HttpEntity<>(body, h);
    }

    private JsonNode post(String path, String token, Object body) {
        return send(HttpMethod.POST, path, token, body);
    }

    private JsonNode get(String path, String token) {
        return send(HttpMethod.GET, path, token, null);
    }

    private JsonNode send(HttpMethod method, String path, String token, Object body) {
        ResponseEntity<String> r = rest.exchange(url(path), method, authed(token, body), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String json = r.getBody();
        if (json == null || json.isBlank()) return null; // 如 /voice/mute、/voice/deaf 返回空 200
        try { return mapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
