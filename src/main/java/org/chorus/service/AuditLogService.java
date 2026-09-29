package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.AuditLogEntry;
import org.chorus.repository.AuditLogRepository;
import org.chorus.util.SnowflakeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志 — 记录管理操作(kick/ban/role/channel 变更等),供 VIEW_AUDIT_LOG 权限成员查看
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    public static final int GUILD_UPDATE    = 1;
    public static final int GUILD_DELETE    = 2;
    public static final int MEMBER_KICK     = 3;
    public static final int MEMBER_BAN      = 4;
    public static final int MEMBER_UNBAN    = 5;
    public static final int MEMBER_LEAVE    = 6;
    public static final int NICKNAME_CHANGE = 7;
    public static final int ROLE_CREATE     = 10;
    public static final int ROLE_UPDATE     = 11;
    public static final int ROLE_DELETE     = 12;
    public static final int CHANNEL_CREATE  = 20;
    public static final int CHANNEL_UPDATE  = 21;
    public static final int CHANNEL_DELETE  = 22;

    private final AuditLogRepository auditLogRepository;
    private final SnowflakeGenerator snowflake;

    @Transactional
    public void log(Long guildId, Long actorId, int type, Long targetId, String details) {
        auditLogRepository.save(AuditLogEntry.builder()
                .id(snowflake.nextId())
                .guildId(guildId)
                .actorId(actorId)
                .targetId(targetId)
                .actionType(type)
                .changes(details)
                .createdAt(Instant.now())
                .build());
    }

    public List<Map<String, Object>> getLog(Long guildId) {
        return auditLogRepository.findByGuildIdOrderByCreatedAtDesc(guildId).stream()
                .map(this::toJson)
                .toList();
    }

    private Map<String, Object> toJson(AuditLogEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", e.getId().toString());
        m.put("guild_id", e.getGuildId().toString());
        m.put("actor_id", e.getActorId().toString());
        m.put("target_id", e.getTargetId() != null ? e.getTargetId().toString() : null);
        m.put("type", e.getActionType());
        m.put("details", e.getChanges());
        m.put("created_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        return m;
    }
}
