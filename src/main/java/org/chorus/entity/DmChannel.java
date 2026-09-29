package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "dm_channels")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class DmChannel {
    @Id private Long id;
    private Short type;
    @Column(length = 100) private String name;
    private String icon;
    private Long ownerId;
    private Long lastMessageId;
    private Instant createdAt;
}
