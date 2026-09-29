package org.chorus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gateway WebSocket 协议消息
 * Gateway 协议实现 (v9)
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class GatewayMessage {
    private int op;           // Opcode
    private Object d;         // Event data
    private Integer s;        // Sequence number
    private String t;         // Event type (for OP 0 Dispatch)
    private Integer retryAfter;      // Rate limit retry-after

    // Opcodes
    public static final int OP_DISPATCH = 0;
    public static final int OP_HEARTBEAT = 1;
    public static final int OP_IDENTIFY = 2;
    public static final int OP_PRESENCE_UPDATE = 3;
    public static final int OP_VOICE_STATE_UPDATE = 4;
    public static final int OP_VOICE_SERVER_PING = 5;
    public static final int OP_RESUME = 6;
    public static final int OP_RECONNECT = 7;
    public static final int OP_REQUEST_GUILD_MEMBERS = 8;
    public static final int OP_INVALID_SESSION = 9;
    public static final int OP_HELLO = 10;
    public static final int OP_HEARTBEAT_ACK = 11;
    public static final int OP_GUILD_SYNC = 12;
}
