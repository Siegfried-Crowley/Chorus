package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "bans")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(GuildBanId.class)
public class GuildBan {
    @Id private Long guildId;
    @Id private Long userId;
    private String reason;
    private Instant createdAt;
}
