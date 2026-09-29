package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "users")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id private Long id;
    @Column(nullable = false, length = 32) private String username;
    @Column(length = 4) private String discriminator;
    @Column(length = 32) private String globalName;
    @Column(unique = true, nullable = false) private String email;
    @Column(nullable = false) private String passwordHash;
    private String avatar;
    private String banner;
    private Integer accentColor;
    @Column(columnDefinition = "TEXT") private String aboutMe;
    @Column(length = 10) private String locale;
    private boolean mfaEnabled;
    private boolean verified;
    private int flags;
    private int premiumType;
    private int publicFlags;
    private Instant lastSeen;
    private Instant createdAt;
}
