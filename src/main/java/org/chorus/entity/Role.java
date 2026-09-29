package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "roles")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Role {
    @Id private Long id;
    @Column(nullable = false) private Long guildId;
    @Column(nullable = false, length = 100) private String name;
    private Integer color;
    private boolean hoist;
    private Integer position;
    @Column(columnDefinition = "BIGINT") private Long permissions;
    private boolean managed;
    private boolean mentionable;
    private String icon;
    private String unicodeEmoji;
    private Instant createdAt;
}
