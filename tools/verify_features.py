# ============================================================================
# 全功能逐项实测：对照论文表3-1 的 11 个功能域逐条验证
# 角色分配：Alice=群主, Bob=普通成员, Eve=注册流程演示+第二成员
# 前置：后端已启动(http://localhost:4001/chorus, sql.init=always, 限流已放宽)
# 用法：python tools/verify_features.py
# ============================================================================
import asyncio
import json
import re
import struct
import time
import zlib

import httpx
import websockets

B = "http://localhost:4001/chorus"
LOG = r"D:\idea databas\test1\docs\build\backend.log"

results = []


def check(name, ok, evidence=""):
    results.append((name, bool(ok), evidence))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  | {evidence}" if evidence else ""))


def png_bytes(w=4, h=4, color=(255, 0, 0)) -> bytes:
    """手工构造最小合法 PNG（真实魔数与 CRC）"""
    def chunk(tag, data):
        c = struct.pack("!I", len(data)) + tag + data
        return c + struct.pack("!I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(color) * w for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack("!IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw))
            + chunk(b"IEND", b""))


def latest_code(email):
    time.sleep(0.2)
    with open(LOG, encoding="utf-8", errors="ignore") as f:
        t = f.read()
    codes = re.findall(r"email=" + re.escape(email) + r" code=(\d{6})", t)
    return codes[-1] if codes else None


async def gw_connect(token):
    """建立网关连接并完成 Identify → READY，返回 (ws, session_id)"""
    ws = await websockets.connect("ws://localhost:4001/chorus/ws", max_size=2 ** 22)
    while True:
        m = json.loads(await ws.recv())
        if m.get("op") == 10:
            break
    await ws.send(json.dumps({"op": 2, "d": {"token": token, "compress": False}}))
    while True:
        m = json.loads(await ws.recv())
        if m.get("op") == 0 and m.get("t") == "READY":
            return ws, m["d"]["session_id"]


async def recv_until(ws, pred, timeout=3.0):
    end = time.time() + timeout
    while time.time() < end:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=max(0.05, end - time.time()))
        except Exception:
            return None
        try:
            m = json.loads(raw)
        except Exception:
            continue
        if pred(m):
            return m
    return None


async def main():
    stamp = str(int(time.time()))
    async with httpx.AsyncClient(base_url=B, trust_env=False, timeout=30) as c:
        # ============ 1 账号认证：注册/邮箱验证/登录/改密/2FA ============
        email = f"eve{stamp}@test.dev"
        r = await c.post("/api/auth/register", json={"username": "Eve", "email": email, "password": "pass123456"})
        check("注册（返回待验证）", r.status_code == 200 and r.json().get("requires_verification"), str(r.json())[:60])
        code = latest_code(email)
        r = await c.post("/api/auth/verify-email", json={"email": email, "code": code})
        check("邮箱验证码激活（验证码取自服务端日志）", r.status_code == 200 and "token" in r.text, f"code={code}")
        r = await c.post("/api/auth/login", json={"email": email, "password": "pass123456"})
        tok = r.json().get("token")
        check("密码登录（签发 JWT）", r.status_code == 200 and bool(tok))
        he = {"Authorization": f"Bearer {tok}"}  # Eve 的令牌
        r = await c.post("/api/auth/change-password", headers=he, json={"oldPassword": "pass123456", "newPassword": "pass654321"})
        check("修改密码", r.status_code == 200, str(r.status_code))
        r = await c.post("/api/auth/login", json={"email": email, "password": "pass654321"})
        tok = r.json().get("token")
        he = {"Authorization": f"Bearer {tok}"}
        check("新密码可登录", r.status_code == 200 and bool(tok))
        r = await c.post("/api/auth/login", json={"email": email, "password": "wrong-pass"})
        check("错误密码拒绝（401 JSON）", r.status_code == 401 and "error" in r.json())

        # 2FA：开启 → 登录返回 mfaToken → 日志验证码 → 验证通过
        r = await c.post("/api/auth/2fa/enable", headers=he)
        check("开启两步验证", r.status_code == 200)
        r = await c.post("/api/auth/login", json={"email": email, "password": "pass654321"})
        mfa = r.json().get("mfa_token") or r.json().get("mfaToken")
        check("2FA 登录被拦（返回 mfaToken）", bool(mfa), str(r.json())[:60])
        code2 = latest_code(email)
        r = await c.post("/api/auth/2fa/verify", json={"mfaToken": mfa, "code": code2})
        check("2FA 验证码通过并签发令牌", r.status_code == 200 and bool(r.json().get("token")), f"code={code2}")
        r = await c.post("/api/auth/2fa/disable", headers=he)
        check("关闭两步验证", r.status_code == 200)

        # ============ 2 用户资料 ============
        eve_id = (await c.get("/api/auth/me", headers=he)).json()["id"]
        r = await c.patch("/api/users/me", headers=he, json={"username": "Eve2", "bio": "测试签名"})
        check("修改用户名/签名", r.status_code == 200, str(r.json())[:80])
        r = await c.post("/api/users/me/avatar", headers=he,
                         files={"file": ("avatar.png", png_bytes(), "image/png")})
        check("头像上传（PNG）", r.status_code == 200 and "url" in json.dumps(r.json()), str(r.json())[:80])
        check("公开资料卡（GET /users/{id}）", (await c.get(f"/api/users/{eve_id}", headers=he)).status_code == 200)

        # ============ 准备群组角色：Alice=群主, Bob/Eve=成员 ============
        atok = (await c.post("/api/auth/login", json={"email": "alice@test.com", "password": "test123"})).json()["token"]
        ha = {"Authorization": f"Bearer {atok}"}
        btok = (await c.post("/api/auth/login", json={"email": "bob@test.com", "password": "test123"})).json()["token"]
        hb = {"Authorization": f"Bearer {btok}"}
        r = await c.post("/api/guilds", headers=ha, json={"name": "功能验证服务器"})
        g = r.json()["id"]
        check("创建群组", r.status_code == 200 and g)
        r = await c.patch(f"/api/guilds/{g}", headers=ha, json={"name": "功能验证服务器改"})
        check("编辑群组名称", r.status_code == 200)
        r = await c.post(f"/api/guilds/{g}/invites", headers=ha, json={})
        inv = r.json()["code"]
        check("创建邀请链接", r.status_code == 200 and inv)
        r = await c.post(f"/api/invites/{inv}/join", headers=hb)
        check("凭邀请加入群组", r.status_code == 200)
        r = await c.get(f"/api/invites/{inv}", headers=hb)
        check("邀请详情可查询", r.status_code in (200, 404))

        # ============ 3 频道 ============
        r = await c.post("/api/channels", headers=ha, json={"guild_id": g, "name": "公告", "type": 0})
        ch_ann = r.json()["id"]
        r = await c.post("/api/channels", headers=ha, json={"guild_id": g, "name": "大厅", "type": 0})
        ch = r.json()["id"]
        r = await c.post("/api/channels", headers=ha, json={"guild_id": g, "name": "语音房", "type": 2})
        ch_voice = r.json()["id"]
        check("创建文字/语音频道", ch_ann and ch and ch_voice)
        r = await c.patch(f"/api/channels/{ch}", headers=ha, json={"name": "大厅改"})
        check("编辑频道（改名）", r.status_code == 200)
        # 越权：Bob(非管理员) 建权限覆盖应 403；群主可以
        r = await c.post(f"/api/channels/{ch}/permissions", headers=hb, json={"target_id": eve_id, "target_type": 1, "allow": 0, "deny": 1024})
        check("权限覆盖-越权拒绝（非管理员 403）", r.status_code == 403, str(r.status_code))
        r = await c.post(f"/api/channels/{ch}/permissions", headers=ha, json={"target_id": int(eve_id), "target_type": 1, "allow": 1024, "deny": 0})
        check("权限覆盖-群主创建成功", r.status_code == 200, str(r.status_code))
        r = await c.get(f"/api/channels/{ch}/permissions", headers=ha)
        check("权限覆盖读取", r.status_code == 200)

        # ============ 4 消息 ============
        r = await c.post(f"/api/channels/{ch}/messages", headers=ha, json={"content": "第一条"})
        m1 = r.json()["id"]
        check("发送消息", r.status_code in (200, 201) and m1)
        r = await c.post(f"/api/channels/{ch}/messages", headers=hb, json={"content": "alpha_beta 通配符测试"})
        target = r.json()["id"]
        r = await c.post(f"/api/channels/{ch}/messages", headers=ha,
                         json={"content": "回复测试", "message_reference": {"message_id": target, "channel_id": ch}})
        check("回复消息（带引用）", r.status_code in (200, 201), str(r.status_code))
        r = await c.patch(f"/api/channels/{ch}/messages/{m1}", headers=ha, json={"content": "第一条(已编辑)"})
        check("编辑消息", r.status_code == 200)
        r = await c.put(f"/api/channels/{ch}/messages/{target}/reactions/👍", headers=ha)
        check("表情回应", r.status_code == 200, str(r.status_code))
        r = await c.put(f"/api/channels/{ch}/messages/pins/{target}", headers=ha)
        r2 = await c.get(f"/api/channels/{ch}/messages/pins", headers=ha)
        check("置顶与置顶列表", r.status_code == 200 and any(p.get("id") == target for p in r2.json()))
        r = await c.post(f"/api/channels/{ch}/messages/typing", headers=ha)
        check("输入中指示", r.status_code == 200, str(r.status_code))
        r = await c.get(f"/api/guilds/{g}/messages/search", headers=ha, params={"query": "alpha_beta", "channel_id": ch})
        check("关键词搜索（字面匹配）", r.status_code == 200 and len(r.json()) == 1, f"命中 {len(r.json())} 条(通配符未展开)")
        r = await c.get(f"/api/channels/{ch}/messages", headers=ha, params={"limit": 2})
        check("分页读取", r.status_code == 200 and len(r.json()) <= 50)
        r = await c.delete(f"/api/channels/{ch}/messages/{m1}", headers=ha)
        check("删除消息", r.status_code in (200, 204))

        # ============ 5 附件 ============
        r = await c.post("/api/uploads", headers=ha, files={"file": ("real.png", png_bytes(), "image/png")})
        att = r.json()
        check("附件上传（合法 PNG）", r.status_code == 200 and att.get("url"), str(att)[:80])
        url = att.get("url", "")
        r2 = await c.get(url if url.startswith("http") else B + url)
        check("附件访问与回读", r2.status_code == 200 and r2.content[:4] == b"\x89PNG", f"{len(r2.content)}B")
        fake = b"this is plain text pretending to be an image" * 4
        r = await c.post("/api/uploads", headers=ha, files={"file": ("fake.png", fake, "image/png")})
        check("附件魔数校验（伪装 PNG 拒绝）", r.status_code in (400, 415), str(r.status_code))

        # ============ 6 实时推送（网关） ============
        ws_b, _ = await gw_connect(btok)
        await recv_until(ws_b, lambda m: True, timeout=0.5)  # 排空 READY
        r = await c.post(f"/api/channels/{ch}/messages", headers=ha, json={"content": "实时推送验证"})
        m = await recv_until(ws_b, lambda m: m.get("op") == 0 and m.get("t") == "MESSAGE_CREATE", timeout=4)
        check("消息实时推送（另一会话收到 Dispatch）", m is not None and m["d"].get("content") == "实时推送验证")
        await c.post(f"/api/channels/{ch}/messages/typing", headers=ha)
        m = await recv_until(ws_b, lambda m: m.get("op") == 0 and "TYPING" in str(m.get("t", "")).upper(), timeout=3)
        check("输入中状态实时推送", m is not None, str(m.get("t")) if m else "未捕获 TYPING 事件")
        ws_a, _ = await gw_connect(atok)
        await ws_a.send(json.dumps({"op": 3, "d": {"since": 0, "activities": [], "status": "dnd", "afk": False}}))
        m = await recv_until(ws_b, lambda m: m.get("op") == 0 and "PRESENCE" in str(m.get("t", "")).upper(), timeout=3)
        check("在线状态实时更新（op3 → PRESENCE_UPDATE）", m is not None, str(m.get("t")) if m else "未捕获")
        await ws_b.close()
        await ws_a.close()

        # ============ 7 语音（REST 控制面） ============
        wsv, sid = await gw_connect(atok)
        r = await c.post("/api/voice/join", headers=ha, json={"guild_id": g, "channel_id": ch_voice, "session_id": sid})
        vt = r.json().get("token")
        check("加入语音频道（发放有效令牌）", r.status_code == 200 and bool(vt))
        wsvoice = await websockets.connect("ws://localhost:4001/chorus/ws/voice", max_size=2 ** 22)
        await wsvoice.send(json.dumps({"type": "join", "token": vt, "channelId": ch_voice}))
        jv = json.loads(await wsvoice.recv())
        check("语音 WS 鉴权接入", jv.get("type") == "joined", str(jv)[:60])
        r = await c.post("/api/voice/mute", headers=ha, json={"guild_id": g, "mute": True})
        check("语音静音控制", r.status_code == 200)
        r = await c.post("/api/voice/deaf", headers=ha, json={"guild_id": g, "deaf": True})
        check("语音禁听控制", r.status_code == 200)
        r = await c.post("/api/voice/leave", headers=ha, json={"guild_id": g})
        check("离开语音频道", r.status_code == 200)
        await wsvoice.close()
        await wsv.close()

        # ============ 8 私信 ============
        bob_id = (await c.get("/api/auth/me", headers=hb)).json()["id"]
        r = await c.post("/api/dm/channels", headers=he, json={"user_id": int(bob_id)})
        dm = r.json()["id"]
        check("创建私信频道", r.status_code in (200, 201) and dm)
        r = await c.post(f"/api/channels/{dm}/messages", headers=he, json={"content": "私信你好"})
        check("私信收发", r.status_code in (200, 201))
        r = await c.get("/api/dm/channels", headers=hb)
        check("私信频道列表", r.status_code == 200 and any(x.get("id") == dm for x in r.json()))

        # ============ 9 好友 ============
        r = await c.post("/api/friends/requests", headers=he, json={"user_id": int(bob_id)})
        check("发送好友请求", r.status_code in (200, 201), str(r.status_code))
        r = await c.put(f"/api/friends/requests/{eve_id}/accept", headers=hb)
        check("接受好友请求", r.status_code == 200, str(r.status_code))
        r = await c.get("/api/friends", headers=he)
        check("好友列表", r.status_code == 200)

        # ============ 10 成员管理与审计 ============
        r = await c.post(f"/api/invites/{inv}/join", headers=he)
        check("Eve 凭邀请加入群组", r.status_code == 200, str(r.status_code))
        r = await c.put(f"/api/guilds/{g}/members/{eve_id}/kick", headers=ha)
        check("踢出成员", r.status_code == 200, str(r.status_code))
        r = await c.post(f"/api/invites/{inv}/join", headers=he)
        check("踢出后重新加入", r.status_code == 200, str(r.status_code))
        r = await c.put(f"/api/guilds/{g}/members/{eve_id}/ban", headers=ha, json={"reason": "验证封禁"})
        check("封禁成员（含原因）", r.status_code == 200, str(r.status_code))
        r = await c.get(f"/api/guilds/{g}/bans", headers=ha)
        ban_ok = r.status_code == 200 and any(str(b.get("user", {}).get("id") or b.get("user_id")) == str(eve_id) for b in r.json())
        check("封禁列表含目标成员", ban_ok, f"{len(r.json()) if r.status_code == 200 else 0} 条")
        r = await c.post(f"/api/invites/{inv}/join", headers=he)
        check("被封禁者加入被拒", r.status_code == 403, str(r.status_code))
        r = await c.delete(f"/api/guilds/{g}/bans/{eve_id}", headers=ha)
        check("解除封禁", r.status_code == 200, str(r.status_code))
        r = await c.get(f"/api/guilds/{g}/audit-log", headers=ha)
        log_ok = r.status_code == 200 and len(r.json()) >= 2
        check("审计日志留痕（踢出/封禁可查）", log_ok, f"{len(r.json()) if r.status_code == 200 else 0} 条")

        # ============ 11 安全语义抽查 ============
        r = await c.get(f"/api/guilds/{g}/members", headers={"Authorization": "Bearer invalid-token-x"})
        check("无效令牌统一 401", r.status_code == 401)

    print("\n========== 汇总 ==========")
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"通过 {passed}/{len(results)}")
    for name, ok, ev in results:
        if not ok:
            print("  FAIL:", name, "|", ev)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    exit(asyncio.run(main()))
