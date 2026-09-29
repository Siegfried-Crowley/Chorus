package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "guild_members")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(GuildMemberId.class)
public class GuildMember {
    @Id private Long guildId;
    @Id private Long userId;
    @Column(length = 32) private String nickname;
    private String avatar;
    private Instant joinedAt;
    private boolean deaf;
    private boolean mute;
    private boolean pending;
}

@Data @NoArgsConstructor @AllArgsConstructor
class GuildMemberId implements java.io.Serializable {
    private Long guildId;
    private Long userId;
}
