package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "invites")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Invite {
    @Id @Column(nullable = false, length = 8) private String code;
    @Column(nullable = false) private Long guildId;
    @Column(nullable = false) private Long channelId;
    @Column(nullable = false) private Long inviterId;
    private Integer maxUses;
    private Integer maxAge;
    private Integer uses;
    private Instant createdAt;
    private Instant expiresAt;
}
