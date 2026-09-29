package org.chorus.repository;

import org.chorus.entity.Message;
import org.chorus.entity.MessageId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, MessageId> {
    List<Message> findByChannelIdOrderByCreatedAtDesc(Long channelId, Pageable pageable);
    List<Message> findByChannelIdAndIdLessThanOrderByCreatedAtDesc(Long channelId, Long beforeId, Pageable pageable);
    List<Message> findByChannelIdAndIdGreaterThanOrderByCreatedAtAsc(Long channelId, Long afterId, Pageable pageable);
    List<Message> findByChannelIdAndPinnedTrueOrderByCreatedAtDesc(Long channelId);
    long countByChannelId(Long channelId);
    void deleteByChannelId(Long channelId);

    @Query("SELECT m FROM Message m WHERE m.guildId = :guildId " +
            "AND (:channelId IS NULL OR m.channelId = :channelId) " +
            "AND m.content LIKE :pattern ESCAPE '\\' ORDER BY m.createdAt DESC")
    List<Message> search(@Param("guildId") Long guildId,
                         @Param("channelId") Long channelId,
                         @Param("pattern") String pattern,
                         Pageable pageable);
}
