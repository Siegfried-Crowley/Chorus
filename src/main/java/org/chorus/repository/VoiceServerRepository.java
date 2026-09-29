package org.chorus.repository;

import org.chorus.entity.VoiceServer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VoiceServerRepository extends JpaRepository<VoiceServer, String> {
    List<VoiceServer> findByRegionAndStatus(String region, String status);
}
