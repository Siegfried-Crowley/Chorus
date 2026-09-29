package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "channels")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Channel {
    @Id private Long id;
    private Long guildId;
    @Column(length = 100) private String name;
    @Column(columnDefinition = "TEXT") private String topic;
    @Column(nullable = false) private Short type;
    private Integer position;
    private Long parentId;
    private boolean nsfw;
    private Integer bitrate;
    private Integer userLimit;
    private String rtcRegion;
    private Integer videoQualityMode;
    private Integer rateLimitPerUser;
    private Long lastMessageId;
    private Instant createdAt;
}
