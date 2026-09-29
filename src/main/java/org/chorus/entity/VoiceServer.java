package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "voice_servers")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class VoiceServer {
    @Id @Column(length = 50) private String id;
    @Column(nullable = false, length = 45) private String ip;
    @Column(nullable = false) private Integer port;
    @Column(length = 20) private String region;
    private Double currentLoad;
    private Integer maxSessions;
    private Integer currentSessions;
    @Column(length = 20) private String status;
    private Instant lastHeartbeat;
}
