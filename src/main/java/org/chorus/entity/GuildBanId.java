package org.chorus.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data @NoArgsConstructor @AllArgsConstructor
public class GuildBanId implements Serializable {
    private Long guildId;
    private Long userId;
}
