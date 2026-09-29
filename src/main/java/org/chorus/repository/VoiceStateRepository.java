package org.chorus.repository;

import org.chorus.entity.VoiceState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VoiceStateRepository extends JpaRepository<VoiceState, Long> {
    List<VoiceState> findByChannelId(Long channelId);
    Optional<VoiceState> findByGuildIdAndUserId(Long guildId, Long userId);
    void deleteByGuildIdAndUserId(Long guildId, Long userId);
    void deleteByGuildId(Long guildId);
    void deleteByChannelId(Long channelId);
}
