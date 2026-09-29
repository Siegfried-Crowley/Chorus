# ============================================================================
# voice-server 冒烟测试：验证 选择性转发 / 无回声 / 禁听 / 静音 / 统计 语义
# 前置：VoiceSfuApp 已在本机运行（RTP 4003/UDP，控制 4004/TCP）
# 用法：python smoke_test.py
# ============================================================================
import socket
import struct
import sys
import time

CTRL = ("127.0.0.1", 4004)
RTP = ("127.0.0.1", 4003)
CHANNEL = "smoke-ch"


def ctrl(command: str) -> str:
    s = socket.create_connection(CTRL, timeout=3)
    try:
        s.sendall((command + "\n").encode())
        time.sleep(0.05)
        return s.recv(4096).decode().strip()
    finally:
        s.close()


def rtp_packet(ssrc: int, seq: int, body: bytes) -> bytes:
    header = struct.pack("!BBHII", 0x80, 0, seq, seq * 20, ssrc)  # V=2, PT=0
    return header + body


def open_rtp() -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    s.settimeout(2)
    # 用 ADDR 预登记地址，转发器无需等首包学习
    return s


def main():
    print("PING ->", ctrl("PING"))
    assert ctrl("PING") == "PONG", "控制面不可达"

    a, b, c = open_rtp(), open_rtp(), open_rtp()
    a_addr, b_addr, c_addr = a.getsockname(), b.getsockname(), c.getsockname()
    print("JOIN ->", ctrl(f"JOIN {CHANNEL} 101"))
    print("JOIN ->", ctrl(f"JOIN {CHANNEL} 202"))
    print("JOIN ->", ctrl(f"JOIN {CHANNEL} 303"))
    print("ADDR ->", ctrl(f"ADDR 101 {a_addr[0]} {a_addr[1]}"))
    print("ADDR ->", ctrl(f"ADDR 202 {b_addr[0]} {b_addr[1]}"))
    print("ADDR ->", ctrl(f"ADDR 303 {c_addr[0]} {c_addr[1]}"))
    print("STATE(C deafened) ->", ctrl("STATE 303 false true"))

    # A(101) 发 5 包 → B 收 5，C(禁听) 收 0，A 自己收 0
    body = b"opus-frame-" + b"\x01" * 21
    for seq in range(5):
        a.sendto(rtp_packet(0x0AAA0001, seq, body), RTP)
        time.sleep(0.02)
    b_rx = [b.recv(4096) for _ in range(5)]
    assert len(b_rx) == 5, "B 应收到 5 包"
    assert all(p == rtp_packet(0x0AAA0001, i, body) for i, p in enumerate(b_rx)), "B 收到的帧应逐字节一致"
    try:
        c.recv(1024)
        raise AssertionError("C 已禁听，不应收到任何包")
    except socket.timeout:
        pass
    try:
        a.recv(1024)
        raise AssertionError("A 不应收到自己发出的包（无回声）")
    except socket.timeout:
        pass
    print("relay+no-echo+deafen OK: B got 5 packets byte-identical, C got 0, A got 0")

    # B(202) 回 1 包 → A 收 1
    body_b = b"reply-frame" + b"\x02" * 21
    b.sendto(rtp_packet(0x0BBB0002, 0, body_b), RTP)
    a_rx = a.recv(4096)
    assert a_rx == rtp_packet(0x0BBB0002, 0, body_b), "A 收到的回包应一致"
    print("bidirectional OK")

    # A 静音后再发 → 无人收到
    print("STATE(A muted) ->", ctrl("STATE 101 true false"))
    a.sendto(rtp_packet(0x0AAA0001, 5, body), RTP)
    try:
        b.recv(1024)
        raise AssertionError("A 已静音，B 不应收到包")
    except socket.timeout:
        pass
    print("mute OK")

    stats = ctrl("STATS")
    print("STATS ->", stats)
    assert "received=7" in stats and "forwarded=6" in stats and "droppedMuted=1" in stats, stats

    for s in (a, b, c):
        s.close()
    print("SMOKE TEST PASSED")
    print("SHUTDOWN ->", ctrl("SHUTDOWN"))
    sys.exit(0)


if __name__ == "__main__":
    main()
