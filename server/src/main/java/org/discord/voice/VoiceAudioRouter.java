package org.discord.voice;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音/视频媒体中继路由器(纯进程内,无外部依赖)。
 *
 * <p>媒体 kind 标签(向后兼容):客户端上行帧首字节 ∈ {0,1,2} 时视为 kind 标签,
 * 其余取值(旧客户端 WebM EBML 头固定 0x1A)按 kind=0 音频处理。
 * kind: 0=音频(Opus), 1=摄像头视频(VP8/VP9), 2=屏幕共享。
 *
 * <p>拓扑:
 * <pre>
 *   channels:  channelId → (wsSessionId → VoiceMember)
 *   sessionToUserId:   wsSessionId → userId
 *   sessionToChannel:  wsSessionId → channelId
 *   userIdToSession:   userId → wsSessionId  (最近一次加入)
 * </pre>
 *
 * <p>转发规则:
 * <ul>
 *   <li>跳过发送者本身(无回声);</li>
 *   <li>muted 发送者仅丢弃音频帧(kind=0),摄像头/屏幕共享照常转发;</li>
 *   <li>deafened 接收者被跳过(不接任何帧);</li>
 *   <li>每个被转发帧前缀 8 字节大端 {@code senderUserId},接收端据此路由到对应播放器;</li>
 *   <li>按 (发送者, kind) 缓存首个分块(WebM init segment),新人加入时逐 kind 重放 → 中途加入可解码。</li>
 * </ul>
 *
 * <p>会话用 {@link org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator}
 * 包装(见 {@link VoiceAudioHandler}),慢接收者超过 5s/512KB 会被强制关闭,中继不阻塞。
 */
@Component
public class VoiceAudioRouter {

    public static final int KIND_AUDIO = 0;
    public static final int KIND_CAMERA = 1;
    public static final int KIND_SCREEN = 2;

    private final Map<String, Map<String, VoiceMember>> channels = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToUserId = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToChannel = new ConcurrentHashMap<>();
    private final Map<String, String> userIdToSession = new ConcurrentHashMap<>();

    /** 一个已连接用户的媒体成员。muted/deafened/initChunks 由网关/REST 线程写入,转发线程读取 → volatile/并发容器 */
    public static class VoiceMember {
        public volatile boolean muted;
        public volatile boolean deafened;
        /** kind → 该流首个分块(WebM init segment),供新人重放 */
        public final Map<Integer, byte[]> initChunks = new ConcurrentHashMap<>();
        public final WebSocketSession session;
        public final String userId;

        VoiceMember(WebSocketSession session, String userId) {
            this.session = session;
            this.userId = userId;
        }
    }

    /** 帧首字节 ∈ {0,1,2} → kind 标签;否则(旧客户端)按音频处理 */
    static int detectKind(byte[] payload) {
        if (payload == null || payload.length == 0) return KIND_AUDIO;
        int b = payload[0] & 0xFF;
        return (b <= 2) ? b : KIND_AUDIO;
    }

    /** 加入频道:先移除同会话旧状态(幂等),再挂入;并把已有成员各 kind 的 initChunk 重放给新人 */
    public void register(WebSocketSession session, String userId, String channelId) {
        String sid = session.getId();
        removeSessionInternal(sid);
        Map<String, VoiceMember> members = channels.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>());
        VoiceMember member = new VoiceMember(session, userId);
        members.put(sid, member);
        sessionToUserId.put(sid, userId);
        sessionToChannel.put(sid, channelId);
        userIdToSession.put(userId, sid);

        // 新人中途加入:重放已有成员各 kind 的 init segment,保证其 MediaSource 能解码
        for (VoiceMember existing : members.values()) {
            if (existing == member) continue;
            for (byte[] init : existing.initChunks.values()) {
                sendPrefixed(member.session, existing.userId, init);
            }
        }
    }

    /** WS 会话断开:从所在频道移除 */
    public void removeSession(String sessionId) {
        removeSessionInternal(sessionId);
    }

    /** 用户离开(如 /voice/leave):按 userId 找到其当前会话并移除 */
    public void removeUserByUserId(String userId) {
        String sid = userIdToSession.get(userId);
        if (sid != null) removeSessionInternal(sid);
    }

    /** 静音/禁听状态同步(由 REST /voice/mute、/voice/deaf 调用);null 参数表示不改 */
    public void updateMemberState(String userId, Boolean muted, Boolean deafened) {
        String sid = userIdToSession.get(userId);
        if (sid == null) return;
        String channelId = sessionToChannel.get(sid);
        if (channelId == null) return;
        Map<String, VoiceMember> members = channels.get(channelId);
        if (members == null) return;
        VoiceMember m = members.get(sid);
        if (m == null) return;
        if (muted != null) m.muted = muted;
        if (deafened != null) m.deafened = deafened;
    }

    public boolean isRegistered(String sessionId) {
        return sessionToUserId.containsKey(sessionId);
    }

    /**
     * 转发一帧媒体。返回 false 表示已注册但被静音丢弃(调用方无需处理);
     * 未注册的调用方应先查 {@link #isRegistered}。
     */
    public boolean forward(String senderSessionId, byte[] payload) {
        String senderUserId = sessionToUserId.get(senderSessionId);
        String channelId = sessionToChannel.get(senderSessionId);
        if (senderUserId == null || channelId == null) return false;
        Map<String, VoiceMember> members = channels.get(channelId);
        if (members == null) return false;
        VoiceMember sender = members.get(senderSessionId);
        if (sender == null) return false;
        int kind = detectKind(payload);
        if (sender.muted && kind == KIND_AUDIO) return false; // 静音:只挡音频,视频/共享照常
        sender.initChunks.putIfAbsent(kind, payload); // 每个流首帧即 init segment
        for (VoiceMember m : members.values()) {
            if (m.session.getId().equals(senderSessionId)) continue; // 无回声
            if (m.deafened) continue; // 禁听:跳过
            sendPrefixed(m.session, senderUserId, payload);
        }
        return true;
    }

    /** 当前在线媒体会话总数(诊断/测试用) */
    public int memberCount() {
        return channels.values().stream().mapToInt(Map::size).sum();
    }

    private void removeSessionInternal(String sessionId) {
        String userId = sessionToUserId.remove(sessionId);
        String channelId = sessionToChannel.remove(sessionId);
        if (channelId != null) {
            Map<String, VoiceMember> members = channels.get(channelId);
            if (members != null) {
                members.remove(sessionId);
                if (members.isEmpty()) channels.remove(channelId);
            }
        }
        if (userId != null && userIdToSession.get(userId) != null
                && userIdToSession.get(userId).equals(sessionId)) {
            userIdToSession.remove(userId);
        }
    }

    /** 构造并发送 [8字节大端senderUserId][payload] 帧 */
    private void sendPrefixed(WebSocketSession session, String senderUserId, byte[] payload) {
        if (!session.isOpen()) return;
        try {
            ByteBuffer buf = ByteBuffer.allocate(8 + payload.length);
            buf.putLong(Long.parseLong(senderUserId));
            buf.put(payload);
            buf.flip();
            session.sendMessage(new BinaryMessage(buf));
        } catch (Exception e) {
            // 慢/断连接收者:decorator 会在超限时关闭会话,这里静默丢弃即可
        }
    }
}
