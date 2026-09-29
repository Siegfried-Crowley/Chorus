package org.chorus.voice;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice SFU (Selective Forwarding Unit) 服务
 * 处理 WebRTC 音频流的 UDP 转发
 *
 * 架构: 简单的选择性转发，上行的 Opus 音频包通过 UDP 接收后
 * 加密转发给频道内的其他用户
 */
@Component
public class VoiceSfuServer {
    private static final Logger log = LoggerFactory.getLogger(VoiceSfuServer.class);

    @Value("${app.voice.server-port:4003}")
    private int udpPort;

    private DatagramSocket udpSocket;
    private volatile boolean running = false;

    // 频道 → 用户 → 地址映射
    private final Map<String, Map<Long, UserConnection>> channels = new ConcurrentHashMap<>();

    static class UserConnection {
        long userId;
        String channelId;
        InetSocketAddress address;
        int ssrc;
        boolean speaking;

        UserConnection(long userId, String channelId, InetSocketAddress address, int ssrc) {
            this.userId = userId;
            this.channelId = channelId;
            this.address = address;
            this.ssrc = ssrc;
            this.speaking = false;
        }
    }

    @PostConstruct
    public void start() {
        try {
            udpSocket = new DatagramSocket(udpPort);
            running = true;
            log.info("Voice SFU UDP server started on port {}", udpPort);

            new Thread(this::receiveLoop, "voice-sfu-udp").start();
            log.info("Voice SFU server initialized");
        } catch (IOException e) {
            log.error("Failed to start Voice SFU server", e);
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[1500]; // MTU size
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running) {
            try {
                packet.setLength(buffer.length);
                udpSocket.receive(packet);

                // 解析 RTP 包
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

                handleRtpPacket(data, packet.getSocketAddress());
            } catch (IOException e) {
                if (running) {
                    log.error("UDP receive error", e);
                }
            }
        }
    }

    private void handleRtpPacket(byte[] data, java.net.SocketAddress senderAddr) {
        if (data.length < 12) return; // 最小 RTP 头 12 字节

        // 解析 RTP 头
        int version = (data[0] >> 6) & 0x03;
        int payloadType = data[1] & 0x7F;
        int sequenceNumber = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int ssrc = ((data[8] & 0xFF) << 24) | ((data[9] & 0xFF) << 16) |
                   ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);

        // 查找发送者所属频道
        for (Map.Entry<String, Map<Long, UserConnection>> channelEntry : channels.entrySet()) {
            String channelId = channelEntry.getKey();
            Map<Long, UserConnection> users = channelEntry.getValue();

            // 找到发送者
            UserConnection sender = null;
            for (UserConnection uc : users.values()) {
                if (uc.ssrc == ssrc) {
                    sender = uc;
                    sender.address = (InetSocketAddress) senderAddr; // 更新地址
                    break;
                }
            }
            if (sender == null) continue;

            // 选择性转发: 转发给频道内其他用户
            for (UserConnection listener : users.values()) {
                if (listener.userId == sender.userId) continue;
                if (listener.address == null) continue;

                try {
                    DatagramPacket forwardPacket = new DatagramPacket(
                            data, data.length, listener.address);
                    udpSocket.send(forwardPacket);
                } catch (IOException e) {
                    log.debug("Failed to forward packet to user {}", listener.userId);
                }
            }
        }
    }

    // 用户加入语音频道
    public void userJoined(String channelId, long userId, int ssrc, InetSocketAddress address) {
        channels.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>())
                .put(userId, new UserConnection(userId, channelId, address, ssrc));
        log.info("User {} joined voice channel {} (SSRC: {})", userId, channelId, ssrc);
    }

    // 用户离开语音频道
    public void userLeft(String channelId, long userId) {
        Map<Long, UserConnection> users = channels.get(channelId);
        if (users != null) {
            users.remove(userId);
            if (users.isEmpty()) {
                channels.remove(channelId);
            }
        }
        log.info("User {} left voice channel {}", userId, channelId);
    }

    // 获取频道状态
    public int getChannelUserCount(String channelId) {
        Map<Long, UserConnection> users = channels.get(channelId);
        return users != null ? users.size() : 0;
    }

    public int getTotalChannels() {
        return channels.size();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        channels.clear();
        log.info("Voice SFU server stopped");
    }
}
