package org.chorus.repository;

import org.chorus.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
    List<Channel> findByGuildIdOrderByPositionAsc(Long guildId);
    List<Channel> findByGuildIdAndTypeOrderByPositionAsc(Long guildId, Short type);
}
