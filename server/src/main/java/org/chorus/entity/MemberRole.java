package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "member_roles")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(MemberRoleId.class)
public class MemberRole {
    @Id private Long guildId;
    @Id private Long userId;
    @Id private Long roleId;
}

@Data @NoArgsConstructor @AllArgsConstructor
class MemberRoleId implements java.io.Serializable {
    private Long guildId;
    private Long userId;
    private Long roleId;
}
