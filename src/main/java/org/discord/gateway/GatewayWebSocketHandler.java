package org.discord.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.discord.dto.GatewayMessage;
import org.discord.entity.DmChannelMember;
import org.discord.entity.Guild;
import org.discord.entity.GuildMember;
import org.discord.entity.VoiceState;
import org.discord.entity.Channel;
import org.discord.repository.ChannelRepository;
import org.discord.repository.DmChannelMemberRepository;
import org.discord.repository.DmChannelRepository;
import org.discord.repository.UserRepository;
import org.discord.service.*;
import org.discord.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Discord Gateway 协议实现 (v9)
 * 处理所有实时事件的分发、心跳、断线重连
 */
@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final JwtUtil jwtUtil;
    private final CacheService cache;
    private final MessageService messageService;
    private final GuildService guildService;
    private final VoiceService voiceService;
    private final FriendService friendService;
    private final ChannelRepository channelRepository;
    private final DmChannelRepository dmChannelRepository;
    private final DmChannelMemberRepository dmMemberRepository;
    private final UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Value("${app.gateway.resume-url:ws://localhost:3000/ws}")
    private String resumeGatewayUrl;

    private final Map<String, GatewaySession> sessions = new ConcurrentHashMap<>();
    private final Map<String, GatewaySession> sessionById = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4);
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    public GatewayWebSocketHandler(ObjectMapper mapper, JwtUtil jwtUtil,
                                    CacheService cache,
                                    MessageService messageService,
                                    GuildService guildService,
                                    VoiceService voiceService,
                                    FriendService friendService,
                                    ChannelRepository channelRepository,
                                    DmChannelRepository dmChannelRepository,
                                    DmChannelMemberRepository dmMemberRepository,
                                    UserRepository userRepository) {
        this.mapper = mapper;
        this.jwtUtil = jwtUtil;
        this.cache = cache;
        this.messageService = messageService;
        this.guildService = guildService;
        this.voiceService = voiceService;
        this.friendService = friendService;
        this.channelRepository = channelRepository;
        this.dmChannelRepository = dmChannelRepository;
        this.dmMemberRepository = dmMemberRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        log.info("Gateway connection established: {}", ws.getId());

        GatewaySession session = new GatewaySession();
        session.ws = ws;
        session.sessionId = UUID.randomUUID().toString().replace("-", "");
        session.status = GatewayStatus.AWAITING_IDENTIFY;
        session.lastHeartbeat = Instant.now();
        session.seq = 0;
        sessions.put(ws.getId(), session);

        // 发送 OP 10 Hello
        Map<String, Object> helloData = new HashMap<>();
        helloData.put("heartbeat_interval", 41250);
        send(ws, buildMessage(GatewayMessage.OP_HELLO, helloData, null, null));
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        GatewaySession session = sessions.get(ws.getId());
        if (session == null) return;

        try {
            JsonNode json = mapper.readTree(message.getPayload());
            int op = json.get("op").asInt();
            JsonNode data = json.get("d");

            switch (op) {
                case GatewayMessage.OP_HEARTBEAT -> handleHeartbeat(session);
                case GatewayMessage.OP_IDENTIFY -> handleIdentify(session, data);
                case GatewayMessage.OP_RESUME -> handleResume(session, data);
                case GatewayMessage.OP_PRESENCE_UPDATE -> handlePresenceUpdate(session, data);
                case GatewayMessage.OP_VOICE_STATE_UPDATE -> handleVoiceStateUpdate(session, data);
                case GatewayMessage.OP_REQUEST_GUILD_MEMBERS -> handleRequestGuildMembers(session, data);
                default -> send(ws, error("Unknown opcode: " + op, 4001));
            }
        } catch (Exception e) {
            log.error("Error handling message", e);
            send(ws, error("Parse error", 4002));
        }
    }

    private void handleHeartbeat(GatewaySession session) {
        session.lastHeartbeat = Instant.now();
        send(session.ws, buildMessage(GatewayMessage.OP_HEARTBEAT_ACK, null, null, null));
    }

    /** 为会话注册心跳超时监控任务（Identify 与 Resume 共用） */
    private void scheduleHeartbeatTimeout(GatewaySession session) {
        // 防止同一 sessionId 重复注册导致任务泄漏
        ScheduledFuture<?> old = heartbeatTasks.remove(session.sessionId);
        if (old != null) old.cancel(false);

        ScheduledFuture<?> task = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (session.ws.isOpen()) {
                long elapsed = Duration.between(session.lastHeartbeat, Instant.now()).toMillis();
                if (elapsed > 125000) {
                    log.warn("Heartbeat timeout for session {}", session.sessionId);
                    try { session.ws.close(CloseStatus.SESSION_NOT_RELIABLE); }
                    catch (IOException ignored) {}
                }
            }
        }, 41250, 41250, TimeUnit.MILLISECONDS);
        heartbeatTasks.put(session.sessionId, task);
    }

    @SuppressWarnings("unchecked")
    private void handleIdentify(GatewaySession session, JsonNode data) {
        try {
            String token = data.get("token").asText();
            if (!jwtUtil.validateToken(token)) {
                send(session.ws, invalidSession());
                return;
            }

            Long userId = jwtUtil.getUserIdFromToken(token);
            session.userId = userId;
            session.status = GatewayStatus.READY;
            sessionById.put(session.sessionId, session);

            // 心跳超时检测任务
            scheduleHeartbeatTimeout(session);

            // 构建 READY 事件（用真实用户名）
            Map<String, Object> readyData = new HashMap<>();
            readyData.put("v", 9);
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", userId.toString());
            userInfo.put("username", userRepository.findById(userId)
                    .map(u -> u.getUsername() != null ? u.getUsername() : "User")
                    .orElse("User"));
            readyData.put("user", userInfo);
            readyData.put("session_id", session.sessionId);
            readyData.put("resume_gateway_url", resumeGatewayUrl);

            // 用户公会列表
            List<Guild> guilds = guildService.getUserGuilds(userId);
            List<Map<String, String>> guildList = new ArrayList<>();
            Set<Long> guildIds = new HashSet<>();
            for (Guild g : guilds) {
                guildList.add(Map.of("id", g.getId().toString(), "name", g.getName()));
                guildIds.add(g.getId());
            }
            readyData.put("guilds", guildList);
            session.guildIds = guildIds;
            session.friendIds = new HashSet<>(friendService.getFriendUserIds(userId));

            // 好友关系
            readyData.put("relationships", friendService.getRelationships(userId));

            send(session.ws, buildMessage(GatewayMessage.OP_DISPATCH, readyData, "READY", session.seq++));

            // 登录后向好友广播在线状态
            broadcastPresenceToFriends(session, "online");

            // 缓存到 CacheService 用于 resume
            cache.set("session:" + session.sessionId, userId.toString(),
                    java.time.Duration.ofSeconds(30));

            log.info("User {} identified with session {}", userId, session.sessionId);

        } catch (Exception e) {
            log.error("Identify error", e);
            send(session.ws, invalidSession());
        }
    }

    private void handleResume(GatewaySession session, JsonNode data) {
        try {
            String token = data.get("token").asText();
            String oldSessionId = data.get("session_id").asText();

            if (!jwtUtil.validateToken(token)) {
                send(session.ws, invalidSession());
                return;
            }

            String userIdStr = cache.get("session:" + oldSessionId);
            if (userIdStr == null) {
                send(session.ws, invalidSession());
                return;
            }

            session.userId = Long.parseLong(userIdStr);
            session.sessionId = oldSessionId;
            session.status = GatewayStatus.READY;
            sessionById.put(session.sessionId, session);

            // Resume 后重建订阅集合(否则重连期间收不到公会事件,断开时语音状态泄漏)
            List<Guild> resumeGuilds = guildService.getUserGuilds(session.userId);
            Set<Long> resumeGuildIds = new HashSet<>();
            for (Guild g : resumeGuilds) resumeGuildIds.add(g.getId());
            session.guildIds = resumeGuildIds;
            session.friendIds = new HashSet<>(friendService.getFriendUserIds(session.userId));
            cache.set("session:" + session.sessionId, session.userId.toString(),
                    java.time.Duration.ofSeconds(30));

            // Resume 的会话同样需要心跳超时监控（与 Identify 保持一致）
            session.lastHeartbeat = Instant.now();
            scheduleHeartbeatTimeout(session);

            send(session.ws, buildMessage(GatewayMessage.OP_DISPATCH,
                    Collections.emptyMap(), "RESUMED", session.seq++));

            log.info("Session {} resumed for user {}", oldSessionId, session.userId);

        } catch (Exception e) {
            log.error("Resume error", e);
            send(session.ws, invalidSession());
        }
    }

    @SuppressWarnings("unchecked")
    private void handlePresenceUpdate(GatewaySession session, JsonNode data) {
        if (session.userId == null) return;
        String status = data.has("status") ? data.get("status").asText() : "online";

        // 广播给共同公会
        List<Guild> guilds = guildService.getUserGuilds(session.userId);
        for (Guild guild : guilds) {
            Map<String, Object> presenceData = new HashMap<>();
            presenceData.put("user_id", session.userId.toString());
            presenceData.put("status", status);
            presenceData.put("activities", List.of());

            broadcastToGuild(guild.getId(), buildMessage(GatewayMessage.OP_DISPATCH,
                    presenceData, "PRESENCE_UPDATE", session.seq++), session.userId);
        }
        // 广播给好友
        broadcastPresenceToFriends(session, status);
    }

    /** 把某用户在线状态广播给他的好友 */
    private void broadcastPresenceToFriends(GatewaySession session, String status) {
        if (session.userId == null) return;
        Map<String, Object> presenceData = new HashMap<>();
        presenceData.put("user_id", session.userId.toString());
        presenceData.put("status", status);
        presenceData.put("activities", List.of());
        String payload = toJsonString(buildMessage(GatewayMessage.OP_DISPATCH,
                presenceData, "PRESENCE_UPDATE", session.seq++));

        // 该用户的在线好友
        if (session.friendIds.isEmpty()) return;
        for (GatewaySession other : sessions.values()) {
            if (other.status == GatewayStatus.READY && other.userId != null
                    && session.friendIds.contains(other.userId)) {
                sendRaw(other.ws, payload);
            }
        }

        // 给该用户推好友的当前在线状态快照（首次登录时）
        if ("online".equals(status)) {
            for (GatewaySession other : sessions.values()) {
                if (other.status == GatewayStatus.READY && other.userId != null
                        && session.friendIds.contains(other.userId)) {
                    Map<String, Object> snap = new HashMap<>();
                    snap.put("user_id", other.userId.toString());
                    snap.put("status", "online");
                    snap.put("activities", List.of());
                    send(session.ws, buildMessage(GatewayMessage.OP_DISPATCH,
                            snap, "PRESENCE_UPDATE", session.seq++));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleVoiceStateUpdate(GatewaySession session, JsonNode data) {
        try {
            Long guildId = data.has("guild_id") && !data.get("guild_id").isNull()
                    ? data.get("guild_id").asLong() : null;
            Long channelId = data.has("channel_id") && !data.get("channel_id").isNull()
                    ? data.get("channel_id").asLong() : null;
            if (guildId == null) return;

            if (channelId == null) {
                voiceService.leaveVoice(guildId, session.userId);
            } else {
                voiceService.joinVoice(guildId, channelId, session.userId, session.sessionId,
                        data.has("self_mute") ? data.get("self_mute").asBoolean(false) : null,
                        data.has("self_deaf") ? data.get("self_deaf").asBoolean(false) : null);
            }

            // 广播语音状态
            if (channelId != null) {
                Map<Long, VoiceState> states = voiceService.getVoiceStatesInChannel(channelId);
                for (VoiceState vs : states.values()) {
                    Map<String, Object> vsJson = new HashMap<>();
                    vsJson.put("guild_id", vs.getGuildId().toString());
                    vsJson.put("channel_id", vs.getChannelId().toString());
                    vsJson.put("user_id", vs.getUserId().toString());
                    vsJson.put("session_id", vs.getSessionId());
                    vsJson.put("self_mute", vs.isSelfMute());
                    vsJson.put("self_deaf", vs.isSelfDeaf());

                    broadcastToGuild(guildId, buildMessage(GatewayMessage.OP_DISPATCH,
                            vsJson, "VOICE_STATE_UPDATE", session.seq++), null);
                }
            } else {
                // 用户离开语音频道：广播 channel_id=null 让其他客户端移除该用户
                Map<String, Object> leaveJson = new HashMap<>();
                leaveJson.put("guild_id", guildId.toString());
                leaveJson.put("channel_id", null);
                leaveJson.put("user_id", session.userId.toString());
                leaveJson.put("session_id", session.sessionId);
                broadcastToGuild(guildId, buildMessage(GatewayMessage.OP_DISPATCH,
                        leaveJson, "VOICE_STATE_UPDATE", session.seq++), null);
            }
        } catch (Exception e) {
            log.error("Voice state update error", e);
        }
    }

    private void handleRequestGuildMembers(GatewaySession session, JsonNode data) {
        try {
            Long guildId = data.get("guild_id").asLong();
            // 越权防护:仅公会成员可拉取成员列表
            if (session.userId == null || guildService.getMember(guildId, session.userId) == null) {
                send(session.ws, error("Not a member of this guild", 4004));
                return;
            }
            List<GuildMember> members = guildService.getGuildMembers(guildId);

            List<Map<String, Object>> memberList = new ArrayList<>();
            for (GuildMember m : members) {
                Map<String, Object> mm = new HashMap<>();
                mm.put("user_id", m.getUserId().toString());
                mm.put("nickname", m.getNickname());
                mm.put("joined_at", m.getJoinedAt() != null ? m.getJoinedAt().toString() : null);
                memberList.add(mm);
            }

            Map<String, Object> chunk = new HashMap<>();
            chunk.put("guild_id", guildId.toString());
            chunk.put("members", memberList);

            send(session.ws, buildMessage(GatewayMessage.OP_DISPATCH,
                    chunk, "GUILD_MEMBERS_CHUNK", session.seq++));
        } catch (Exception e) {
            log.error("Request guild members error", e);
        }
    }

    /**
     * 广播消息到公会的所有在线用户
     */
    public void broadcastToGuild(Long guildId, GatewayMessage message, Long excludeUserId) {
        String payload = toJsonString(message);
        for (GatewaySession session : sessions.values()) {
            if (session.status == GatewayStatus.READY
                    && session.ws.isOpen()
                    && session.userId != null
                    && !session.userId.equals(excludeUserId)
                    && session.guildIds.contains(guildId)) {
                try {
                    sendRaw(session.ws, payload);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 向公会的所有在线会话广播一个自定义网关事件
     * (GUILD_UPDATE / GUILD_DELETE / GUILD_MEMBER_ADD-REMOVE / ROLE / CHANNEL / GUILD_BAN 等)
     */
    public void dispatchToGuild(Long guildId, String eventName, Map<String, Object> data,
                                Long excludeUserId) {
        String payload = toJsonString(buildMessage(GatewayMessage.OP_DISPATCH, data, eventName, 0));
        for (GatewaySession session : sessions.values()) {
            if (session.status == GatewayStatus.READY
                    && session.ws.isOpen()
                    && session.userId != null
                    && !session.userId.equals(excludeUserId)
                    && session.guildIds.contains(guildId)) {
                try {
                    sendRaw(session.ws, payload);
                } catch (Exception ignored) {}
            }
        }
    }

    /** 好友关系变化后，实时通知双方（携带最新关系列表与 DM 频道） */
    public void dispatchRelationshipUpdate(Long user1Id, Long user2Id, org.discord.entity.DmChannel dm) {
        for (GatewaySession session : sessions.values()) {
            if (session.status == GatewayStatus.READY && session.userId != null
                    && (session.userId.equals(user1Id) || session.userId.equals(user2Id))) {
                // 刷新缓存的好友集合
                session.friendIds = new HashSet<>(friendService.getFriendUserIds(session.userId));

                Map<String, Object> d = new HashMap<>();
                d.put("user_id", session.userId.equals(user1Id) ? user2Id : user1Id);
                d.put("dm_channel_id", dm != null ? dm.getId().toString() : null);
                send(session.ws, buildMessage(GatewayMessage.OP_DISPATCH, d, "RELATIONSHIP_UPDATE", session.seq++));

                // 重推关系列表（前端可据此刷新好友/私信列表）
                Map<String, Object> rel = new HashMap<>();
                rel.put("relationships", friendService.getRelationships(session.userId));
                send(session.ws, buildMessage(GatewayMessage.OP_DISPATCH, rel, "RELATIONSHIPS_SYNC", session.seq++));
            }
        }
    }

    public void dispatchMessage(Long channelId, org.discord.entity.Message msg) {
        Map<String, Object> json = messageService.toJson(msg);
        // 公会频道按 guildIds 过滤(非成员收不到),DM 频道只发参与者 — 复用统一分发
        dispatchChannelEvent(channelId, "MESSAGE_CREATE", json, null);
    }

    /** 向公会广播某用户当前语音状态(静音/禁听变更等) */
    public void broadcastVoiceState(Long guildId, Long userId) {
        voiceService.getVoiceState(guildId, userId).ifPresent(vs -> {
            Map<String, Object> vsJson = new HashMap<>();
            vsJson.put("guild_id", vs.getGuildId().toString());
            vsJson.put("channel_id", vs.getChannelId().toString());
            vsJson.put("user_id", vs.getUserId().toString());
            vsJson.put("session_id", vs.getSessionId());
            vsJson.put("self_mute", vs.isSelfMute());
            vsJson.put("self_deaf", vs.isSelfDeaf());
            dispatchToGuild(guildId, "VOICE_STATE_UPDATE", vsJson, null);
        });
    }

    /**
     * 向频道广播自定义事件(反应/置顶/输入中 等)。
     * 公会频道 → 按公会广播;DM 频道 → 只发给参与者。
     */
    public void dispatchChannelEvent(Long channelId, String eventName, Map<String, Object> data,
                                     Long excludeUserId) {
        boolean isDm = dmChannelRepository.existsById(channelId);
        if (!isDm) {
            Channel channel = channelRepository.findById(channelId).orElse(null);
            if (channel != null && channel.getGuildId() != null) {
                dispatchToGuild(channel.getGuildId(), eventName, data, excludeUserId);
                return;
            }
        }
        // DM 频道: 仅向参与者广播(参与者集合预先取一次,避免逐会话查库)
        String payload = toJsonString(buildMessage(GatewayMessage.OP_DISPATCH, data, eventName, 0));
        Set<Long> dmMemberIds = dmMemberRepository.findByChannelId(channelId).stream()
                .map(DmChannelMember::getUserId).collect(java.util.stream.Collectors.toSet());
        for (GatewaySession session : sessions.values()) {
            if (session.status != GatewayStatus.READY || !session.ws.isOpen()
                    || session.userId == null || session.userId.equals(excludeUserId)) continue;
            if (dmMemberIds.contains(session.userId)) {
                try { sendRaw(session.ws, payload); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 用户加入/离开/被移出公会时刷新其在线会话的 guildIds,
     * 避免会话在 Identify 时缓存的老集合导致广播漏发或误发。
     */
    public void refreshUserGuilds(Long userId) {
        if (userId == null) return;
        for (GatewaySession session : sessions.values()) {
            if (session.userId != null && session.userId.equals(userId)) {
                session.guildIds = new HashSet<>(guildService.getUserGuilds(userId).stream()
                        .map(Guild::getId).toList());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        log.info("Gateway connection closed: {}, status: {}", ws.getId(), status);

        GatewaySession session = sessions.remove(ws.getId());
        if (session != null && session.sessionId != null) {
            ScheduledFuture<?> task = heartbeatTasks.remove(session.sessionId);
            if (task != null) task.cancel(false);

            // 保留会话 30 秒用于 Resume
            if (session.userId != null) {
                cache.set("session:" + session.sessionId,
                        session.userId.toString(), java.time.Duration.ofSeconds(30));
            }
            sessionById.remove(session.sessionId);

            // 断连清理:若该用户在这些公会有语音状态,一并摘除,避免残留脏状态
            if (session.userId != null) {
                for (Long guildId : session.guildIds) {
                    try {
                        voiceService.leaveVoice(guildId, session.userId);
                    } catch (Exception e) {
                        log.warn("Voice cleanup failed guild={} user={}", guildId, session.userId, e);
                    }
                }
            }
        }
    }

    // ========== 工具方法 ==========

    private GatewayMessage buildMessage(int op, Object data, String t, Integer seq) {
        return GatewayMessage.builder()
                .op(op)
                .d(data != null ? data : Collections.emptyMap())
                .t(t)
                .s(seq)
                .build();
    }

    private void send(WebSocketSession ws, GatewayMessage msg) {
        try {
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.error("Send error", e);
        }
    }

    private void sendRaw(WebSocketSession ws, String payload) {
        try {
            if (ws.isOpen()) {
                ws.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            log.error("Send error", e);
        }
    }

    private String toJsonString(GatewayMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return "{}";
        }
    }

    private GatewayMessage invalidSession() {
        return GatewayMessage.builder()
                .op(GatewayMessage.OP_INVALID_SESSION)
                .d(false)
                .build();
    }

    private GatewayMessage error(String message, int code) {
        return GatewayMessage.builder()
                .op(GatewayMessage.OP_INVALID_SESSION)
                .d(Map.of("message", message, "code", code))
                .build();
    }

    enum GatewayStatus {
        AWAITING_IDENTIFY, IDENTIFYING, READY, RESUMING, DISCONNECTED
    }

    static class GatewaySession {
        WebSocketSession ws;
        Long userId;
        String sessionId;
        GatewayStatus status;
        Instant lastHeartbeat;
        int seq;
        // 该会话所属的公会 ID 集合（登录时计算，广播时直接查集合，避免每会话查库）
        Set<Long> guildIds = Collections.emptySet();
        // 该会话的在线好友 ID 集合（广播 presence 时使用）
        Set<Long> friendIds = Collections.emptySet();
    }
}
