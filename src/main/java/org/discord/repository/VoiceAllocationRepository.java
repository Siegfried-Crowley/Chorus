package org.discord.repository;

import org.discord.entity.VoiceAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface VoiceAllocationRepository extends JpaRepository<VoiceAllocation, Long> {
    Optional<VoiceAllocation> findByGuildIdAndUserId(Long guildId, Long userId);
    Optional<VoiceAllocation> findByToken(String token);
    void deleteByGuildIdAndUserId(Long guildId, Long userId);
    void deleteByGuildId(Long guildId);
    void deleteByChannelId(Long channelId);
}
