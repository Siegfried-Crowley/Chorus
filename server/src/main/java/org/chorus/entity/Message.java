package org.chorus.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "messages")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(MessageId.class)
public class Message {
    @Id private Long id;
    @Id private Long channelId;
    private Long guildId;
    @Column(nullable = false) private Long authorId;
    @Column(columnDefinition = "TEXT") private String content;
    // 这些字段以 JSON 字符串存储;用 @Lob(而非 columnDefinition="JSON")避免
    // Hibernate 6 的 JSON JdbcType 把 String 再序列化一次(双重编码)
    @Lob private String embeds;
    @Lob private String attachments;
    @Lob private String stickers;
    @Lob private String reactions;
    @Lob private String mentions;
    private Short type;
    private Integer flags;
    private boolean pinned;
    private boolean mentionEveryone;
    @Lob private String messageReference;
    @Column(length = 100) private String nonce;
    private Instant editedTimestamp;
    private Instant createdAt;
}
