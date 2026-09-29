package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "channel_overwrites")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChannelOverwrite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long channelId;
    @Column(nullable = false) private Short type;  // 0: role, 1: member
    @Column(nullable = false) private Long targetId;
    @Column(columnDefinition = "BIGINT") private Long allow;
    @Column(columnDefinition = "BIGINT") private Long deny;
}
