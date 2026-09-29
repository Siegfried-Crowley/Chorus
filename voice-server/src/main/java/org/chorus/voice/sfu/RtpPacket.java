package org.chorus.voice.sfu;

import java.net.DatagramPacket;

/**
 * 最小 RTP 包解析。只读取转发决策所需的头部字段，不触碰载荷（Opus/WebM 帧原样转发）。
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * </pre>
 */
public final class RtpPacket {

    private final int version;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final byte[] raw;

    private RtpPacket(int version, int payloadType, int sequenceNumber,
                      long timestamp, long ssrc, byte[] raw) {
        this.version = version;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.ssrc = ssrc;
        this.raw = raw;
    }

    /** 解析失败（长度不足或版本号非法）返回 null，由调用方丢弃 */
    public static RtpPacket parse(DatagramPacket datagram) {
        byte[] data = datagram.getData();
        int len = datagram.getLength();
        if (len < 12) return null;
        int version = (data[0] >> 6) & 0x03;
        if (version != 2) return null;
        int payloadType = data[1] & 0x7F;
        int sequenceNumber = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        long timestamp = ((data[4] & 0xFFL) << 24) | ((data[5] & 0xFFL) << 16)
                | ((data[6] & 0xFFL) << 8) | (data[7] & 0xFFL);
        long ssrc = ((data[8] & 0xFFL) << 24) | ((data[9] & 0xFFL) << 16)
                | ((data[10] & 0xFFL) << 8) | (data[11] & 0xFFL);
        byte[] raw = new byte[len];
        System.arraycopy(data, 0, raw, 0, len);
        return new RtpPacket(version, payloadType, sequenceNumber, timestamp, ssrc, raw);
    }

    public int version() { return version; }
    public int payloadType() { return payloadType; }
    public int sequenceNumber() { return sequenceNumber; }
    public long timestamp() { return timestamp; }
    public long ssrc() { return ssrc; }
    /** 原始报文（含 RTP 头），转发时原样发送 */
    public byte[] raw() { return raw; }
}
