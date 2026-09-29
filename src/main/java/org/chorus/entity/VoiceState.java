package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "voice_states")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(VoiceStateId.class)
public class VoiceState {
    @Id private Long guildId;
    @Id private Long userId;
    @Column(nullable = false) private Long channelId;
    @Column(nullable = false) private String sessionId;
    private boolean selfMute;
    private boolean selfDeaf;
    private boolean mute;
    private boolean deaf;
    private boolean suppress;
    private Instant requestToSpeakTimestamp;
    private Instant joinedAt;
}

@Data @NoArgsConstructor @AllArgsConstructor
class VoiceStateId implements java.io.Serializable {
    private Long guildId;
    private Long userId;
}
