package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.DmChannel;
import org.chorus.entity.DmChannelMember;
import org.chorus.entity.Message;
import org.chorus.entity.MessageId;
import org.chorus.entity.User;
import org.chorus.exception.BadRequestException;
import org.chorus.repository.DmChannelMemberRepository;
import org.chorus.repository.DmChannelRepository;
import org.chorus.repository.MessageRepository;
import org.chorus.repository.UserRepository;
import org.chorus.util.SnowflakeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DmService {
    private final DmChannelRepository dmChannelRepository;
    private final DmChannelMemberRepository dmMemberRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final SnowflakeGenerator snowflake;

    /** 我参与的私信频道列表（含对方用户信息和最后一条消息预览） */
    public List<Map<String, Object>> listDmChannels(Long userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DmChannelMember m : dmMemberRepository.findByUserId(userId)) {
            DmChannel ch = dmChannelRepository.findById(m.getChannelId()).orElse(null);
            if (ch == null) continue;
            Map<String, Object> json = channelJson(ch, userId);
            result.add(json);
        }
        // 按最后消息时间倒序（无消息的在后面）
        result.sort((a, b) -> {
            String la = (String) a.get("last_message_id");
            String lb = (String) b.get("last_message_id");
            if (la == null && lb == null) return 0;
            if (la == null) return 1;
            if (lb == null) return -1;
            return lb.compareTo(la);
        });
        return result;
    }

    /** 获取或创建与某用户的私信频道，返回频道 JSON */
    @Transactional
    public Map<String, Object> openDm(Long userId, Long otherUserId) {
        if (userId.equals(otherUserId)) {
            throw new BadRequestException("Cannot DM yourself");
        }
        // 查找已存在的两人 DM
        for (DmChannelMember mine : dmMemberRepository.findByUserId(userId)) {
            List<DmChannelMember> members = dmMemberRepository.findByChannelId(mine.getChannelId());
            boolean hasOther = members.stream().anyMatch(x -> x.getUserId().equals(otherUserId));
            if (hasOther) {
                DmChannel ch = dmChannelRepository.findById(mine.getChannelId()).orElse(null);
                if (ch != null) return channelJson(ch, userId);
            }
        }
        // 不存在则创建
        Long id = snowflake.nextId();
        DmChannel ch = DmChannel.builder()
                .id(id).type((short) 1).createdAt(Instant.now())
                .build();
        dmChannelRepository.save(ch);
        dmMemberRepository.save(new DmChannelMember(id, userId));
        dmMemberRepository.save(new DmChannelMember(id, otherUserId));
        return channelJson(ch, userId);
    }

    /** 判断某用户是否是该 DM 频道的成员 */
    public boolean isMember(Long channelId, Long userId) {
        DmChannel ch = dmChannelRepository.findById(channelId).orElse(null);
        if (ch == null) return false;
        return dmMemberRepository.findByChannelId(channelId).stream()
                .anyMatch(m -> m.getUserId().equals(userId));
    }

    private Map<String, Object> channelJson(DmChannel ch, Long viewerId) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", ch.getId().toString());
        json.put("type", 1);
        json.put("name", ch.getName());
        json.put("icon", ch.getIcon());
        json.put("last_message_id", ch.getLastMessageId() != null ? ch.getLastMessageId().toString() : null);
        json.put("created_at", ch.getCreatedAt() != null ? ch.getCreatedAt().toString() : null);

        // 对方用户信息（频道里除自己外的另一个成员）
        for (DmChannelMember o : dmMemberRepository.findByChannelId(ch.getId())) {
            if (o.getUserId().equals(viewerId)) continue;
            userRepository.findById(o.getUserId()).ifPresent(peer -> {
                Map<String, Object> u = new HashMap<>();
                u.put("id", peer.getId().toString());
                u.put("username", peer.getUsername());
                u.put("discriminator", peer.getDiscriminator());
                u.put("avatar", peer.getAvatar());
                u.put("global_name", peer.getGlobalName());
                json.put("recipient", u);
            });
        }

        // 最后一条消息预览
        if (ch.getLastMessageId() != null) {
            messageRepository.findById(new MessageId(ch.getId(), ch.getLastMessageId()))
                    .ifPresent(last -> json.put("last_message", last.getContent()));
        }
        return json;
    }
}
