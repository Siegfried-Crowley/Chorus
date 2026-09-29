package org.chorus.voice.sfu;

import java.util.concurrent.CountDownLatch;

/**
 * 独立语音转发器入口。
 *
 * <p>用法：java -cp target/classes org.chorus.voice.sfu.VoiceSfuApp [rtpPort] [controlPort]
 * 默认 RTP 4003/UDP、控制 4004/TCP，与主应用 application.yml 中
 * app.voice.server-port / app.voice.ws-port 保持一致。作为容器化/独立部署形态时，
 * 主后端通过控制面维护频道成员表，音频数据面完全在此进程内转发。
 */
public final class VoiceSfuApp {

    public static void main(String[] args) throws Exception {
        int rtpPort = args.length > 0 ? Integer.parseInt(args[0]) : 4003;
        int controlPort = args.length > 1 ? Integer.parseInt(args[1]) : 4004;

        ChannelRegistry registry = new ChannelRegistry();
        RtpRelayServer.Stats stats = new RtpRelayServer.Stats();
        RtpRelayServer relay = new RtpRelayServer(rtpPort, registry, stats);

        CountDownLatch stopped = new CountDownLatch(1);
        ControlServer[] holder = new ControlServer[1];
        ControlServer control = new ControlServer(controlPort, registry, relay, stats, () -> {
            relay.shutdown();
            if (holder[0] != null) holder[0].shutdown();
            stopped.countDown();
        });
        holder[0] = control;

        Thread relayThread = new Thread(relay, "rtp-relay");
        relayThread.setDaemon(true);
        relayThread.start();
        Thread controlThread = new Thread(control, "control");
        controlThread.setDaemon(true);
        controlThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown));
        System.out.printf("Voice SFU started: rtp=udp/%d control=tcp/%d (loopback)%n", rtpPort, controlPort);
        stopped.await();
        System.out.println("Voice SFU stopped");
    }

    private VoiceSfuApp() {
    }
}
