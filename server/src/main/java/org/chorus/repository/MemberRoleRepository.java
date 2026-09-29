package org.chorus.repository;

import org.chorus.entity.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MemberRoleRepository extends JpaRepository<MemberRole, Long> {
    List<MemberRole> findByGuildIdAndUserId(Long guildId, Long userId);
    List<MemberRole> findByUserId(Long userId);
    void deleteByGuildIdAndUserIdAndRoleId(Long guildId, Long userId, Long roleId);
    void deleteByGuildIdAndUserId(Long guildId, Long userId);
    void deleteByGuildId(Long guildId);
    void deleteByRoleId(Long roleId);
}
