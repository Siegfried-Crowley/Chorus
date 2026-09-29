package org.discord.repository;

import org.discord.entity.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InviteRepository extends JpaRepository<Invite, String> {
    List<Invite> findByGuildIdOrderByCreatedAtDesc(Long guildId);
    void deleteByGuildId(Long guildId);
    void deleteByChannelId(Long channelId);
}
