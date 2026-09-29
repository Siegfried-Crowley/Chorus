package org.discord.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data @AllArgsConstructor @Builder
public class AuthResponse {
    private String token;
    private UserInfo user;
    private String sessionId;

    @Data @AllArgsConstructor @Builder
    public static class UserInfo {
        /** 雪花 ID 超出 JS 安全整数范围(2^53),必须序列化为字符串,否则前端精度丢失 */
        private String id;
        private String username;
        private String discriminator;
        private String globalName;
        private String email;
        private String avatar;
        private String banner;
        private Integer accentColor;
        private String aboutMe;
        private String locale;
        private boolean verified;
        private boolean mfaEnabled;
        private int flags;
        private int premiumType;
    }
}
