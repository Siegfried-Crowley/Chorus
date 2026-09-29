package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "voice_allocations")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class VoiceAllocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long guildId;
    @Column(nullable = false) private Long channelId;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 50) private String serverId;
    @Column(nullable = false) private String token;
    @Column(nullable = false) private Integer ssrc;
    private String sessionId;
    private Instant allocatedAt;
    private Instant expiresAt;
}
