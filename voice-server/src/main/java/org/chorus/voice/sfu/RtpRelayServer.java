package org.chorus.voice.sfu;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

/**
 * RTP 数据面：单线程接收 UDP 报文并做选择性转发。
 *
 * <p>转发语义与主应用内嵌中继一致：
 * <ul>
 *   <li>发送者识别：以 RTP 头中的 SSRC 为准，首个报文自动学习源地址；</li>
 *   <li>无回声：跳过发送者本身；</li>
 *   <li>静音：muted 发送者的帧直接丢弃；</li>
 *   <li>禁听：deafened 接收者被跳过；</li>
 *   <li>载荷透明：RTP 头与载荷原样转发，不做重编码。</li>
 * </ul>
 */
public class RtpRelayServer implements Runnable {

    private static final int MAX_PACKET_SIZE = 1500; // MTU

    private final DatagramSocket socket;
    private final ChannelRegistry registry;
    private final Stats stats;
    private volatile boolean running = true;

    public static final class Stats {
        public volatile long received;
        public volatile long relayed;
        public volatile long droppedInvalid;
        public volatile long droppedMuted;
        public volatile long forwarded; // 按接收者计的转发次数
    }

    public RtpRelayServer(int udpPort, ChannelRegistry registry, Stats stats) throws SocketException {
        this.socket = new DatagramSocket(new InetSocketAddress(udpPort));
        this.registry = registry;
        this.stats = stats;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);
                stats.received++;
                handle(packet);
            } catch (IOException e) {
                if (running) { /* 记录后继续，单包错误不中断服务 */ }
            }
        }
    }

    private void handle(DatagramPacket packet) {
        RtpPacket rtp = RtpPacket.parse(packet);
        if (rtp == null) {
            stats.droppedInvalid++;
            return;
        }
        ChannelRegistry.Member sender = registry.bySsrc(rtp.ssrc());
        if (sender == null) {
            // 未知 SSRC：按源地址尝试匹配已注册成员（首包地址补全）
            sender = findByAddress((InetSocketAddress) packet.getSocketAddress());
            if (sender == null) {
                stats.droppedInvalid++;
                return;
            }
        }
        sender.address = (InetSocketAddress) packet.getSocketAddress();
        if (sender.ssrc != rtp.ssrc()) sender.ssrc = rtp.ssrc();
        if (sender.muted) {
            stats.droppedMuted++;
            return;
        }
        relay(sender, rtp);
    }

    private ChannelRegistry.Member findByAddress(InetSocketAddress address) {
        for (var member : registry.allMembers()) {
            if (address.equals(member.address)) return member;
        }
        return null;
    }

    private void relay(ChannelRegistry.Member sender, RtpPacket rtp) {
        stats.relayed++;
        for (ChannelRegistry.Member listener : registry.membersOf(sender.channelId)) {
            if (listener.userId == sender.userId) continue; // 无回声
            if (listener.deafened) continue;                // 禁听：不接
            if (listener.address == null) continue;         // 地址未学习（尚未发过包/心跳）
            send(listener.address, rtp.raw());
            listener.packetsRelayed++;
            stats.forwarded++;
        }
    }

    private void send(InetSocketAddress target, byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, target));
        } catch (IOException e) {
            // 单个接收者发送失败：静默丢弃（慢接收者不阻塞转发线程）
        }
    }

    public void shutdown() {
        running = false;
        socket.close();
    }
}
