package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.*;
import org.chorus.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限系统核心实现
 * 实现角色权限叠加与频道覆盖的完整判定逻辑
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final RoleRepository roleRepository;
    private final GuildMemberRepository memberRepository;
    private final MemberRoleRepository memberRoleRepository;

    // === 权限位定义 (64-bit) ===
    public static final long CREATE_INSTANT_INVITE   = 1L << 0;
    public static final long KICK_MEMBERS            = 1L << 1;
    public static final long BAN_MEMBERS             = 1L << 2;
    public static final long ADMINISTRATOR           = 1L << 3;
    public static final long MANAGE_CHANNELS         = 1L << 4;
    public static final long MANAGE_GUILD            = 1L << 5;
    public static final long ADD_REACTIONS           = 1L << 6;
    public static final long VIEW_AUDIT_LOG          = 1L << 7;
    public static final long PRIORITY_SPEAKER        = 1L << 8;
    public static final long STREAM                  = 1L << 9;
    public static final long VIEW_CHANNEL            = 1L << 10;
    public static final long SEND_MESSAGES           = 1L << 11;
    public static final long SEND_TTS_MESSAGES       = 1L << 12;
    public static final long MANAGE_MESSAGES         = 1L << 13;
    public static final long EMBED_LINKS             = 1L << 14;
    public static final long ATTACH_FILES            = 1L << 15;
    public static final long READ_MESSAGE_HISTORY    = 1L << 16;
    public static final long MENTION_EVERYONE        = 1L << 17;
    public static final long USE_EXTERNAL_EMOJI      = 1L << 18;
    public static final long VIEW_GUILD_INSIGHTS     = 1L << 19;
    public static final long CONNECT                 = 1L << 20;
    public static final long SPEAK                   = 1L << 21;
    public static final long MUTE_MEMBERS            = 1L << 22;
    public static final long DEAFEN_MEMBERS          = 1L << 23;
    public static final long MOVE_MEMBERS            = 1L << 24;
    public static final long USE_VAD                 = 1L << 25;
    public static final long CHANGE_NICKNAME         = 1L << 26;
    public static final long MANAGE_NICKNAMES        = 1L << 27;
    public static final long MANAGE_ROLES            = 1L << 28;
    public static final long MANAGE_WEBHOOKS         = 1L << 29;
    public static final long MANAGE_GUILD_EXPRESSIONS = 1L << 30;
    public static final long USE_APPLICATION_COMMANDS = 1L << 31;
    public static final long REQUEST_TO_SPEAK        = 1L << 32;
    public static final long MANAGE_EVENTS           = 1L << 33;
    public static final long MANAGE_THREADS          = 1L << 34;
    public static final long CREATE_PUBLIC_THREADS   = 1L << 35;
    public static final long CREATE_PRIVATE_THREADS  = 1L << 36;
    public static final long USE_EXTERNAL_STICKERS   = 1L << 37;
    public static final long SEND_MESSAGES_IN_THREADS = 1L << 38;
    public static final long USE_EMBEDDED_ACTIVITIES = 1L << 39;
    public static final long MODERATE_MEMBERS        = 1L << 40;

    public static final long ALL_PERMISSIONS = -1L; // all bits set

    /**
     * 计算用户在指定公会的最终权限
     */
    public long calculateGuildPermissions(Guild guild, GuildMember member) {
        // 如果是服务器所有者 → 全部权限
        if (member.getUserId().equals(guild.getOwnerId())) {
            return ALL_PERMISSIONS;
        }

        // 获取所有角色
        List<Role> roles = roleRepository.findByGuildIdOrderByPositionAsc(guild.getId());

        // 1. @everyone 角色的基础权限
        Role everyoneRole = roles.stream()
                .filter(r -> r.getId().equals(guild.getId()))
                .findFirst().orElse(null);
        long permissions = everyoneRole != null && everyoneRole.getPermissions() != null
                ? everyoneRole.getPermissions() : 0L;

        // 检查是否管理员
        if ((permissions & ADMINISTRATOR) != 0) return ALL_PERMISSIONS;

        // 2. 叠加成员已分配的角色权限 (按 position 升序)
        Set<Long> assignedRoleIds = memberRoleRepository
                .findByGuildIdAndUserId(guild.getId(), member.getUserId()).stream()
                .map(MemberRole::getRoleId)
                .collect(Collectors.toSet());
        List<Role> memberRoles = roles.stream()
                .filter(r -> assignedRoleIds.contains(r.getId()))
                .sorted(Comparator.comparingInt(r -> r.getPosition() != null ? r.getPosition() : 0))
                .toList();

        for (Role role : memberRoles) {
            if (role.getPermissions() != null) {
                permissions |= role.getPermissions();
            }
        }

        if ((permissions & ADMINISTRATOR) != 0) return ALL_PERMISSIONS;

        return permissions;
    }

    /**
     * 计算用户在指定频道中的最终权限
     * 遵循: 角色基础 → @everyone覆盖 → 角色覆盖(按position) → 成员覆盖
     */
    public long calculateChannelPermissions(Guild guild, GuildMember member,
                                             Channel channel, List<ChannelOverwrite> overwrites,
                                             long guildPermissions) {
        // 如果已有管理员权限，直接返回全部
        if ((guildPermissions & ADMINISTRATOR) != 0) return ALL_PERMISSIONS;

        long permissions = guildPermissions;

        // 应用频道覆盖
        if (overwrites != null && !overwrites.isEmpty()) {
            // a. @everyone 覆盖
            for (ChannelOverwrite everyone : overwrites) {
                if (everyone.getType() == 0 && everyone.getTargetId().equals(guild.getId())) {
                    permissions &= ~everyone.getDeny();
                    permissions |= everyone.getAllow();
                    break;
                }
            }

            // b. 角色覆盖 (按 position 升序)
            List<Role> roles = roleRepository.findByGuildIdOrderByPositionAsc(guild.getId());
            List<ChannelOverwrite> roleOverwrites = overwrites.stream()
                    .filter(o -> o.getType() == 0 && !o.getTargetId().equals(guild.getId()))
                    .sorted(Comparator.comparingInt(o -> {
                        Role r = roles.stream()
                                .filter(role -> role.getId().equals(o.getTargetId()))
                                .findFirst().orElse(null);
                        return r != null && r.getPosition() != null ? r.getPosition() : 0;
                    }))
                    .toList();

            for (ChannelOverwrite overwrite : roleOverwrites) {
                permissions &= ~overwrite.getDeny();
                permissions |= overwrite.getAllow();
            }

            // c. 特定成员覆盖 (最高优先级)
            for (ChannelOverwrite memberOw : overwrites) {
                if (memberOw.getType() == 1 && memberOw.getTargetId().equals(member.getUserId())) {
                    permissions &= ~memberOw.getDeny();
                    permissions |= memberOw.getAllow();
                    break;
                }
            }
        }

        return permissions;
    }

    // === 快捷权限检查方法 ===

    public boolean canViewChannel(long permissions, Channel channel) {
        if (channel.getType() == 4) return true; // category - always visible
        return (permissions & VIEW_CHANNEL) != 0;
    }

    public boolean canSendMessage(long permissions) {
        return (permissions & SEND_MESSAGES) != 0;
    }

    public boolean canConnectVoice(long permissions) {
        return (permissions & CONNECT) != 0;
    }

    public boolean canSpeakVoice(long permissions) {
        return (permissions & SPEAK) != 0;
    }

    public boolean canManageMessages(long permissions) {
        return (permissions & MANAGE_MESSAGES) != 0;
    }

    public boolean canKick(long permissions) {
        return (permissions & KICK_MEMBERS) != 0;
    }

    public boolean canBan(long permissions) {
        return (permissions & BAN_MEMBERS) != 0;
    }

    public boolean canManageRoles(long permissions) {
        return (permissions & MANAGE_ROLES) != 0;
    }

    public boolean hasPermission(long permissions, long required) {
        return (permissions & required) == required;
    }
}
