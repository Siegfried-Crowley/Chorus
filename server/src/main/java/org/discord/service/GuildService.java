package org.discord.service;

import lombok.RequiredArgsConstructor;
import org.discord.entity.*;
import org.discord.exception.BadRequestException;
import org.discord.exception.ForbiddenException;
import org.discord.exception.NotFoundException;
import org.discord.repository.*;
import org.discord.util.SnowflakeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GuildService {
    private final GuildRepository guildRepository;
    private final GuildMemberRepository memberRepository;
    private final ChannelRepository channelRepository;
    private final RoleRepository roleRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final ChannelOverwriteRepository overwriteRepository;
    private final MessageRepository messageRepository;
    private final InviteRepository inviteRepository;
    private final GuildBanRepository banRepository;
    private final VoiceStateRepository voiceStateRepository;
    private final VoiceService voiceService;
    private final SnowflakeGenerator snowflake;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    @Transactional
    public Guild createGuild(String name, Long ownerId) {
        Long guildId = snowflake.nextId();
        Guild guild = Guild.builder()
                .id(guildId)
                .name(name)
                .ownerId(ownerId)
                .verificationLevel(0)
                .defaultMessageNotifications(0)
                .explicitContentFilter(0)
                .mfaLevel(0)
                .memberCount(1)
                .preferredLocale("zh-CN")
                .createdAt(Instant.now())
                .build();
        guildRepository.save(guild);

        // 创建 @everyone 角色
        Role everyone = Role.builder()
                .id(guildId)  // @everyone 角色 ID = guild ID
                .guildId(guildId)
                .name("@everyone")
                .color(0)
                .hoist(false)
                .position(0)
                .permissions(permissionService.CREATE_INSTANT_INVITE
                        | permissionService.VIEW_CHANNEL
                        | permissionService.SEND_MESSAGES
                        | permissionService.READ_MESSAGE_HISTORY
                        | permissionService.CONNECT
                        | permissionService.SPEAK
                        | permissionService.USE_VAD
                        | permissionService.ADD_REACTIONS
                        | permissionService.CHANGE_NICKNAME
                        | permissionService.EMBED_LINKS
                        | permissionService.ATTACH_FILES)
                .mentionable(false)
                .createdAt(Instant.now())
                .build();
        roleRepository.save(everyone);

        // 添加创建者为成员
        GuildMember member = GuildMember.builder()
                .guildId(guildId)
                .userId(ownerId)
                .joinedAt(Instant.now())
                .build();
        memberRepository.save(member);

        // 创建默认频道
        Channel general = Channel.builder()
                .id(snowflake.nextId())
                .guildId(guildId)
                .name("一般聊天")
                .type((short) 0)  // TEXT
                .position(0)
                .build();
        channelRepository.save(general);

        Channel voice = Channel.builder()
                .id(snowflake.nextId())
                .guildId(guildId)
                .name("语音频道")
                .type((short) 2)  // VOICE
                .position(1)
                .bitrate(64000)
                .build();
        channelRepository.save(voice);

        return guild;
    }

    public Guild getGuild(Long guildId) {
        return guildRepository.findById(guildId)
                .orElseThrow(() -> new NotFoundException("Guild not found"));
    }

    public List<Guild> getUserGuilds(Long userId) {
        List<GuildMember> memberships = memberRepository.findByUserId(userId);
        return guildRepository.findAllById(
                memberships.stream().map(GuildMember::getGuildId).toList()
        );
    }

    public GuildMember getMember(Long guildId, Long userId) {
        return memberRepository.findByGuildIdAndUserId(guildId, userId)
                .orElse(null);
    }

    @Transactional
    public GuildMember addMember(Long guildId, Long userId) {
        if (memberRepository.existsByGuildIdAndUserId(guildId, userId)) {
            throw new BadRequestException("Already a member");
        }
        GuildMember member = GuildMember.builder()
                .guildId(guildId)
                .userId(userId)
                .joinedAt(Instant.now())
                .build();
        memberRepository.save(member);

        Guild guild = getGuild(guildId);
        guild.setMemberCount(guild.getMemberCount() + 1);
        guildRepository.save(guild);

        return member;
    }

    @Transactional
    public void removeMember(Long guildId, Long userId) {
        memberRepository.deleteByGuildIdAndUserId(guildId, userId);
        Guild guild = getGuild(guildId);
        guild.setMemberCount(Math.max(0, guild.getMemberCount() - 1));
        guildRepository.save(guild);
    }

    public List<Channel> getGuildChannels(Long guildId) {
        return channelRepository.findByGuildIdOrderByPositionAsc(guildId);
    }

    public List<Role> getGuildRoles(Long guildId) {
        return roleRepository.findByGuildIdOrderByPositionAsc(guildId);
    }

    public List<GuildMember> getGuildMembers(Long guildId) {
        return memberRepository.findByGuildId(guildId);
    }

    /** 返回某成员在公会中已分配的角色(含 @everyone) */
    public List<Role> getMemberRoles(Long guildId, Long userId) {
        if (getMember(guildId, userId) == null) {
            throw new NotFoundException("Member not found");
        }
        Set<Long> assignedIds = memberRoleRepository.findByGuildIdAndUserId(guildId, userId).stream()
                .map(MemberRole::getRoleId)
                .collect(Collectors.toSet());
        List<Role> roles = roleRepository.findByGuildIdOrderByPositionAsc(guildId);
        return roles.stream()
                .filter(r -> r.getId().equals(guildId) || assignedIds.contains(r.getId()))
                .toList();
    }

    @Transactional
    public void updateMemberRoles(Long guildId, Long userId, List<Long> roleIds) {
        // 先删除现有角色
        List<MemberRole> existing = memberRoleRepository.findByGuildIdAndUserId(guildId, userId);
        memberRoleRepository.deleteAll(existing);

        // 添加新角色
        for (Long roleId : roleIds) {
            MemberRole mr = MemberRole.builder()
                    .guildId(guildId)
                    .userId(userId)
                    .roleId(roleId)
                    .build();
            memberRoleRepository.save(mr);
        }
    }

    // ========== 公会管理 ==========

    @Transactional
    public void deleteGuild(Long guildId, Long actorId) {
        Guild guild = getGuild(guildId);
        if (!guild.getOwnerId().equals(actorId)) {
            throw new ForbiddenException("No permission");
        }
        // 清理语音状态(成员离开语音频道)
        for (GuildMember m : memberRepository.findByGuildId(guildId)) {
            voiceStateRepository.findByGuildIdAndUserId(guildId, m.getUserId())
                    .ifPresent(vs -> voiceService.leaveVoice(guildId, m.getUserId()));
        }
        // 邀请先删(其外键指向频道,避免删除频道时违反外键约束)
        inviteRepository.deleteByGuildId(guildId);
        // 频道级联: overwrites + messages + 频道本身
        for (Channel c : channelRepository.findByGuildIdOrderByPositionAsc(guildId)) {
            overwriteRepository.deleteByChannelId(c.getId());
            messageRepository.deleteByChannelId(c.getId());
            voiceStateRepository.deleteByChannelId(c.getId());
            channelRepository.delete(c);
        }
        roleRepository.deleteByGuildId(guildId);
        memberRoleRepository.deleteByGuildId(guildId);
        memberRepository.deleteByGuildId(guildId);
        banRepository.deleteByGuildId(guildId);
        auditLogRepository.deleteByGuildId(guildId);
        guildRepository.delete(guild);
    }

    @Transactional
    public void leaveGuild(Long guildId, Long userId) {
        Guild guild = getGuild(guildId);
        if (guild.getOwnerId().equals(userId)) {
            throw new BadRequestException("Owner cannot leave; transfer or delete");
        }
        if (getMember(guildId, userId) == null) {
            throw new ForbiddenException("Not a member");
        }
        voiceStateRepository.findByGuildIdAndUserId(guildId, userId)
                .ifPresent(vs -> voiceService.leaveVoice(guildId, userId));
        memberRoleRepository.deleteByGuildIdAndUserId(guildId, userId);
        removeMember(guildId, userId);
        auditLogService.log(guildId, userId, AuditLogService.MEMBER_LEAVE, userId, "Member left the guild");
    }

    @Transactional
    public void kickMember(Long guildId, Long targetUserId, Long actorId) {
        Guild guild = getGuild(guildId);
        if (guild.getOwnerId().equals(targetUserId)) {
            throw new BadRequestException("Cannot kick the owner");
        }
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(guild, actor);
        if (!permissionService.canKick(perms)) {
            throw new ForbiddenException("Missing KICK_MEMBERS permission");
        }
        if (getMember(guildId, targetUserId) == null) {
            throw new NotFoundException("Member not found");
        }
        voiceStateRepository.findByGuildIdAndUserId(guildId, targetUserId)
                .ifPresent(vs -> voiceService.leaveVoice(guildId, targetUserId));
        memberRoleRepository.deleteByGuildIdAndUserId(guildId, targetUserId);
        removeMember(guildId, targetUserId);
        auditLogService.log(guildId, actorId, AuditLogService.MEMBER_KICK, targetUserId,
                "Kicked member " + targetUserId);
    }

    @Transactional
    public GuildBan banMember(Long guildId, Long targetUserId, Long actorId, String reason) {
        Guild guild = getGuild(guildId);
        if (guild.getOwnerId().equals(targetUserId)) {
            throw new BadRequestException("Cannot ban the owner");
        }
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(guild, actor);
        if (!permissionService.canBan(perms)) {
            throw new ForbiddenException("Missing BAN_MEMBERS permission");
        }
        GuildBan ban = banRepository.save(GuildBan.builder()
                .guildId(guildId)
                .userId(targetUserId)
                .reason(reason)
                .createdAt(Instant.now())
                .build());
        // 若目标仍在公会则一并移除
        if (getMember(guildId, targetUserId) != null) {
            voiceStateRepository.findByGuildIdAndUserId(guildId, targetUserId)
                    .ifPresent(vs -> voiceService.leaveVoice(guildId, targetUserId));
            memberRoleRepository.deleteByGuildIdAndUserId(guildId, targetUserId);
            removeMember(guildId, targetUserId);
        }
        auditLogService.log(guildId, actorId, AuditLogService.MEMBER_BAN, targetUserId,
                "Banned member " + targetUserId + (reason != null ? " — " + reason : ""));
        return ban;
    }

    @Transactional
    public void unbanMember(Long guildId, Long targetUserId, Long actorId) {
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(getGuild(guildId), actor);
        if (!permissionService.canBan(perms)) {
            throw new ForbiddenException("Missing BAN_MEMBERS permission");
        }
        banRepository.findByGuildIdAndUserId(guildId, targetUserId)
                .orElseThrow(() -> new NotFoundException("Ban not found"));
        banRepository.deleteByGuildIdAndUserId(guildId, targetUserId);
        auditLogService.log(guildId, actorId, AuditLogService.MEMBER_UNBAN, targetUserId,
                "Unbanned member " + targetUserId);
    }

    public List<GuildBan> getBans(Long guildId, Long actorId) {
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(getGuild(guildId), actor);
        if (!permissionService.canBan(perms)) {
            throw new ForbiddenException("Missing BAN_MEMBERS permission");
        }
        return banRepository.findByGuildId(guildId);
    }

    @Transactional
    public GuildMember updateNickname(Long guildId, Long targetUserId, Long actorId, String nickname) {
        Guild guild = getGuild(guildId);
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        GuildMember target = getMember(guildId, targetUserId);
        if (target == null) throw new NotFoundException("Member not found");

        boolean self = targetUserId.equals(actorId);
        long perms = permissionService.calculateGuildPermissions(guild, actor);
        if (!self && !permissionService.hasPermission(perms, permissionService.MANAGE_NICKNAMES)) {
            throw new ForbiddenException("Missing MANAGE_NICKNAMES permission");
        }
        if (self && !permissionService.hasPermission(perms, permissionService.CHANGE_NICKNAME)) {
            throw new ForbiddenException("Missing CHANGE_NICKNAME permission");
        }
        if (nickname != null && nickname.isBlank()) nickname = null;
        target.setNickname(nickname);
        memberRepository.save(target);
        auditLogService.log(guildId, actorId, AuditLogService.NICKNAME_CHANGE, targetUserId,
                "Nickname " + (nickname != null ? "set to " + nickname : "cleared"));
        return target;
    }

    @Transactional
    public Guild updateGuild(Long guildId, Long actorId, String name, String icon) {
        Guild guild = getGuild(guildId);
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        boolean isOwner = guild.getOwnerId().equals(actorId);
        if (!isOwner) {
            long perms = permissionService.calculateGuildPermissions(guild, actor);
            if (!permissionService.hasPermission(perms, permissionService.MANAGE_GUILD)) {
                throw new ForbiddenException("Missing MANAGE_GUILD permission");
            }
        }
        if (name != null && !name.isBlank()) guild.setName(name);
        if (icon != null) guild.setIcon(icon.isBlank() ? null : icon);
        guildRepository.save(guild);
        auditLogService.log(guildId, actorId, AuditLogService.GUILD_UPDATE, guildId,
                name != null && !name.isBlank() ? "Updated guild name to " + name : "Updated guild icon");
        return guild;
    }

    // ========== 角色管理 ==========

    @Transactional
    public Role createRole(Long guildId, Long actorId, String name, Integer color, boolean hoist,
                           Long permissions, boolean mentionable) {
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(getGuild(guildId), actor);
        if (!permissionService.canManageRoles(perms)) {
            throw new ForbiddenException("Missing MANAGE_ROLES permission");
        }
        int maxPos = roleRepository.findByGuildIdOrderByPositionAsc(guildId).stream()
                .mapToInt(r -> r.getPosition() != null ? r.getPosition() : 0)
                .max().orElse(0);
        Role role = Role.builder()
                .id(snowflake.nextId())
                .guildId(guildId)
                .name(name != null && !name.isBlank() ? name : "new role")
                .color(color != null ? color : 0)
                .hoist(hoist)
                .position(maxPos + 1)
                .permissions(permissions != null ? permissions : 0L)
                .mentionable(mentionable)
                .createdAt(Instant.now())
                .build();
        roleRepository.save(role);
        auditLogService.log(guildId, actorId, AuditLogService.ROLE_CREATE, role.getId(),
                "Created role " + role.getName());
        return role;
    }

    @Transactional
    public Role updateRole(Long guildId, Long roleId, Long actorId, String name, Integer color,
                           Boolean hoist, Long permissions, Boolean mentionable) {
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(getGuild(guildId), actor);
        if (!permissionService.canManageRoles(perms)) {
            throw new ForbiddenException("Missing MANAGE_ROLES permission");
        }
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role not found"));
        if (!role.getGuildId().equals(guildId)) {
            throw new BadRequestException("Role not in guild");
        }
        if (role.getId().equals(guildId)) {
            // @everyone 名字不可改
            if (name != null && !name.isBlank()) {
                throw new BadRequestException("@everyone name is fixed");
            }
        }
        if (name != null && !name.isBlank()) role.setName(name);
        if (color != null) role.setColor(color);
        if (hoist != null) role.setHoist(hoist);
        if (permissions != null) role.setPermissions(permissions);
        if (mentionable != null) role.setMentionable(mentionable);
        roleRepository.save(role);
        auditLogService.log(guildId, actorId, AuditLogService.ROLE_UPDATE, roleId,
                "Updated role " + role.getName());
        return role;
    }

    @Transactional
    public void deleteRole(Long guildId, Long roleId, Long actorId) {
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(getGuild(guildId), actor);
        if (!permissionService.canManageRoles(perms)) {
            throw new ForbiddenException("Missing MANAGE_ROLES permission");
        }
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role not found"));
        if (!role.getGuildId().equals(guildId)) {
            throw new BadRequestException("Role not in guild");
        }
        if (role.getId().equals(guildId)) {
            throw new BadRequestException("Cannot delete @everyone role");
        }
        memberRoleRepository.deleteByRoleId(roleId);
        roleRepository.delete(role);
        auditLogService.log(guildId, actorId, AuditLogService.ROLE_DELETE, roleId,
                "Deleted role " + role.getName());
    }

    @Transactional
    public void assignRoles(Long guildId, Long userId, Long actorId, List<Long> roleIds) {
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(getGuild(guildId), actor);
        if (!permissionService.hasPermission(perms, permissionService.MANAGE_ROLES)) {
            throw new ForbiddenException("Missing MANAGE_ROLES permission");
        }
        if (getMember(guildId, userId) == null) {
            throw new NotFoundException("Member not found");
        }
        // 校验角色都属于该公会
        for (Long roleId : roleIds) {
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> new NotFoundException("Role not found"));
            if (!role.getGuildId().equals(guildId)) {
                throw new BadRequestException("Role not in guild");
            }
        }
        updateMemberRoles(guildId, userId, roleIds);
    }

    public List<Map<String, Object>> getAuditLog(Long guildId, Long actorId) {
        GuildMember actor = getMember(guildId, actorId);
        if (actor == null) throw new ForbiddenException("Not a member");
        boolean isOwner = getGuild(guildId).getOwnerId().equals(actorId);
        long perms = permissionService.calculateGuildPermissions(getGuild(guildId), actor);
        if (!isOwner && !permissionService.hasPermission(perms, permissionService.VIEW_AUDIT_LOG)) {
            throw new ForbiddenException("No permission to view audit log");
        }
        return auditLogService.getLog(guildId);
    }
}
