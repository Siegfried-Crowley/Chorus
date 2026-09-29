package org.chorus.repository;

import org.chorus.entity.GuildMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GuildMemberRepository extends JpaRepository<GuildMember, Long> {
    List<GuildMember> findByGuildId(Long guildId);
    List<GuildMember> findByUserId(Long userId);
    Optional<GuildMember> findByGuildIdAndUserId(Long guildId, Long userId);
    long countByGuildId(Long guildId);
    boolean existsByGuildIdAndUserId(Long guildId, Long userId);
    void deleteByGuildIdAndUserId(Long guildId, Long userId);
    void deleteByGuildId(Long guildId);
}
