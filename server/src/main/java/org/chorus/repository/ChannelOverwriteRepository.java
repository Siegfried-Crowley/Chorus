package org.chorus.repository;

import org.chorus.entity.ChannelOverwrite;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChannelOverwriteRepository extends JpaRepository<ChannelOverwrite, Long> {
    List<ChannelOverwrite> findByChannelId(Long channelId);
    List<ChannelOverwrite> findByChannelIdIn(List<Long> channelIds);
    void deleteByChannelId(Long channelId);
}
