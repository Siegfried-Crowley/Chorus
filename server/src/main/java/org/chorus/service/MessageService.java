package org.chorus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.chorus.entity.Channel;
import org.chorus.entity.DmChannel;
import org.chorus.exception.BadRequestException;
import org.chorus.exception.ForbiddenException;
import org.chorus.exception.NotFoundException;
import org.chorus.entity.Guild;
import org.chorus.entity.GuildMember;
import org.chorus.entity.Message;
import org.chorus.entity.MessageId;
import org.chorus.entity.User;
import org.chorus.repository.DmChannelMemberRepository;
import org.chorus.repository.DmChannelRepository;
import org.chorus.repository.GuildMemberRepository;
import org.chorus.repository.MessageRepository;
import org.chorus.repository.ChannelRepository;
import org.chorus.repository.GuildRepository;
import org.chorus.repository.UserRepository;
import org.chorus.util.SnowflakeGenerator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageService {
    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\S+)");

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final DmChannelRepository dmChannelRepository;
    private final DmChannelMemberRepository dmMemberRepository;
    private final GuildRepository guildRepository;
    private final GuildMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final ChannelAccessService channelAccess;
    private final SnowflakeGenerator snowflake;
    private final ObjectMapper objectMapper;

    @Transactional
    public Message createMessage(Long channelId, Long authorId, String content,
                                  String nonce, String messageReference) {
        return createMessage(channelId, authorId, content, nonce, messageReference, null);
    }

    @Transactional
    public Message createMessage(Long channelId, Long authorId, String content,
                                  String nonce, String messageReference, String attachmentsJson) {
        // 频道既可能是公会频道，也可能是 DM 频道
        Channel channel = channelRepository.findById(channelId).orElse(null);
        DmChannel dmChannel = null;

        if (channel == null) {
            dmChannel = dmChannelRepository.findById(channelId)
                    .orElseThrow(() -> new NotFoundException("Channel not found"));
        }

        // 统一授权校验：DM 需为频道成员；公会频道需为成员且具备频道级 SEND_MESSAGES
        channelAccess.requireSendMessages(channelId, authorId);

        // 去重：检查 nonce
        if (nonce != null && !nonce.isEmpty()) {
            List<Message> existing = messageRepository
                    .findByChannelIdOrderByCreatedAtDesc(channelId, PageRequest.of(0, 1));
            for (Message m : existing) {
                if (nonce.equals(m.getNonce())) {
                    return m; // 重复消息，直接返回已有的
                }
            }
        }

        Message message = Message.builder()
                .id(snowflake.nextId())
                .channelId(channelId)
                .guildId(channel != null ? channel.getGuildId() : null)
                .authorId(authorId)
                .content(content != null ? content : "")
                .embeds("[]")
                .attachments(attachmentsJson != null ? attachmentsJson : "[]")
                .stickers("[]")
                .reactions("{}")
                .mentions("{}")
                .type((short) 0)
                .flags(0)
                .nonce(nonce)
                .messageReference(messageReference)
                .createdAt(Instant.now())
                .build();

        // 解析 @everyone / @username 提及(仅公会频道)
        if (channel != null && channel.getGuildId() != null) {
            parseMentions(message, channel, authorId);
        }

        message = messageRepository.save(message);

        // 更新频道的 last_message_id
        if (channel != null) {
            channel.setLastMessageId(message.getId());
            channelRepository.save(channel);
        } else if (dmChannel != null) {
            dmChannel.setLastMessageId(message.getId());
            dmChannelRepository.save(dmChannel);
        }

        return message;
    }

    private boolean channelMembershipAllowed(Long channelId, Long userId) {
        return channelAccess.isDmMember(channelId, userId);
    }

    /** 解析消息中的 @everyone 与 @昵称/@用户名 提及,写入 mentions JSON 与 mentionEveryone 字段 */
    private void parseMentions(Message message, Channel channel, Long authorId) {
        String content = message.getContent();
        boolean hasEveryone = content != null && content.contains("@everyone");
        boolean mentionEveryone = false;

        // @everyone 需要 MENTION_EVERYONE 权限;无权限则剥掉提及标记(文本保留)
        if (hasEveryone) {
            Guild guild = guildRepository.findById(channel.getGuildId()).orElse(null);
            GuildMember author = memberRepository
                    .findByGuildIdAndUserId(channel.getGuildId(), authorId).orElse(null);
            if (guild != null && author != null) {
                long perms = permissionService.calculateGuildPermissions(guild, author);
                mentionEveryone = permissionService.hasPermission(perms, permissionService.MENTION_EVERYONE);
            }
        }

        // 收集该频道公会的 昵称/用户名 -> userId 映射
        List<GuildMember> members = memberRepository.findByGuildId(channel.getGuildId());
        Set<Long> memberIds = members.stream().map(GuildMember::getUserId).collect(Collectors.toSet());
        Map<Long, String> usernames = userRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        Map<String, String> displayToId = new HashMap<>();
        for (GuildMember m : members) {
            String display = m.getNickname() != null && !m.getNickname().isBlank()
                    ? m.getNickname() : usernames.getOrDefault(m.getUserId(), "");
            if (!display.isEmpty()) displayToId.put(display, m.getUserId().toString());
        }

        Set<String> mentionedIds = new LinkedHashSet<>();
        if (content != null) {
            Matcher matcher = MENTION_PATTERN.matcher(content);
            while (matcher.find()) {
                String name = matcher.group(1);
                String id = displayToId.get(name);
                if (id != null) mentionedIds.add(id);
            }
        }

        Map<String, Object> mentions = new LinkedHashMap<>();
        mentions.put("everyone", mentionEveryone);
        mentions.put("user_ids", mentionedIds);
        message.setMentions(toJsonString(mentions));
        message.setMentionEveryone(mentionEveryone);
    }

    // ========== 反应 (Reactions) ==========

    @Transactional
    public Message addReaction(Long channelId, Long messageId, Long userId, String emoji) {
        channelAccess.requireChannelAccess(channelId, userId);
        Message message = requireMessage(channelId, messageId);
        Map<String, List<String>> reactions = parseReactions(message.getReactions());
        List<String> users = reactions.computeIfAbsent(emoji, k -> new ArrayList<>());
        if (!users.contains(userId.toString())) {
            users.add(userId.toString());
        }
        message.setReactions(toJsonString(reactions));
        return messageRepository.save(message);
    }

    @Transactional
    public Message removeReaction(Long channelId, Long messageId, Long userId, String emoji) {
        channelAccess.requireChannelAccess(channelId, userId);
        Message message = requireMessage(channelId, messageId);
        Map<String, List<String>> reactions = parseReactions(message.getReactions());
        List<String> users = reactions.get(emoji);
        if (users != null) {
            users.removeIf(id -> id.equals(userId.toString()));
            if (users.isEmpty()) reactions.remove(emoji);
        }
        message.setReactions(toJsonString(reactions));
        return messageRepository.save(message);
    }

    // ========== 置顶 (Pins) ==========

    @Transactional
    public Message pinMessage(Long channelId, Long messageId, Long userId) {
        Message message = requireMessage(channelId, messageId);
        requireManageMessages(message, userId);
        message.setPinned(true);
        return messageRepository.save(message);
    }

    @Transactional
    public Message unpinMessage(Long channelId, Long messageId, Long userId) {
        Message message = requireMessage(channelId, messageId);
        requireManageMessages(message, userId);
        message.setPinned(false);
        return messageRepository.save(message);
    }

    public List<Message> getPinnedMessages(Long channelId, Long userId) {
        channelAccess.requireChannelAccess(channelId, userId);
        return messageRepository.findByChannelIdAndPinnedTrueOrderByCreatedAtDesc(channelId);
    }

    private void requireManageMessages(Message message, Long userId) {
        if (message.getGuildId() == null) {
            throw new ForbiddenException("No permission");
        }
        channelAccess.requireGuildMember(message.getGuildId(), userId);
        Guild guild = guildRepository.findById(message.getGuildId()).orElse(null);
        GuildMember member = memberRepository
                .findByGuildIdAndUserId(message.getGuildId(), userId).orElse(null);
        if (guild == null || member == null) {
            throw new ForbiddenException("No permission");
        }
        long perms = permissionService.calculateGuildPermissions(guild, member);
        if (!permissionService.hasPermission(perms, permissionService.MANAGE_MESSAGES)) {
            throw new ForbiddenException("Missing MANAGE_MESSAGES permission");
        }
    }

    // ========== 输入中 (Typing) ==========

    /** 校验发送者是否有权在频道输入(有 SEND_MESSAGES 或为 DM 成员) */
    public void validateTyping(Long channelId, Long userId) {
        channelAccess.requireSendMessages(channelId, userId);
    }

    // ========== 搜索 (Search) ==========

    public List<Message> searchMessages(Long guildId, Long channelId, String query, Long userId) {
        channelAccess.requireGuildMember(guildId, userId);
        if (channelId != null) {
            Channel channel = channelRepository.findById(channelId)
                    .orElseThrow(() -> new NotFoundException("Channel not found"));
            if (!channel.getGuildId().equals(guildId)) {
                throw new BadRequestException("Channel not in guild");
            }
            // 只能搜索有权限查看的频道
            channelAccess.requireChannelAccess(channelId, userId);
        }
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) return List.of();
        // 转义 LIKE 通配符,防止用户输入 % _ 被当作通配符(既影响结果又可能被滥用)
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return messageRepository.search(guildId, channelId, "%" + escaped + "%", PageRequest.of(0, 50));
    }

    // ========== 工具 ==========

    private Message requireMessage(Long channelId, Long messageId) {
        return messageRepository.findById(new MessageId(channelId, messageId))
                .orElseThrow(() -> new NotFoundException("Message not found"));
    }

    private Map<String, List<String>> parseReactions(String json) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : parseJsonMap(json).entrySet()) {
            Object v = e.getValue();
            List<String> ids = new ArrayList<>();
            if (v instanceof List<?> list) {
                for (Object o : list) ids.add(String.valueOf(o));
            }
            result.put(e.getKey(), ids);
        }
        return result;
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public List<Message> getMessages(Long channelId, Long userId, int limit, Long before, Long after) {
        // 读取消息历史必须先通过频道访问校验(公会成员 + VIEW_CHANNEL,或 DM 成员)
        channelAccess.requireChannelAccess(channelId, userId);
        if (before != null) {
            return messageRepository
                    .findByChannelIdAndIdLessThanOrderByCreatedAtDesc(
                            channelId, before, PageRequest.of(0, limit));
        } else if (after != null) {
            return messageRepository
                    .findByChannelIdAndIdGreaterThanOrderByCreatedAtAsc(
                            channelId, after, PageRequest.of(0, limit));
        }
        return messageRepository
                .findByChannelIdOrderByCreatedAtDesc(channelId, PageRequest.of(0, limit));
    }

    @Transactional
    public Message updateMessage(Long channelId, Long messageId, Long userId, String content) {
        Message message = messageRepository.findById(new MessageId(channelId, messageId))
                .orElseThrow(() -> new NotFoundException("Message not found"));

        if (!message.getAuthorId().equals(userId)) {
            throw new ForbiddenException("Cannot edit another user's message");
        }

        message.setContent(content);
        message.setEditedTimestamp(Instant.now());
        return messageRepository.save(message);
    }

    @Transactional
    public void deleteMessage(Long channelId, Long messageId, Long userId) {
        Message message = messageRepository.findById(new MessageId(channelId, messageId))
                .orElseThrow(() -> new NotFoundException("Message not found"));

        // 允许作者或管理员删除
        if (!message.getAuthorId().equals(userId)) {
            if (message.getGuildId() != null) {
                Guild guild = guildRepository.findById(message.getGuildId()).orElse(null);
                GuildMember member = memberRepository
                        .findByGuildIdAndUserId(message.getGuildId(), userId).orElse(null);
                if (member == null || guild == null) {
                    throw new ForbiddenException("No permission");
                }
                long perms = permissionService.calculateGuildPermissions(guild, member);
                if (!permissionService.hasPermission(perms, permissionService.MANAGE_MESSAGES)) {
                    throw new ForbiddenException("Missing MANAGE_MESSAGES permission");
                }
            } else {
                throw new ForbiddenException("Cannot delete another user's message");
            }
        }

        messageRepository.delete(message);
    }

    public Map<String, Object> toJson(Message msg) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", msg.getId().toString());
        map.put("channel_id", msg.getChannelId().toString());
        map.put("guild_id", msg.getGuildId() != null ? msg.getGuildId().toString() : null);
        map.put("author_id", msg.getAuthorId().toString());
        map.put("content", msg.getContent());
        map.put("timestamp", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
        map.put("edited_timestamp", msg.getEditedTimestamp() != null ? msg.getEditedTimestamp().toString() : null);
        map.put("type", msg.getType());
        map.put("nonce", msg.getNonce());
        map.put("flags", msg.getFlags());
        map.put("attachments", parseJsonList(msg.getAttachments()));
        map.put("reactions", parseJsonMap(msg.getReactions()));
        map.put("mentions", parseJsonMap(msg.getMentions()));
        map.put("mention_everyone", msg.isMentionEveryone());
        map.put("pinned", msg.isPinned());
        map.put("message_reference", parseJsonMap(msg.getMessageReference()));
        return map;
    }

    private List<Object> parseJsonList(String json) {
        if (json == null || json.isEmpty()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                // 旧数据可能被 JSON 类型列序列化成了字符串(双重编码), 递归解析内层
                return parseJsonList(node.asText());
            }
            return objectMapper.convertValue(node, List.class);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isEmpty()) return new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                // 旧数据可能被 JSON 类型列序列化成了字符串(双重编码), 递归解析内层
                return parseJsonMap(node.asText());
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }
}
