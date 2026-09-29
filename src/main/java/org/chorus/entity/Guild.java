package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "guilds")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Guild {
    @Id private Long id;
    @Column(nullable = false, length = 100) private String name;
    private String icon;
    private String splash;
    private String banner;
    @Column(nullable = false) private Long ownerId;
    private Long afkChannelId;
    private Integer afkTimeout;
    private Integer verificationLevel;
    private Integer defaultMessageNotifications;
    private Integer explicitContentFilter;
    private Integer mfaLevel;
    private Long systemChannelId;
    private Long rulesChannelId;
    private Long publicUpdatesChannelId;
    private String preferredLocale;
    private Integer premiumTier;
    private Integer memberCount;
    private Integer maxMembers;
    private Integer maxPresences;
    private Integer maxVideoChannelUsers;
    private Instant createdAt;
}
