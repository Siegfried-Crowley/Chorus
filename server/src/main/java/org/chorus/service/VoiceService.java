package org.chorus.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.chorus.entity.*;
import org.chorus.exception.BadRequestException;
import org.chorus.exception.ForbiddenException;
import org.chorus.exception.NotFoundException;
import org.chorus.repository.*;
import org.chorus.util.SnowflakeGenerator;
import org.chorus.voice.VoiceAudioRouter;
import org.chorus.voice.VoiceSfuServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音服务 - 管理 Voice Server 分配、语音状态跟踪
 */
@Service
@RequiredArgsConstructor
public class VoiceService {
    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final VoiceStateRepository voiceStateRepository;
    private final VoiceAllocationRepository allocationRepository;
    private final VoiceServerRepository serverRepository;
    private final ChannelRepository channelRepository;
    private final GuildRepository guildRepository;
    private final GuildMemberRepository memberRepository;
    private final PermissionService permissionService;
    private final SnowflakeGenerator snowflake;
    private final CacheService cache;
    private final VoiceSfuServer voiceSfu;
    private final VoiceAudioRouter voiceAudioRouter;

    @Value("${app.voice.server-port:4003}")
    private int voiceServerPort;
    @Value("${app.voice.ws-port:4004}")
    private int voiceWsPort;

    // 语音服务器池 (内存中维护)
    private final Map<String, VoiceServerInfo> serverPool = new ConcurrentHashMap<>();

    /**
     * 启动时把本地 SFU 注册进语音服务器池，否则 joinVoice 会因为 serverPool 为空而失败
     */
    @PostConstruct
    public void init() {
        registerVoiceServer("sfu-local", "127.0.0.1", voiceServerPort, voiceWsPort, "local");
        log.info("Registered local voice server: UDP {} / WS {}", voiceServerPort, voiceWsPort);
    }

    public static class VoiceServerInfo {
        public String id;
        public String ip;
        public int port;
        public int wsPort;
        public double load;
        public int sessions;
    }

    @Transactional
    public Map<String, Object> joinVoice(Long guildId, Long channelId, Long userId, String sessionId,
                                             Boolean selfMute, Boolean selfDeaf) {
        // 先校验后清理:任何校验失败都不应把用户从当前语音频道踢出
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new NotFoundException("Voice channel not found"));

        if (channel.getType() != 2) {
            throw new BadRequestException("Not a voice channel");
        }

        // 权限检查
        if (guildId != null) {
            GuildMember member = memberRepository
                    .findByGuildIdAndUserId(guildId, userId)
                    .orElseThrow(() -> new ForbiddenException("Not a member"));
            Guild guild = guildRepository.findById(guildId).orElse(null);

            if (guild != null) {
                long perms = permissionService.calculateGuildPermissions(guild, member);
                if (!permissionService.canConnectVoice(perms)) {
                    throw new ForbiddenException("Missing CONNECT permission");
                }
            }
        }

        // 幂等:网关 OP4 与 REST /voice/join 双路并发加入时,同一会话复用未过期分配,
        // 避免"REST 先发 token、OP4 后删 allocation"导致客户端拿到已失效的 token
        if (guildId != null && sessionId != null) {
            VoiceState existing = voiceStateRepository.findByGuildIdAndUserId(guildId, userId).orElse(null);
            if (existing != null && channelId.equals(existing.getChannelId())
                    && sessionId.equals(existing.getSessionId())) {
                if (selfMute != null || selfDeaf != null) {
                    if (selfMute != null) existing.setSelfMute(selfMute);
                    if (selfDeaf != null) existing.setSelfDeaf(selfDeaf);
                    voiceStateRepository.save(existing);
                    voiceAudioRouter.updateMemberState(userId.toString(), selfMute, selfDeaf);
                }
                VoiceAllocation alloc = allocationRepository.findByGuildIdAndUserId(guildId, userId).orElse(null);
                if (alloc != null && alloc.getExpiresAt() != null && alloc.getExpiresAt().isAfter(Instant.now())) {
                    VoiceServerInfo server = selectVoiceServer();
                    if (server != null) return buildJoinResult(alloc, server);
                }
            }
        }

        // 校验通过,清旧状态(换频道/重进)
        if (guildId != null) {
            voiceStateRepository.deleteByGuildIdAndUserId(guildId, userId);
            allocationRepository.deleteByGuildIdAndUserId(guildId, userId);
        }

        // 保存语音状态
        VoiceState vs = VoiceState.builder()
                .guildId(guildId)
                .channelId(channelId)
                .userId(userId)
                .sessionId(sessionId)
                .selfMute(selfMute != null && selfMute)
                .selfDeaf(selfDeaf != null && selfDeaf)
                .joinedAt(Instant.now())
                .build();
        voiceStateRepository.save(vs);
        if (Boolean.TRUE.equals(selfMute) || Boolean.TRUE.equals(selfDeaf)) {
            voiceAudioRouter.updateMemberState(userId.toString(), selfMute, selfDeaf);
        }

        // 分配 Voice Server
        VoiceServerInfo server = selectVoiceServer();
        if (server == null) {
            throw new BadRequestException("No voice server available");
        }

        int ssrc = (int)(snowflake.nextId() & 0x7FFFFFFF);
        String token = UUID.randomUUID().toString();

        VoiceAllocation alloc = VoiceAllocation.builder()
                .guildId(guildId)
                .channelId(channelId)
                .userId(userId)
                .serverId(server.id)
                .token(token)
                .ssrc(ssrc)
                .sessionId(sessionId)
                .allocatedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(30))
                .build();
        allocationRepository.save(alloc);

        // 在 SFU 中转发表注册用户（地址由首个上行 RTP 包自动补全）
        voiceSfu.userJoined(channelId.toString(), userId, ssrc, null);

        // 通知 Voice Server（内存模拟，无 Redis 依赖）
        Map<String, Object> notify = new HashMap<>();
        notify.put("type", "USER_JOIN");
        notify.put("guildId", guildId.toString());
        notify.put("channelId", channelId.toString());
        notify.put("userId", userId.toString());
        notify.put("ssrc", ssrc);
        notify.put("token", token);
        cache.publish("voice:events", notify.toString());

        return buildJoinResult(alloc, server);
    }

    /** 构造 join 响应(新建与幂等复用共用) */
    private Map<String, Object> buildJoinResult(VoiceAllocation alloc, VoiceServerInfo server) {
        Map<String, Object> result = new HashMap<>();
        result.put("token", alloc.getToken());
        result.put("ssrc", alloc.getSsrc());
        result.put("endpoint", server.ip + ":" + server.wsPort);
        result.put("server_id", server.id);
        result.put("modes", List.of("xsalsa20_poly1305"));
        result.put("port", server.port);
        result.put("ips", List.of(server.ip));
        return result;
    }

    /** 查询用户当前语音状态(供网关广播静音/禁听变更) */
    public java.util.Optional<VoiceState> getVoiceState(Long guildId, Long userId) {
        return voiceStateRepository.findByGuildIdAndUserId(guildId, userId);
    }

    @Transactional
    public void leaveVoice(Long guildId, Long userId) {
        voiceStateRepository.deleteByGuildIdAndUserId(guildId, userId);
        allocationRepository.findByGuildIdAndUserId(guildId, userId)
                .ifPresent(alloc -> {
                    allocationRepository.delete(alloc);
                    if (alloc.getChannelId() != null) {
                        voiceSfu.userLeft(alloc.getChannelId().toString(), userId);
                    }
                    Map<String, Object> notify = new HashMap<>();
                    notify.put("type", "USER_LEAVE");
                    notify.put("guildId", guildId.toString());
                    notify.put("userId", userId.toString());
                    cache.publish("voice:events", notify.toString());
                });
        // 无论是否有 allocation 记录,都要把音频中继里的会话摘除
        voiceAudioRouter.removeUserByUserId(userId.toString());
    }

    public List<VoiceState> getChannelVoiceStates(Long channelId) {
        return voiceStateRepository.findByChannelId(channelId);
    }

    @Transactional
    public void updateSelfMute(Long guildId, Long userId, boolean mute) {
        voiceStateRepository.findByGuildIdAndUserId(guildId, userId)
                .ifPresent(vs -> {
                    vs.setSelfMute(mute);
                    voiceStateRepository.save(vs);
                });
        // 同步到音频中继:muted 后中继不再转发该用户的上行帧
        voiceAudioRouter.updateMemberState(userId.toString(), mute, null);
    }

    @Transactional
    public void updateSelfDeaf(Long guildId, Long userId, boolean deaf) {
        voiceStateRepository.findByGuildIdAndUserId(guildId, userId)
                .ifPresent(vs -> {
                    vs.setSelfDeaf(deaf);
                    voiceStateRepository.save(vs);
                });
        // 同步到音频中继:deafened 后中继跳过该接收者
        voiceAudioRouter.updateMemberState(userId.toString(), null, deaf);
    }

    // Voice Server 注册和管理
    public void registerVoiceServer(String id, String ip, int port, int wsPort, String region) {
        VoiceServerInfo info = new VoiceServerInfo();
        info.id = id;
        info.ip = ip;
        info.port = port;
        info.wsPort = wsPort;
        info.load = 0;
        info.sessions = 0;
        serverPool.put(id, info);
    }

    private VoiceServerInfo selectVoiceServer() {
        return serverPool.values().stream()
                .min(Comparator.comparingDouble(s -> s.load))
                .orElse(null);
    }

    // 提供给 Gateway 使用：获取频道内所有用户的语音状态
    public Map<Long, VoiceState> getVoiceStatesInChannel(Long channelId) {
        Map<Long, VoiceState> result = new HashMap<>();
        voiceStateRepository.findByChannelId(channelId)
                .forEach(vs -> result.put(vs.getUserId(), vs));
        return result;
    }
}
