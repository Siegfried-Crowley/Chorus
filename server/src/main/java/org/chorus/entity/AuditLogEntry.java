package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "audit_log")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLogEntry {
    @Id private Long id;
    @Column(nullable = false) private Long guildId;
    @Column(nullable = false) private Long actorId;
    private Long targetId;
    @Column(nullable = false) private Integer actionType;
    @Lob private String changes;
    @Lob private String options;
    private String reason;
    private Instant createdAt;
}
