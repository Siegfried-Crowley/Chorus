package org.chorus.voice.sfu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * TCP 控制面（行协议，供主后端或运维脚本调用）：
 * <pre>
 *   JOIN &lt;channelId&gt; &lt;userId&gt;          加入频道
 *   LEAVE &lt;userId&gt;                     离开
 *   STATE &lt;userId&gt; &lt;muted&gt; &lt;deafened&gt;  更新静音/禁听（true/false）
 *   ADDR &lt;userId&gt; &lt;ip&gt; &lt;port&gt;          预登记成员地址（可选，未登记则由首包学习）
 *   STATS                              查询转发统计
 *   SHUTDOWN                           优雅停机
 *   PING                               存活探测 → PONG
 * </pre>
 * 仅监听回环地址，避免控制面暴露到外部。
 */
public class ControlServer implements Runnable {

    private final int port;
    private final ChannelRegistry registry;
    private final RtpRelayServer relay;
    private final RtpRelayServer.Stats stats;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private final Runnable shutdownHook;

    public ControlServer(int port, ChannelRegistry registry, RtpRelayServer relay,
                         RtpRelayServer.Stats stats, Runnable shutdownHook) {
        this.port = port;
        this.registry = registry;
        this.relay = relay;
        this.stats = stats;
        this.shutdownHook = shutdownHook;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port, 16, InetAddress.getLoopbackAddress());
            while (running) {
                Socket socket = serverSocket.accept();
                new Thread(() -> serve(socket), "ctrl-" + socket.getPort()).start();
            }
        } catch (IOException e) {
            if (running) throw new RuntimeException(e);
        }
    }

    private void serve(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), false,
                     StandardCharsets.UTF_8)) {
            String line;
            while (running && (line = in.readLine()) != null) {
                String reply = dispatch(line.trim());
                if (reply != null) {
                    out.write(reply + "\n");
                    out.flush();
                }
            }
        } catch (IOException e) {
            // 连接异常：结束该控制会话
        }
    }

    private String dispatch(String line) {
        if (line.isEmpty()) return null;
        String[] p = line.split("\\s+");
        try {
            switch (p[0].toUpperCase(Locale.ROOT)) {
                case "PING" -> { return "PONG"; }
                case "JOIN" -> {
                    registry.join(p[1], Long.parseLong(p[2]));
                    return "OK JOIN " + p[2] + "@" + p[1];
                }
                case "LEAVE" -> {
                    registry.removeUser(Long.parseLong(p[1]));
                    return "OK LEAVE " + p[1];
                }
                case "STATE" -> {
                    ChannelRegistry.Member m = registry.byUser(Long.parseLong(p[1]));
                    if (m == null) return "ERR no such member";
                    m.muted = Boolean.parseBoolean(p[2]);
                    m.deafened = Boolean.parseBoolean(p[3]);
                    return "OK STATE " + p[1];
                }
                case "ADDR" -> {
                    ChannelRegistry.Member m = registry.byUser(Long.parseLong(p[1]));
                    if (m == null) return "ERR no such member";
                    m.address = new InetSocketAddress(p[2], Integer.parseInt(p[3]));
                    return "OK ADDR " + p[1];
                }
                case "STATS" -> {
                    return String.format(Locale.ROOT,
                            "STATS channels=%d members=%d received=%d relayed=%d forwarded=%d "
                                    + "droppedInvalid=%d droppedMuted=%d",
                            registry.channelCount(), registry.memberCount(),
                            stats.received, stats.relayed, stats.forwarded,
                            stats.droppedInvalid, stats.droppedMuted);
                }
                case "SHUTDOWN" -> {
                    new Thread(shutdownHook, "shutdown").start();
                    return "OK BYE";
                }
                default -> { return "ERR unknown command"; }
            }
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        }
    }

    public void shutdown() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // 忽略
        }
    }
}
