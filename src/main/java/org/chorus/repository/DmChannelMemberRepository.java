package org.chorus.repository;

import org.chorus.entity.DmChannelMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DmChannelMemberRepository extends JpaRepository<DmChannelMember, Long> {
    List<DmChannelMember> findByUserId(Long userId);
    List<DmChannelMember> findByChannelId(Long channelId);
}
