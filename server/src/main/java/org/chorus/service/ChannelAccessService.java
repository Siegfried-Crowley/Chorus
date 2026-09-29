package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.Channel;
import org.chorus.entity.Guild;
import org.chorus.entity.GuildMember;
import org.chorus.exception.ForbiddenException;
import org.chorus.exception.NotFoundException;
import org.chorus.repository.ChannelRepository;
import org.chorus.repository.ChannelOverwriteRepository;
import org.chorus.repository.DmChannelMemberRepository;
import org.chorus.repository.DmChannelRepository;
import org.chorus.repository.GuildMemberRepository;
import org.chorus.repository.GuildRepository;
import org.springframework.stereotype.Service;

/**
 * 统一的频道/公会访问授权入口。
 *
 * <p>所有涉及"某用户能否查看/写入某频道或某公会"的接口都必须经由本类校验,
 * 避免此前各 Controller/Service 各自实现、漏检导致的越权。
 * 未通过校验一律抛 {@link ForbiddenException}(403)。
 */
@Service
@RequiredArgsConstructor
public class ChannelAccessService {

    private final ChannelRepository channelRepository;
    private final ChannelOverwriteRepository overwriteRepository;
    private final DmChannelRepository dmChannelRepository;
    private final DmChannelMemberRepository dmMemberRepository;
    private final GuildRepository guildRepository;
    private final GuildMemberRepository memberRepository;
    private final PermissionService permissionService;

    /** 解析频道:是公会频道返回 {@link Channel},是 DM 返回 null,都不存在抛 404。 */
    public Channel resolveGuildChannel(Long channelId) {
        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null && !dmChannelRepository.existsById(channelId)) {
            throw new NotFoundException("Channel not found");
        }
        return channel;
    }

    /** 用户是否为该 DM 频道成员 */
    public boolean isDmMember(Long channelId, Long userId) {
        return dmMemberRepository.findByChannelId(channelId).stream()
                .anyMatch(m -> m.getUserId().equals(userId));
    }

    /** 校验用户在公会内,否则 403 */
    public void requireGuildMember(Long guildId, Long userId) {
        memberRepository.findByGuildIdAndUserId(guildId, userId)
                .orElseThrow(() -> new ForbiddenException("Not a member of this guild"));
    }

    /** 校验用户具备 MANAGE_CHANNELS(频道/权限覆盖管理类操作),否则 403 */
    public void requireManageChannels(Long guildId, Long userId) {
        requireGuildMember(guildId, userId);
        Guild guild = guildRepository.findById(guildId).orElse(null);
        GuildMember member = memberRepository.findByGuildIdAndUserId(guildId, userId).orElse(null);
        if (guild == null || member == null) {
            throw new ForbiddenException("No permission");
        }
        long perms = permissionService.calculateGuildPermissions(guild, member);
        if (!permissionService.hasPermission(perms, permissionService.MANAGE_CHANNELS)) {
            throw new ForbiddenException("Missing MANAGE_CHANNELS permission");
        }
    }

    /** 计算用户在频道的最终权限(公会权限 + 频道覆盖)。channel 为 null 视为 DM。 */
    public long channelPermissions(Channel channel, Long guildId, Long userId) {
        Guild guild = guildRepository.findById(guildId).orElse(null);
        GuildMember member = memberRepository.findByGuildIdAndUserId(guildId, userId).orElse(null);
        if (guild == null || member == null) {
            return 0L;
        }
        long guildPerms = permissionService.calculateGuildPermissions(guild, member);
        if (channel == null) {
            return guildPerms;
        }
        return permissionService.calculateChannelPermissions(
                guild, member, channel,
                overwriteRepository.findByChannelId(channel.getId()),
                guildPerms);
    }

    /**
     * 校验用户能否"看到"频道(DM 成员,或公会成员且具备 VIEW_CHANNEL)。
     * 无法访问 → 403。
     */
    public void requireChannelAccess(Long channelId, Long userId) {
        Channel channel = resolveGuildChannel(channelId);
        if (channel == null) {
            if (!isDmMember(channelId, userId)) {
                throw new ForbiddenException("No permission");
            }
            return;
        }
        if (channel.getGuildId() == null) {
            return; // 无 guild 的公会频道理论上不存在,直接放行避免误伤
        }
        requireGuildMember(channel.getGuildId(), userId);
        long perms = channelPermissions(channel, channel.getGuildId(), userId);
        if (!permissionService.canViewChannel(perms, channel)) {
            throw new ForbiddenException("No permission");
        }
    }

    /**
     * 校验用户能否在该频道发消息(DM 成员,或具备 SEND_MESSAGES)。
     */
    public void requireSendMessages(Long channelId, Long userId) {
        Channel channel = resolveGuildChannel(channelId);
        if (channel == null) {
            if (!isDmMember(channelId, userId)) {
                throw new ForbiddenException("No permission");
            }
            return;
        }
        if (channel.getGuildId() == null) {
            return;
        }
        requireGuildMember(channel.getGuildId(), userId);
        long perms = channelPermissions(channel, channel.getGuildId(), userId);
        if (!permissionService.hasPermission(perms, permissionService.SEND_MESSAGES)) {
            throw new ForbiddenException("Missing SEND_MESSAGES permission");
        }
    }
}
