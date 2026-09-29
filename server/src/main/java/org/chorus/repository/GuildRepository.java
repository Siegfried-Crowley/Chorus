package org.chorus.repository;

import org.chorus.entity.Guild;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GuildRepository extends JpaRepository<Guild, Long> {
    List<Guild> findByOwnerId(Long ownerId);
}
