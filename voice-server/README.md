# voice-server — 独立语音转发器（最小 UDP SFU 原型）

独立部署形态的语音数据面：主后端负责控制面（成员进出、静音/禁听），本进程只做 RTP 选择性转发。零第三方依赖，纯 JDK 17。

## 构建

```bat
cd voice-server
mvn -B package
```

## 运行

```bat
java -cp target/classes org.chorus.voice.sfu.VoiceSfuApp [rtpPort] [controlPort]
# 默认: RTP 4003/UDP, 控制 4004/TCP(仅回环) — 与主应用 application.yml 的
# app.voice.server-port / app.voice.ws-port 一致
```

> 注意：主应用内嵌中继也监听 4003/UDP，两者同时本机运行会端口冲突；独立形态二选一。

## 控制面协议（TCP，行协议）

| 命令 | 说明 |
|------|------|
| `JOIN <channelId> <userId>` | 加入频道（幂等，自动离开旧频道） |
| `LEAVE <userId>` | 离开 |
| `STATE <userId> <muted> <deafened>` | 更新静音/禁听 |
| `ADDR <userId> <ip> <port>` | 预登记地址（可选，缺省由首个 RTP 包自动学习） |
| `STATS` | 查询转发统计 |
| `SHUTDOWN` / `PING` | 停机 / 存活探测 |

## 转发语义（与主应用内嵌中继一致）

- 以 RTP 头 SSRC 识别发送者，首个报文自动学习源地址；
- 无回声：不转发给发送者本人；
- muted 发送者的帧丢弃；deafened 接收者跳过；
- RTP 头与载荷透明转发，不重编码。

## 冒烟测试

```python
# 三个 UDP 端点 A/B/C 同频道，C deafened：
#   A 发 5 个 RTP 包 → B 应收到 5 个、C 收到 0 个、载荷逐字节一致
```
