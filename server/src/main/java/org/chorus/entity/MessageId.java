package org.chorus.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data @NoArgsConstructor @AllArgsConstructor
public class MessageId implements Serializable {
    private Long channelId;
    private Long id;
}
