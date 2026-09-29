package org.chorus.repository;

import org.chorus.entity.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {
    List<AuditLogEntry> findByGuildIdOrderByCreatedAtDesc(Long guildId);
    void deleteByGuildId(Long guildId);
}
