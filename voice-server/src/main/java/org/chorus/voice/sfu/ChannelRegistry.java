package org.chorus.voice.sfu;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 频道成员注册表：channelId → (userId → Member)。
 *
 * <p>转发规则所需的状态全部在此维护：成员地址由首个 RTP 包自动补全（NAT 友好），
 * 静音/禁听标志由控制面线程写入、转发线程读取，字段用 volatile 保证可见性。
 */
public class ChannelRegistry {

    public static final class Member {
        public final long userId;
        public final String channelId;
        public volatile InetSocketAddress address;
        public volatile boolean muted;
        public volatile boolean deafened;
        public volatile long ssrc = -1;
        public volatile long packetsRelayed;

        Member(long userId, String channelId) {
            this.userId = userId;
            this.channelId = channelId;
        }
    }

    private final Map<String, Map<Long, Member>> channels = new ConcurrentHashMap<>();
    private final Map<Long, Member> membersByUser = new ConcurrentHashMap<>();

    public Member join(String channelId, long userId) {
        removeUser(userId); // 幂等：同一用户先离开旧频道
        Member m = new Member(userId, channelId);
        channels.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>()).put(userId, m);
        membersByUser.put(userId, m);
        return m;
    }

    public void removeUser(long userId) {
        Member m = membersByUser.remove(userId);
        if (m == null) return;
        Map<Long, Member> members = channels.get(m.channelId);
        if (members != null) {
            members.remove(userId);
            if (members.isEmpty()) channels.remove(m.channelId);
        }
    }

    public Member byUser(long userId) {
        return membersByUser.get(userId);
    }

    public Member bySsrc(long ssrc) {
        for (Member m : membersByUser.values()) {
            if (m.ssrc == ssrc) return m;
        }
        return null;
    }

    public Collection<Member> membersOf(String channelId) {
        Map<Long, Member> members = channels.get(channelId);
        return members == null ? List.of() : members.values();
    }

    public Collection<Member> allMembers() {
        return List.copyOf(membersByUser.values());
    }

    public int channelCount() {
        return channels.size();
    }

    public int memberCount() {
        return membersByUser.size();
    }
}
