# ============================================================================
# 视频中继协议验证(合成客户端): kind 标签路由 / init 段分桶重放 / 静音只挡音频
# 前置:后端已启动(4001), 限流放宽
# 用法: python tools/test_video_relay.py
# ============================================================================
import asyncio
import json
import struct
import time

import httpx
import websockets

B = "http://localhost:4001/chorus"
WS = "ws://localhost:4001/chorus"
results = []


def check(name, ok, evidence=""):
    results.append((name, bool(ok)))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  | {evidence}" if evidence else ""))


async def login(c, email, pw):
    r = await c.post("/api/auth/login", json={"email": email, "password": pw})
    r.raise_for_status()
    return r.json()["token"]


async def gw_connect(token):
    ws = await websockets.connect(f"{WS}/ws", max_size=2 ** 22)
    while True:
        m = json.loads(await ws.recv())
        if m.get("op") == 10:
            break
    await ws.send(json.dumps({"op": 2, "d": {"token": token, "compress": False}}))
    while True:
        m = json.loads(await ws.recv())
        if m.get("op") == 0 and m.get("t") == "READY":
            return ws, m["d"]["session_id"]


async def main():
    async with httpx.AsyncClient(base_url=B, timeout=30, trust_env=False) as c:
        atok = await login(c, "alice@test.com", "test123")
        btok = await login(c, "bob@test.com", "test123")
        # 找视频测试服务器与语音房
        ha = {"Authorization": f"Bearer {atok}"}
        hb = {"Authorization": f"Bearer {btok}"}
        guilds = (await c.get("/api/guilds", headers=ha)).json()
        g = next(x["id"] for x in guilds if x["name"] == "视频测试服务器")
        chans = (await c.get(f"/api/guilds/{g}/channels", headers=ha)).json()
        vch = next(x["id"] for x in chans if x["name"] == "视频语音房")

        # Alice/Bob 各自网关会话 + join voice
        wsa, sid_a = await gw_connect(atok)
        wsb, sid_b = await gw_connect(btok)
        ta = (await c.post("/api/voice/join", headers=ha, json={"guild_id": g, "channel_id": vch, "session_id": sid_a})).json()["token"]
        tb = (await c.post("/api/voice/join", headers=hb, json={"guild_id": g, "channel_id": vch, "session_id": sid_b})).json()["token"]

        va = await websockets.connect(f"{WS}/ws/voice", max_size=2 ** 22)
        vb = await websockets.connect(f"{WS}/ws/voice", max_size=2 ** 22)
        await va.send(json.dumps({"type": "join", "token": ta, "channelId": vch}))
        await vb.send(json.dumps({"type": "join", "token": tb, "channelId": vch}))
        assert json.loads(await va.recv())["type"] == "joined"
        assert json.loads(await vb.recv())["type"] == "joined"

        alice_id = (await c.get("/api/auth/me", headers=ha)).json()["id"]
        bob_id = (await c.get("/api/auth/me", headers=hb)).json()["id"]

        async def send(ws, kind, chunk):
            if kind is None:
                await ws.send(chunk)  # 旧客户端:无 kind 标签
            else:
                await ws.send(bytes([kind]) + chunk)

        async def recv_binary(ws, timeout=3):
            try:
                while True:
                    m = await asyncio.wait_for(ws.recv(), timeout)
                    if isinstance(m, bytes):
                        return m
            except asyncio.TimeoutError:
                return None

        # 1) 摄像头帧转发: alice → bob, 前缀 8B userId + kind 字节保留
        cam_init = bytes([0x1a, 0x45, 0xdf, 0xa3, 0x99, 0x42, 0x82])  # 伪 WebM EBML 头(仅协议层验证)
        await send(va, 1, cam_init)
        f1 = await recv_binary(vb)
        check("摄像头帧转发(8B前缀+kind字节)", f1 is not None
              and f1[:8] == struct.pack(">Q", int(alice_id)) and f1[8] == 1 and f1[9:] == cam_init,
              "len=%s" % (len(f1) if f1 else 0))

        # 2) 屏幕共享帧 kind=2
        scr = bytes([0x1a, 0x45, 0xdf, 0xa3, 0x84, 0x4a, 0x6b])
        await send(va, 2, scr)
        f2 = await recv_binary(vb)
        check("屏幕共享帧转发(kind=2)", f2 is not None and f2[8] == 2 and f2[9:] == scr)

        # 3) 音频帧 kind=0
        aud = bytes([0x1a, 0x45, 0xdf, 0xa3, 0x81])
        await send(va, 0, aud)
        f3 = await recv_binary(vb)
        check("音频帧转发(kind=0)", f3 is not None and f3[8] == 0 and f3[9:] == aud)

        # 4) 旧客户端兼容:无 kind 字节的裸 WebM(首字节 0x1A)按音频处理,原样转发
        legacy = bytes([0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42])
        await send(va, None, legacy)  # kind=None = 不加标签
        f4 = await recv_binary(vb)
        check("旧客户端兼容(无kind标签按音频原样转发)", f4 is not None and f4[8] == 0x1a and f4[9:] == legacy[1:] or (f4 is not None and f4[8:] == legacy))

        # 5) 静音只挡音频:alice 静音后,音频被丢、摄像头照常转发
        await c.post("/api/voice/mute", headers=ha, json={"guild_id": g, "mute": True})
        time.sleep(0.3)
        await send(va, 0, aud)
        f5 = await recv_binary(vb, timeout=1.0)
        check("静音丢弃音频帧", f5 is None)
        await send(va, 1, cam_init)
        f6 = await recv_binary(vb)
        check("静音不影响摄像头帧", f6 is not None and f6[8] == 1)
        await c.post("/api/voice/mute", headers=ha, json={"guild_id": g, "mute": False})

        # 6) 中途加入重放:Charlie 后加入,应收到 alice 的摄像头 init 段
        ctok = await login(c, "charlie@test.com", "test123")
        wsc, sid_c = await gw_connect(ctok)
        hc = {"Authorization": f"Bearer {ctok}"}
        # charlie 需先加入公会(用 alice 的邀请)
        invs = (await c.get(f"/api/guilds/{g}/invites", headers=ha)).json()
        code = invs[0]["code"]
        await c.post(f"/api/invites/{code}/join", headers=hc)
        tc = (await c.post("/api/voice/join", headers=hc, json={"guild_id": g, "channel_id": vch, "session_id": sid_c})).json()["token"]
        vc = await websockets.connect(f"{WS}/ws/voice", max_size=2 ** 22)
        await vc.send(json.dumps({"type": "join", "token": tc, "channelId": vch}))
        # init 重放按 kind 分桶逐条下发,顺序不定:收集至多 4 帧,在其中找摄像头 init
        got_joined = False
        frames = []
        deadline = time.time() + 1.5
        while time.time() < deadline:
            try:
                m = await asyncio.wait_for(vc.recv(), timeout=0.5)
            except asyncio.TimeoutError:
                continue
            if isinstance(m, str):
                if json.loads(m).get("type") == "joined":
                    got_joined = True
            else:
                frames.append(m)
        cam_replay = next((f for f in frames if len(f) > 9 and f[8] == 1), None)
        check("中途加入收到摄像头init重放", got_joined and cam_replay is not None and cam_replay[9:] == cam_init,
              "frames=%s" % [f[8] if len(f) > 8 else None for f in frames])

        for w in (va, vb, vc, wsa, wsb, wsc):
            try:
                await w.close()
            except Exception:
                pass

    print(f"\n========== 通过 {sum(1 for _, ok in results if ok)}/{len(results)} ==========")
    return 0 if all(ok for _, ok in results) else 1


if __name__ == "__main__":
    exit(asyncio.run(main()))
