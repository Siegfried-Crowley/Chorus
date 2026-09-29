package org.discord.service;

import lombok.RequiredArgsConstructor;
import org.discord.entity.Channel;
import org.discord.entity.Guild;
import org.discord.entity.GuildBan;
import org.discord.entity.GuildMember;
import org.discord.entity.Invite;
import org.discord.exception.BadRequestException;
import org.discord.exception.ForbiddenException;
import org.discord.exception.NotFoundException;
import org.discord.repository.ChannelRepository;
import org.discord.repository.GuildBanRepository;
import org.discord.repository.InviteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 邀请服务 - 创建/查询/删除邀请,以及通过邀请加入公会
 */
@Service
@RequiredArgsConstructor
public class InviteService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final InviteRepository inviteRepository;
    private final GuildBanRepository banRepository;
    private final ChannelRepository channelRepository;
    private final GuildService guildService;
    private final PermissionService permissionService;

    private String generateCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    @Transactional
    public Invite createInvite(Long guildId, Long actorId, Long channelId,
                               Integer maxUses, Integer maxAgeSeconds) {
        GuildMember member = guildService.getMember(guildId, actorId);
        if (member == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(
                guildService.getGuild(guildId), member);
        if (!permissionService.hasPermission(perms, permissionService.CREATE_INSTANT_INVITE)) {
            throw new ForbiddenException("Missing CREATE_INSTANT_INVITE permission");
        }

        // 邀请必须绑定一个属于该公会的频道;未指定时取第一个频道
        Long targetChannelId = channelId;
        if (targetChannelId == null) {
            targetChannelId = channelRepository.findByGuildIdOrderByPositionAsc(guildId).stream()
                    .findFirst().map(Channel::getId)
                    .orElseThrow(() -> new BadRequestException("No channels in guild"));
        } else {
            Channel channel = channelRepository.findById(targetChannelId)
                    .orElseThrow(() -> new NotFoundException("Channel not found"));
            if (!channel.getGuildId().equals(guildId)) {
                throw new BadRequestException("Channel not in guild");
            }
        }

        String code = generateCode();
        while (inviteRepository.existsById(code)) {
            code = generateCode();
        }

        Instant now = Instant.now();
        Instant expiresAt = (maxAgeSeconds != null && maxAgeSeconds > 0)
                ? now.plusSeconds(maxAgeSeconds) : null;
        Invite invite = Invite.builder()
                .code(code)
                .guildId(guildId)
                .channelId(targetChannelId)
                .inviterId(actorId)
                .maxUses(maxUses != null ? maxUses : 0)
                .maxAge(maxAgeSeconds != null ? maxAgeSeconds : 0)
                .uses(0)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();
        return inviteRepository.save(invite);
    }

    public List<Invite> getInvites(Long guildId, Long actorId) {
        GuildMember member = guildService.getMember(guildId, actorId);
        if (member == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(
                guildService.getGuild(guildId), member);
        // 与 createInvite 保持一致:能创建邀请的成员就应能看到邀请列表,
        // 否则普通成员创建后界面无任何变化(列表 403),会误以为失败而重复创建
        if (!permissionService.hasPermission(perms, permissionService.CREATE_INSTANT_INVITE)) {
            throw new ForbiddenException("Missing CREATE_INSTANT_INVITE permission");
        }
        return inviteRepository.findByGuildIdOrderByCreatedAtDesc(guildId);
    }

    @Transactional
    public void deleteInvite(String code, Long actorId) {
        Invite invite = inviteRepository.findById(code)
                .orElseThrow(() -> new NotFoundException("Invite not found"));
        GuildMember member = guildService.getMember(invite.getGuildId(), actorId);
        if (member == null) throw new ForbiddenException("Not a member");

        Guild guild = guildService.getGuild(invite.getGuildId());
        boolean isCreator = invite.getInviterId().equals(actorId);
        boolean isOwner = guild.getOwnerId().equals(actorId);
        if (!isCreator && !isOwner) {
            long perms = permissionService.calculateGuildPermissions(guild, member);
            if (!permissionService.hasPermission(perms, permissionService.MANAGE_GUILD)) {
                throw new ForbiddenException("No permission");
            }
        }
        inviteRepository.delete(invite);
    }

    public Invite getInvite(String code) {
        return inviteRepository.findById(code)
                .orElseThrow(() -> new NotFoundException("Invite not found"));
    }

    /** 加入前预览:返回公会/频道摘要(过期邀请不可预览) */
    public Map<String, Object> getInvitePreview(String code) {
        Invite invite = getInvite(code);
        if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Invite expired");
        }
        Guild guild = guildService.getGuild(invite.getGuildId());
        Channel channel = channelRepository.findById(invite.getChannelId()).orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("code", invite.getCode());
        result.put("guild_id", guild.getId().toString());
        result.put("guild_name", guild.getName());
        result.put("guild_icon", guild.getIcon());
        result.put("member_count", guild.getMemberCount());
        result.put("channel_id", channel != null ? channel.getId().toString() : null);
        result.put("channel_name", channel != null ? channel.getName() : null);
        return result;
    }

    @Transactional
    public Invite joinInvite(String code, Long userId) {
        Invite invite = inviteRepository.findById(code)
                .orElseThrow(() -> new NotFoundException("Invite not found"));

        // 过期校验
        if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now())) {
            inviteRepository.delete(invite);
            throw new BadRequestException("Invite expired");
        }
        // 次数上限
        if (invite.getMaxUses() != null && invite.getMaxUses() > 0
                && invite.getUses() != null && invite.getUses() >= invite.getMaxUses()) {
            throw new BadRequestException("Invite max uses reached");
        }
        // 被 ban 不可加入
        if (banRepository.findByGuildIdAndUserId(invite.getGuildId(), userId).isPresent()) {
            throw new ForbiddenException("Banned from guild");
        }
        // 加入(已是成员会抛 "Already a member")
        guildService.addMember(invite.getGuildId(), userId);
        invite.setUses(invite.getUses() == null ? 1 : invite.getUses() + 1);
        return inviteRepository.save(invite);
    }
}
