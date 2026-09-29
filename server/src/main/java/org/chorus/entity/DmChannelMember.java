package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "dm_channel_members")
@Data @NoArgsConstructor @AllArgsConstructor
@IdClass(DmChannelMemberId.class)
public class DmChannelMember {
    @Id private Long channelId;
    @Id private Long userId;
}

@Data @NoArgsConstructor @AllArgsConstructor
class DmChannelMemberId implements java.io.Serializable {
    private Long channelId;
    private Long userId;
}
