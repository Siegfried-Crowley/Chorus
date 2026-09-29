package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.Channel;
import org.chorus.entity.ChannelOverwrite;
import org.chorus.entity.Guild;
import org.chorus.entity.GuildMember;
import org.chorus.exception.BadRequestException;
import org.chorus.exception.ForbiddenException;
import org.chorus.exception.NotFoundException;
import org.chorus.repository.ChannelOverwriteRepository;
import org.chorus.repository.ChannelRepository;
import org.chorus.repository.DmChannelRepository;
import org.chorus.repository.GuildMemberRepository;
import org.chorus.repository.InviteRepository;
import org.chorus.repository.MessageRepository;
import org.chorus.repository.VoiceAllocationRepository;
import org.chorus.repository.VoiceStateRepository;
import org.chorus.util.SnowflakeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChannelService {
    private final ChannelRepository channelRepository;
    private final ChannelOverwriteRepository overwriteRepository;
    private final DmChannelRepository dmChannelRepository;
    private final GuildMemberRepository memberRepository;
    private final GuildService guildService;
    private final PermissionService permissionService;
    private final SnowflakeGenerator snowflake;
    private final AuditLogService auditLogService;
    private final InviteRepository inviteRepository;
    private final MessageRepository messageRepository;
    private final VoiceStateRepository voiceStateRepository;
    private final VoiceAllocationRepository allocationRepository;

    // 频道类型
    public static final short TYPE_GUILD_TEXT = 0;
    public static final short TYPE_DM = 1;
    public static final short TYPE_GUILD_VOICE = 2;
    public static final short TYPE_GROUP_DM = 3;
    public static final short TYPE_GUILD_CATEGORY = 4;
    public static final short TYPE_GUILD_ANNOUNCEMENT = 5;
    public static final short TYPE_GUILD_STAGE = 13;
    public static final short TYPE_GUILD_FORUM = 15;

    @Transactional
    public Channel createChannel(Long guildId, String name, short type, Long parentId, Long creatorId) {
        GuildMember member = guildService.getMember(guildId, creatorId);
        if (member == null) throw new ForbiddenException("Not a member");

        long perms = permissionService.calculateGuildPermissions(
                guildService.getGuild(guildId), member);
        if (!permissionService.hasPermission(perms, permissionService.MANAGE_CHANNELS)) {
            throw new ForbiddenException("Missing MANAGE_CHANNELS permission");
        }

        Channel channel = Channel.builder()
                .id(snowflake.nextId())
                .guildId(guildId)
                .name(name)
                .type(type)
                .position(0)
                .parentId(parentId)
                .nsfw(false)
                .bitrate(type == TYPE_GUILD_VOICE ? 64000 : null)
                .rateLimitPerUser(0)
                .createdAt(Instant.now())
                .build();
        channelRepository.save(channel);
        auditLogService.log(guildId, creatorId, AuditLogService.CHANNEL_CREATE, channel.getId(),
                "Created channel #" + channel.getName());
        return channel;
    }

    public List<Channel> getGuildChannels(Long guildId) {
        return channelRepository.findByGuildIdOrderByPositionAsc(guildId);
    }

    public Channel getChannel(Long channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new NotFoundException("Channel not found"));
    }

    @Transactional
    public Channel updateChannel(Long channelId, Long actorId, String name, String topic,
                                 Integer rateLimit, Integer position, Long parentId) {
        Channel channel = getChannel(channelId);
        if (channel.getGuildId() != null) {
            requireManageChannels(channel.getGuildId(), actorId);
        }
        if (name != null) channel.setName(name);
        if (topic != null) channel.setTopic(topic);
        if (rateLimit != null) channel.setRateLimitPerUser(rateLimit);
        if (position != null) channel.setPosition(position);
        if (parentId != null) {
            Channel parent = channelRepository.findById(parentId)
                    .orElseThrow(() -> new NotFoundException("Parent channel not found"));
            if (!parent.getGuildId().equals(channel.getGuildId())) {
                throw new BadRequestException("Parent not in guild");
            }
            channel.setParentId(parentId);
        }
        channelRepository.save(channel);
        auditLogService.log(channel.getGuildId(), actorId, AuditLogService.CHANNEL_UPDATE,
                channelId, "Updated channel #" + channel.getName());
        return channel;
    }

    @Transactional
    public void deleteChannel(Long channelId, Long actorId) {
        Channel channel = getChannel(channelId);
        if (channel.getGuildId() != null) {
            requireManageChannels(channel.getGuildId(), actorId);
        }
        Long guildId = channel.getGuildId();
        String name = channel.getName();
        overwriteRepository.deleteByChannelId(channelId);
        // 关联数据清理:邀请/消息/语音状态与分配(防孤儿行,也避免外键约束违约)
        inviteRepository.deleteByChannelId(channelId);
        messageRepository.deleteByChannelId(channelId);
        voiceStateRepository.deleteByChannelId(channelId);
        allocationRepository.deleteByChannelId(channelId);
        channelRepository.deleteById(channelId);
        if (guildId != null) {
            auditLogService.log(guildId, actorId, AuditLogService.CHANNEL_DELETE, channelId,
                    "Deleted channel #" + name);
        }
    }

    private void requireManageChannels(Long guildId, Long userId) {
        GuildMember member = guildService.getMember(guildId, userId);
        if (member == null) throw new ForbiddenException("Not a member");
        long perms = permissionService.calculateGuildPermissions(
                guildService.getGuild(guildId), member);
        if (!permissionService.hasPermission(perms, permissionService.MANAGE_CHANNELS)) {
            throw new ForbiddenException("Missing MANAGE_CHANNELS permission");
        }
    }

    // 权限覆盖管理 — 所有写操作必须先校验 MANAGE_CHANNELS,防止任意用户自授权限
    @Transactional
    public ChannelOverwrite createOverwrite(Long channelId, short type, Long targetId,
                                             long allow, long deny, Long actorId) {
        Channel channel = getChannel(channelId);
        if (channel.getGuildId() != null) {
            requireManageChannels(channel.getGuildId(), actorId);
        }
        ChannelOverwrite ow = ChannelOverwrite.builder()
                .channelId(channelId)
                .type(type)
                .targetId(targetId)
                .allow(allow)
                .deny(deny)
                .build();
        ChannelOverwrite saved = overwriteRepository.save(ow);
        if (channel.getGuildId() != null) {
            auditLogService.log(channel.getGuildId(), actorId, AuditLogService.CHANNEL_UPDATE,
                    channelId, "Updated channel permission overwrite");
        }
        return saved;
    }

    @Transactional
    public ChannelOverwrite updateOverwrite(Long overwriteId, Long allow, Long deny, Long actorId) {
        ChannelOverwrite ow = overwriteRepository.findById(overwriteId)
                .orElseThrow(() -> new NotFoundException("Overwrite not found"));
        Channel channel = getChannel(ow.getChannelId());
        if (channel.getGuildId() != null) {
            requireManageChannels(channel.getGuildId(), actorId);
        }
        if (allow != null) ow.setAllow(allow);
        if (deny != null) ow.setDeny(deny);
        return overwriteRepository.save(ow);
    }

    @Transactional
    public void deleteOverwrite(Long overwriteId, Long actorId) {
        ChannelOverwrite ow = overwriteRepository.findById(overwriteId)
                .orElseThrow(() -> new NotFoundException("Overwrite not found"));
        Channel channel = getChannel(ow.getChannelId());
        if (channel.getGuildId() != null) {
            requireManageChannels(channel.getGuildId(), actorId);
        }
        overwriteRepository.deleteById(overwriteId);
    }

    public List<ChannelOverwrite> getOverwrites(Long channelId) {
        return overwriteRepository.findByChannelId(channelId);
    }

    // 为用户返回有权限查看的频道
    public List<Channel> getAccessibleChannels(Long guildId, Long userId) {
        Guild guild = guildService.getGuild(guildId);
        GuildMember member = guildService.getMember(guildId, userId);
        if (member == null) return List.of();

        long guildPerms = permissionService.calculateGuildPermissions(guild, member);
        List<Channel> channels = getGuildChannels(guildId);

        // 一次性批量加载全部 overwrites 并按频道分组,避免逐频道查询(N+1)
        Map<Long, List<ChannelOverwrite>> overwriteMap =
                overwriteRepository.findByChannelIdIn(channels.stream().map(Channel::getId).toList())
                        .stream()
                        .collect(java.util.stream.Collectors.groupingBy(ChannelOverwrite::getChannelId));

        return channels.stream()
                .filter(c -> {
                    long channelPerms = permissionService.calculateChannelPermissions(
                            guild, member, c,
                            overwriteMap.getOrDefault(c.getId(), List.of()),
                            guildPerms);
                    return permissionService.canViewChannel(channelPerms, c);
                })
                .toList();
    }
}
