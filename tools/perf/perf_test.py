# ============================================================================
# Discord Clone 性能基准测试
# 测量: REST API 延迟 / Gateway 广播扇出延迟 / 心跳 RTT / 语音中继端到端延迟
# 运行前提: 后端已启动(http://localhost:4001/chorus), 限流已放宽
# 用法: python perf_test.py [--base http://localhost:4001/chorus] [--out perf-results.json]
# ============================================================================
import argparse
import asyncio
import json
import statistics
import struct
import time

import httpx
import websockets

WS_BASE = "ws://localhost:4001/chorus"
ALICE = {"email": "alice2@test.dev", "password": "test123456"}
BOB = {"email": "bob2@test.dev", "password": "test123456"}


def pct(values, p):
    values = sorted(values)
    idx = min(len(values) - 1, max(0, round(p / 100 * len(values)) - 1))
    return values[idx]


def stat_block(values_ms):
    return {
        "n": len(values_ms),
        "avg_ms": round(statistics.mean(values_ms), 2),
        "p50_ms": round(pct(values_ms, 50), 2),
        "p95_ms": round(pct(values_ms, 95), 2),
        "min_ms": round(min(values_ms), 2),
        "max_ms": round(max(values_ms), 2),
    }


async def login(http, creds):
    r = await http.post("/api/auth/login", json=creds)
    r.raise_for_status()
    data = r.json()
    token = data.get("token") or data.get("access_token")
    me = await http.get("/api/auth/me", headers={"Authorization": f"Bearer {token}"})
    me.raise_for_status()
    me_data = me.json()
    user_id = str(me_data.get("id") or me_data.get("user", {}).get("id"))
    return token, user_id, me_data


async def setup_world(http_alice, http_bob):
    """创建群组/文字频道/语音频道, 邀请 Bob 加入, 返回相关 ID"""
    r = await http_alice.post("/api/guilds", json={"name": "性能测试服务器"})
    r.raise_for_status()
    guild_id = r.json()["id"]

    r = await http_alice.post("/api/channels", json={"guild_id": guild_id, "name": "文字频道", "type": 0})
    r.raise_for_status()
    text_id = r.json()["id"]

    r = await http_alice.post("/api/channels", json={"guild_id": guild_id, "name": "语音频道", "type": 2})
    r.raise_for_status()
    voice_id = r.json()["id"]

    r = await http_alice.post(f"/api/guilds/{guild_id}/invites", json={})
    r.raise_for_status()
    code = r.json()["code"]

    r = await http_bob.post(f"/api/invites/{code}/join", json={})
    r.raise_for_status()
    return guild_id, text_id, voice_id


async def seed_messages(http, channel_id, n=300):
    """灌入 n 条消息 (顺序写, 供读接口压测)"""
    for i in range(n):
        r = await http.post(f"/api/channels/{channel_id}/messages",
                            json={"content": f"基准测试消息 {i:04d} alpha_beta"})
        r.raise_for_status()


async def bench_rest(http, guild_id, channel_id):
    """顺序 100 次 + 并发 20x100 次测量核心 REST 接口延迟"""
    result = {}

    async def timed(coro):
        t0 = time.perf_counter()
        resp = await coro
        resp.raise_for_status()
        return (time.perf_counter() - t0) * 1000

    # 顺序: 读消息分页
    seq_read = [await timed(http.get(f"/api/channels/{channel_id}/messages?limit=50"))
                for _ in range(100)]
    result["read_messages_seq"] = stat_block(seq_read)

    # 顺序: 写消息
    seq_write = [await timed(http.post(f"/api/channels/{channel_id}/messages",
                                       json={"content": f"延迟采样 {i}"}))
                 for i in range(100)]
    result["post_message_seq"] = stat_block(seq_write)

    # 顺序: 搜索
    seq_search = [await timed(http.get(f"/api/guilds/{guild_id}/messages/search?query=alpha_beta"))
                  for _ in range(50)]
    result["search_seq"] = stat_block(seq_search)

    # 并发 20: 读消息分页 (20 并发 x 100 次)
    sem = asyncio.Semaphore(20)

    async def worker(i, lat):
        async with sem:
            lat.append(await timed(http.get(f"/api/channels/{channel_id}/messages?limit=50")))

    lat = []
    await asyncio.gather(*(worker(i, lat) for i in range(2000)))
    result["read_messages_conc20"] = stat_block(lat)

    return result


async def gw_connect(token):
    """建立 Gateway 连接并完成 Identify, 返回 (ws, session_id)"""
    ws = await websockets.connect(f"{WS_BASE}/ws", max_size=2**22)
    # Hello
    while True:
        hello = json.loads(await ws.recv())
        if hello.get("op") == 10:
            break
    await ws.send(json.dumps({"op": 2, "d": {"token": token, "capabilities": 16381,
                                             "compress": False, "intents": 32767}}))
    while True:
        msg = json.loads(await ws.recv())
        if msg.get("op") == 0 and msg.get("t") == "READY":
            return ws, msg["d"]["session_id"]


async def bench_gateway(token, channel_id):
    """广播扇出: K 个网关会话同时在线, 测量从 REST 发帖到各会话收到 MESSAGE_CREATE 的延迟"""
    result = {}
    for k in (10, 50, 100):
        sessions = []
        for _ in range(k):
            ws, sid = await gw_connect(token)
            sessions.append(ws)
        # 排空积压事件
        await asyncio.sleep(0.5)
        for ws in sessions:
            try:
                while True:
                    await asyncio.wait_for(ws.recv(), timeout=0.05)
            except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
                pass

        lat = []

        async def reader(ws):
            t0 = None
            while True:
                try:
                    raw = await asyncio.wait_for(ws.recv(), timeout=10)
                except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
                    return
                delta = time.perf_counter() - t_send[0]
                msg = json.loads(raw)
                if msg.get("op") == 0 and msg.get("t") == "MESSAGE_CREATE":
                    lat.append(delta * 1000)
                    return

        async with httpx.AsyncClient(base_url=BASE, trust_env=False, timeout=15) as http:
            pass  # 复用外部 client 即可
        t_send = [None]

        async def post_and_time():
            t_send[0] = time.perf_counter()
            r = await HTTP.post(f"/api/channels/{channel_id}/messages",
                                json={"content": f"扇出采样 K={k}"})
            r.raise_for_status()

        readers = [asyncio.create_task(reader(ws)) for ws in sessions]
        await post_and_time()
        await asyncio.gather(*readers)
        result[f"fanout_{k}"] = stat_block(lat)
        for ws in sessions:
            await ws.close()
        await asyncio.sleep(0.5)
    return result


async def bench_heartbeat(token):
    """心跳 RTT: 发送 op=1, 等待 op=11"""
    ws, _ = await gw_connect(token)
    rtt = []
    for _ in range(20):
        t0 = time.perf_counter()
        await ws.send(json.dumps({"op": 1, "d": None}))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
            if msg.get("op") == 11:
                break
        rtt.append((time.perf_counter() - t0) * 1000)
        await asyncio.sleep(0.05)
    await ws.close()
    return {"heartbeat_rtt": stat_block(rtt)}


async def bench_voice_relay(alice_token, bob_token, alice_id, guild_id, voice_id):
    """语音中继端到端延迟: Alice 发二进制帧(带时间戳), Bob 接收测延迟。
    语音加入需要活跃网关会话, 先建立两条 Gateway 连接取 session_id。"""
    ws_a, sid_a = await gw_connect(alice_token)
    ws_b, sid_b = await gw_connect(bob_token)
    async with httpx.AsyncClient(base_url=BASE, trust_env=False, timeout=15) as http:
        ra = await http.post("/api/voice/join",
                             headers={"Authorization": f"Bearer {alice_token}"},
                             json={"guild_id": guild_id, "channel_id": voice_id, "session_id": sid_a})
        ra.raise_for_status()
        rb = await http.post("/api/voice/join",
                             headers={"Authorization": f"Bearer {bob_token}"},
                             json={"guild_id": guild_id, "channel_id": voice_id, "session_id": sid_b})
        rb.raise_for_status()
        ta = ra.json()["token"]
        tb = rb.json()["token"]

    wa = await websockets.connect(f"{WS_BASE}/ws/voice", max_size=2**22)
    wb = await websockets.connect(f"{WS_BASE}/ws/voice", max_size=2**22)
    await wa.send(json.dumps({"type": "join", "token": ta, "channelId": voice_id}))
    await wb.send(json.dumps({"type": "join", "token": tb, "channelId": voice_id}))
    ja = json.loads(await wa.recv())
    jb = json.loads(await wb.recv())
    assert ja.get("type") == "joined" and jb.get("type") == "joined", (ja, jb)

    lat = []
    for i in range(100):
        # 路由器会自动为每帧加 8 字节发送者前缀, 客户端只发载荷(时间戳 + 填充)
        payload = struct.pack(">d", time.perf_counter()) + b"\x00" * 52
        await wa.send(payload)
        while True:
            frame = await asyncio.wait_for(wb.recv(), timeout=5)
            if isinstance(frame, bytes) and len(frame) >= 16:
                break
        t0 = struct.unpack(">d", frame[8:16])[0]
        lat.append((time.perf_counter() - t0) * 1000)
        await asyncio.sleep(0.02)  # 50 pps, 近似真实语音包率

    result = {"voice_relay_e2e": stat_block(lat)}
    await wa.close()
    await wb.close()
    await ws_a.close()
    await ws_b.close()
    return result


async def main():
    global BASE, HTTP
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:4001/chorus")
    parser.add_argument("--out", default="perf-results.json")
    parser.add_argument("--seed", type=int, default=300)
    args = parser.parse_args()
    BASE = args.base

    results = {"env": {"base_url": BASE, "time": time.strftime("%Y-%m-%d %H:%M:%S")}}

    async with httpx.AsyncClient(base_url=BASE, trust_env=False, timeout=30) as http_a, \
               httpx.AsyncClient(base_url=BASE, trust_env=False, timeout=30) as http_b:
        alice_token, alice_id, _ = await login(http_a, ALICE)
        bob_token, bob_id, _ = await login(http_b, BOB)
        http_a.headers["Authorization"] = f"Bearer {alice_token}"
        http_b.headers["Authorization"] = f"Bearer {bob_token}"
        results["env"]["alice_id"] = alice_id
        print(f"[setup] alice={alice_id} bob={bob_id}")

        guild_id, text_id, voice_id = await setup_world(http_a, http_b)
        results["env"]["guild_id"] = guild_id
        results["env"]["text_channel_id"] = text_id
        results["env"]["voice_channel_id"] = voice_id
        print(f"[setup] guild={guild_id} text={text_id} voice={voice_id}")

        print(f"[seed] posting {args.seed} messages ...")
        t0 = time.perf_counter()
        await seed_messages(http_a, text_id, args.seed)
        results["seed"] = {"count": args.seed,
                           "elapsed_s": round(time.perf_counter() - t0, 2)}

        HTTP = http_a
        print("[bench] REST latency ...")
        results["rest"] = await bench_rest(http_a, guild_id, text_id)

        print("[bench] gateway heartbeat ...")
        results["gateway"] = await bench_heartbeat(alice_token)

        print("[bench] fan-out 10/50/100 ...")
        results["fanout"] = await bench_gateway(alice_token, text_id)

        print("[bench] voice relay ...")
        results["voice"] = await bench_voice_relay(alice_token, bob_token, alice_id,
                                                   guild_id, voice_id)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
