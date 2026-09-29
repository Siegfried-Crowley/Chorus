package org.chorus.service;

import lombok.RequiredArgsConstructor;
import org.chorus.entity.DmChannel;
import org.chorus.entity.DmChannelMember;
import org.chorus.entity.Relationship;
import org.chorus.entity.User;
import org.chorus.exception.BadRequestException;
import org.chorus.exception.NotFoundException;
import org.chorus.repository.*;
import org.chorus.util.SnowflakeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FriendService {
    private final RelationshipRepository relationshipRepository;
    private final UserRepository userRepository;
    private final DmChannelRepository dmChannelRepository;
    private final DmChannelMemberRepository dmChannelMemberRepository;
    private final SnowflakeGenerator snowflake;

    // Relationship types
    public static final short TYPE_NONE = 0;
    public static final short TYPE_FRIEND = 1;
    public static final short TYPE_BLOCKED = 2;
    public static final short TYPE_INCOMING_REQUEST = 3;
    public static final short TYPE_OUTGOING_REQUEST = 4;

    @Transactional
    public void sendFriendRequest(Long fromId, Long toId) {
        if (fromId.equals(toId)) throw new BadRequestException("Cannot add yourself");

        userRepository.findById(toId).orElseThrow(() -> new NotFoundException("User not found"));

        // 检查是否已经是好友
        Optional<Relationship> existing = relationshipRepository.findByFromIdAndToId(fromId, toId);
        if (existing.isPresent()) {
            if (existing.get().getType() == TYPE_FRIEND)
                throw new BadRequestException("Already friends");
            if (existing.get().getType() == TYPE_BLOCKED)
                throw new BadRequestException("Cannot send request");
        }

        // 删除旧关系
        relationshipRepository.deleteByFromIdAndToId(fromId, toId);

        // 发出请求
        Relationship outgoing = Relationship.builder()
                .fromId(fromId).toId(toId)
                .type(TYPE_OUTGOING_REQUEST)
                .since(Instant.now()).build();
        relationshipRepository.save(outgoing);

        Relationship incoming = Relationship.builder()
                .fromId(toId).toId(fromId)
                .type(TYPE_INCOMING_REQUEST)
                .since(Instant.now()).build();
        relationshipRepository.save(incoming);
    }

    @Transactional
    public DmChannel acceptFriendRequest(Long userId, Long fromUserId) {
        Relationship incoming = relationshipRepository
                .findByFromIdAndToId(userId, fromUserId)
                .orElseThrow(() -> new NotFoundException("No request found"));

        if (incoming.getType() != TYPE_INCOMING_REQUEST) {
            throw new BadRequestException("No incoming request");
        }

        // 更新双方关系为好友
        updateRelationship(userId, fromUserId, TYPE_FRIEND);
        updateRelationship(fromUserId, userId, TYPE_FRIEND);

        // 创建 DM 频道（存在则复用）
        return createDmChannelIfNotExists(userId, fromUserId);
    }

    @Transactional
    public void rejectFriendRequest(Long userId, Long fromUserId) {
        relationshipRepository.deleteByFromIdAndToId(userId, fromUserId);
        relationshipRepository.deleteByFromIdAndToId(fromUserId, userId);
    }

    @Transactional
    public void removeFriend(Long userId, Long friendId) {
        relationshipRepository.deleteByFromIdAndToId(userId, friendId);
        relationshipRepository.deleteByFromIdAndToId(friendId, userId);
    }

    @Transactional
    public void blockUser(Long userId, Long blockedId) {
        // 删除现有关系
        relationshipRepository.deleteByFromIdAndToId(userId, blockedId);
        relationshipRepository.deleteByFromIdAndToId(blockedId, userId);

        // 建立屏蔽
        Relationship block = Relationship.builder()
                .fromId(userId).toId(blockedId)
                .type(TYPE_BLOCKED)
                .since(Instant.now()).build();
        relationshipRepository.save(block);
    }

    @Transactional
    public void unblockUser(Long userId, Long blockedId) {
        relationshipRepository.deleteByFromIdAndToId(userId, blockedId);
    }

    /** 返回用户的所有好友 userId（type=1） */
    public List<Long> getFriendUserIds(Long userId) {
        return relationshipRepository.findByFromId(userId).stream()
                .filter(r -> r.getType() == TYPE_FRIEND)
                .map(Relationship::getToId)
                .toList();
    }

    public List<Map<String, Object>> getRelationships(Long userId) {
        List<Relationship> fromMe = relationshipRepository.findByFromId(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Relationship rel : fromMe) {
            userRepository.findById(rel.getToId()).ifPresent(user -> {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", rel.getToId().toString());
                entry.put("type", (int) rel.getType());
                entry.put("username", user.getUsername());
                entry.put("discriminator", user.getDiscriminator());
                entry.put("avatar", user.getAvatar());
                entry.put("global_name", user.getGlobalName());
                result.add(entry);
            });
        }
        return result;
    }

    private void updateRelationship(Long fromId, Long toId, short type) {
        relationshipRepository.deleteByFromIdAndToId(fromId, toId);
        Relationship rel = Relationship.builder()
                .fromId(fromId).toId(toId)
                .type(type).since(Instant.now()).build();
        relationshipRepository.save(rel);
    }

    private DmChannel createDmChannelIfNotExists(Long user1Id, Long user2Id) {
        // 检查是否已有DM频道
        List<Long> existingChannels = dmChannelMemberRepository.findByUserId(user1Id).stream()
                .filter(m -> dmChannelMemberRepository.findByUserId(user2Id).stream()
                        .anyMatch(m2 -> m2.getChannelId().equals(m.getChannelId())))
                .map(m -> m.getChannelId())
                .toList();

        if (!existingChannels.isEmpty()) { // 已有DM频道
            return dmChannelRepository.findById(existingChannels.get(0)).orElse(null);
        }

        Long channelId = snowflake.nextId();
        DmChannel dm = DmChannel.builder()
                .id(channelId)
                .type((short) 1)
                .createdAt(Instant.now())
                .build();
        dmChannelRepository.save(dm);

        dmChannelMemberRepository.save(
                new DmChannelMember(channelId, user1Id));
        dmChannelMemberRepository.save(
                new DmChannelMember(channelId, user2Id));
        return dm;
    }
}
