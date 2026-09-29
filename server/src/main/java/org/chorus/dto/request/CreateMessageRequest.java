package org.chorus.dto.request;

import lombok.Data;

@Data
public class CreateMessageRequest {
    private String content;
    private String nonce;
    private String messageReference; // JSON
    private String attachments;      // JSON 数组字符串，如 [{"url":"...","filename":"a.png","content_type":"image/png","size":123}]
}
