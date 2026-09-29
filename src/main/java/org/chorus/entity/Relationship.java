package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "relationships")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Relationship {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long fromId;
    @Column(nullable = false) private Long toId;
    @Column(nullable = false) private Short type;
    private Instant since;
}
