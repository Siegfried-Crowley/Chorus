# 🎵 Chorus

<div align="center">

> **社区化实时通信平台** — 学习项目，仅供本地编程学习，拆解实时通信的底层实现逻辑  
> 功能：文字聊天 · 实时语音 · 音视频通话（摄像头/屏幕共享）· 群组权限 · 好友系统 · 私信 · 文件附件

[![JDK](https://img.shields.io/badge/JDK-17+-blue?logo=openjdk)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen?logo=spring)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-61DAFB?logo=react)](https://reactjs.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql)](https://www.postgresql.org/)
[![Tomcat](https://img.shields.io/badge/Tomcat-10.1-F8DC75?logo=apache-tomcat)](https://tomcat.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 🔑 测试账号（直接登录用）

项目启动后，**浏览器打开 `http://localhost:3000`**，用以下任一账号登录：

| 头像 | 用户名 | 邮箱 | 密码 | 角色说明 |
|:----:|:------:|:----:|:----:|:--------:|
| 👩 | **Alice** | `alice@test.com` | `test123` | 已创建了服务器，有好友 |
| 👨 | **Bob** | `bob@test.com` | `test123` | 可加好友，体验聊天 |
| 👨 | **Charlie** | `charlie@test.com` | `test123` | 普通用户 |

> 💡 **小提示**：开两个浏览器窗口分别登录 Alice 和 Bob，就能看到**实时聊天**（新消息、在线状态、语音进出实时推送）效果！

---

## 📖 目录

- [🚀 5 分钟快速启动](#-5-分钟快速启动)
- [✨ 功能展示](#-功能展示)
- [✅ 自动化测试](#-自动化测试)
- [🏗️ 系统架构](#️-系统架构)
- [⚠️ 已知限制](#️-已知限制)
- [📂 项目结构](#-项目结构)
- [⚙️ 详细部署指南](#️-详细部署指南)
- [💻 IntelliJ IDEA 配置](#-intellij-idea-配置)
- [🔗 API 接口一览](#-api-接口一览)
- [🔐 权限系统](#-权限系统)
- [🗄️ 数据库设计](#️-数据库设计)
- [🌐 Gateway 协议](#-gateway-协议)
- [🎤 语音系统架构](#-语音系统架构)
- [🧠 学习路线建议](#-学习路线建议)
- [📋 待办 (Todo)](#-待办-todo)

---

## 🚀 5 分钟快速启动

### 你需要准备什么

> 🚀 **零 Docker / 零数据库**，本方案用 H2 内嵌数据库，开箱即跑。装好下面两项即可：

| 软件 | 下载地址 | 用途 |
|------|---------|------|
| **IntelliJ IDEA**（Ultimate，含 Tomcat 插件）| [官网](https://www.jetbrains.com/idea/) | 启动后端 |
| **Node.js** | [官网下载](https://nodejs.org/) ≥ 18 | 运行前端 |

> 💡 后端默认用 **H2 内嵌数据库**（文件持久化），无需安装 PostgreSQL / Redis / MinIO / Docker。

### 第 1 步：打开项目

用 IntelliJ IDEA 打开 `D:\idea databas\Chorus`（会自动识别 Maven 项目）。

### 第 2 步：用 IDEA + Tomcat 启动后端

后端根项目（`src/`，即 IDEA 部署的 `chorus`）默认 profile 使用 **MySQL**（需本地 MySQL 已启动，数据库名 `chorus`，JPA 自动建表；用户名/密码见 `src/main/resources/application.yml`）。`server/` 树才是 H2 零依赖配置（docker 用）。

1. **确认 Tomcat 已配置**：`Run → Edit Configurations`，新建或使用已有的 **Tomcat Server → Local**
2. **配置 Tomcat**：
   - **HTTP port**：`8080`
   - **VM options**：**留空或不填任何 `-Dspring.profiles.active=*` 和 DB 环境变量**（用默认配置）
   - **Deployment**：添加 `chorus:war exploded`，**Application context** 填 `/chorus`
3. **确保 MySQL 已启动**（本机 3306），数据库 `chorus` 存在
4. 点 ▶ **运行 Tomcat**，控制台出现 **Tomcat started** 且无报错即部署成功

**验证后端**：浏览器打开以下地址，返回 **401** 说明应用已正常启动（Spring Security 拦截了未登录请求，无凭证统一返回 401 JSON）：
```
http://localhost:8080/chorus/api/auth/me
```

> ⚠️ **重要提醒**：README 下方旧章节提到的 `-Dspring.profiles.active=tomcat` profile 已不存在；Redis / MinIO 相关配置已从代码中移除。请**不要**照旧配置，否则 Tomcat 会因找不到 profile / 连不上库而启动失败。就用上面第 2 步的默认方式（根 `src/` 用 MySQL；想零依赖用 `server/` 树的 H2 配置）即可。

### 第 3 步：启动前端

> ⚠️ **重要：以下命令必须在 `client` 目录下执行，不是在项目根目录！**

```bash
# 先切换到 client 目录！
cd client

# 然后执行:
npm install
npm start
```

浏览器自动打开 `http://localhost:3000` 🎉

> 🔍 **端口分工**：`3000` 是前端页面（Vite），`8080` 是后端接口（Tomcat）。登录时前端会通过 `client/.env` 里的地址把请求发到 `http://localhost:8080/chorus`，两者缺一不可。若后端端口不是 8080，请同步修改 `client/.env` 并重启前端。

### 第 4 步：登录体验

> 测试账号见本文最上方 [🔑 测试账号](#-测试账号直接登录用) 表格。

### 第 5 步：开始探索

- **创建服务器**：点击左侧"+"按钮
- **创建频道**：右键服务器 → 创建频道
- **加好友**：输入对方邮箱或用户 ID
- **语音频道**：点击语音频道即可看到谁在线、静音/禁听、说话检测（控制面，不含真实音频）

---

## ✨ 功能展示

### 💬 文字聊天
| 功能 | 说明 |
|------|------|
| 实时消息发送 | WebSocket 推送，毫秒级到达 |
| 消息编辑/删除 | 自己消息 hover 后 ✏️/🗑️ |
| 多频道支持 | 文字频道、语音频道分类管理 |
| 消息历史 | 自动加载最近 50 条，顶部"加载更早"分页 |
| Nonce 去重 | 断网重发不会产生重复消息 |
| 附件 | 输入框附件按钮上传图片/文件，消息内渲染预览 |

### 📩 私信 (DM)
| 功能 | 说明 |
|------|------|
| 好友发起私信 | 好友列表"私信"按钮，自动创建/复用 DM 频道 |
| 侧边栏私信列表 | 左侧"私信"图标进入，实时刷新最后消息预览 |
| DM 实时广播 | 新私信只推给频道双方，越权访问被拒 |

### 🎤 语音频道（真实音频）
> ✅ 已实现**真实音频**：`MediaRecorder(audio/webm;codecs=opus)` → 专用 WebSocket 中继 `/ws/voice`（服务端透明转发，前缀 8 字节发送者 ID）→ `MediaSource/SourceBuffer` 播放。Chrome/Edge/Firefox 通用，延迟约 200-500ms。另有**实时视频能力**（摄像头 + 屏幕共享）：中继帧加 1 字节 kind 标签（0=音频/1=摄像头/2=屏幕），VoiceAudioRouter 按 (发送者, kind) 分桶缓存 init 段供新人重放，静音只挡音频；客户端 VideoPlayback（video + MediaSource/SourceBuffer）按 kind 路由渲染瓦片。另有可独立部署的零依赖 UDP 选择性转发原型 `voice-server/`（RTP 头解析 + 按频道成员表转发，控制面 TCP 行协议，见其 README 与冒烟测试）。
| 功能 | 说明 |
|------|------|
| 真实通话 | 讲话实时送达同频道成员，对方声音经中继播放 |
| 进出广播 | 加入/离开通过 Gateway OP4 实时推给同频道成员 |
| 说话检测 (VAD) | 说话时头像绿框高亮（需麦克风权限） |
| 静音/禁听 | 静音用 `pause()/resume()` 不重启录音器；禁听静音所有远端播放器 |
| 中途加入可解码 | 服务端缓存每个发送者的 WebM init segment，新人加入自动重放 |
| 自动播放策略 | 远端播放首次以静音起播，点"点击启用声音"手势恢复 |

### 👥 服务器管理
| 功能 | 说明 |
|------|------|
| 创建服务器 | 点"+"按钮，输入名称即可 |
| 频道管理 | 创建文字/语音频道，分类分组、重命名/删除、频道级权限覆盖 |
| 角色管理 | 创建/编辑/删除角色、勾选权限位、批量分配成员 |
| 邀请系统 | 生成/删除邀请链接（可预览），按链接加入即进服务器 |
| 成员管理 | 右侧成员列表按在线/离线分组，右键踢出/封禁/解除封禁/改昵称 |
| 服务器设置 | 概览/成员/角色/邀请四 Tab：改名、上传图标 |
| 退出/解散 | 右键服务器退出；owner 解散前需先转让或删除（防误删） |

### 🤝 好友系统
| 功能 | 说明 |
|------|------|
| 发送好友请求 | 输入对方用户 ID |
| 接受/拒绝请求 | 待处理列表一键操作 |
| 删除好友 | 好友列表右键移除 |
| 屏蔽用户 | 不再接收消息 |
| 在线状态 | 登录/登出实时推送给好友，登录时拉好友在线快照 |

### 🔐 权限系统（角色位掩码 + 频道覆盖）
| 层级 | 说明 |
|------|------|
| @everyone 角色 | 服务器所有成员的默认权限 |
| 角色叠加 (Role) | 每个角色有独立权限位，按 Position 排序 |
| 频道覆盖 | 对特定频道覆盖角色的权限 |
| 特定成员覆盖 | 对单独成员设置权限（最高优先级） |
| 管理员 | `ADMINISTRATOR` 权限位 = 全部权限 |
| 服务器所有者 | 不受任何权限限制 |

**权限覆盖 UI**：文字/语音频道右侧齿轮 → 选择角色/成员，左键=允许、右键=拒绝对应权限位（查看、发消息、管理消息、上传、连接语音、说话）。

### 💬 消息增强
| 功能 | 说明 |
|------|------|
| Markdown 渲染 | 加粗/斜体/行内代码/代码块/链接/emoji（轻量 tokenizer 直接产出 React 节点，天然防 XSS） |
| @提及 | `@everyone`（需权限）与 `@用户名` 高亮，点击跳转资料 |
| 表情回应 | 消息 hover 🙂 添加/切换，已回应列表 + 本人高亮 |
| 回复 (Reply) | hover ↩ 引用原消息，输入框带引用条可取消，渲染被引用摘要 |
| 置顶 (Pins) | 频道头部 📌 打开置顶面板，点击跳转对应消息 |
| 正在输入 | 频道底部"xxx 正在输入…"（6 秒超时消失） |
| 未读角标 | 非当前频道有新消息时侧边栏显示数字角标，切回清零 |
| 消息搜索 | 频道头部 🔍 按关键词搜索（可按频道过滤），点击结果跳转 |

### 🛡️ 账号与安全
| 功能 | 说明 |
|------|------|
| 邮箱验证 | 注册后发 6 位验证码（打印到后端日志），未验证不能登录 |
| 两步验证 (2FA) | 设置开启后，登录先输密码再输验证码（验证码打印到日志） |
| 修改密码 | 校验旧密码后更新 |
| 头像/资料页 | 头像上传、改用户名/签名/全局名；点任意头像看公开资料卡、发起私信 |
| 在线状态切换 | 用户栏手动切 online/idle/dnd/offline，实时推给好友 |
| 服务器审计日志 | 踢人/封禁/删服/角色/频道变更全程留痕，管理员可查 |
| 限流保护 | 登录按 IP 10 次/分、发消息按用户 20 次/秒，超限 429；X-Forwarded-For 仅在显式开启 `trust-proxy` 时信任（防伪造 IP 绕过） |
| 附件安全校验 | 上传按类型白名单（图片/音视频/PDF/文本）+ 文件头魔数校验（防伪装类型）+ 25MB 上限 + 文件名净化 |
| 安全响应头 | 全局 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy` |

---

## ✅ 自动化测试

后端（Spring Boot 集成测试，真实 Tomcat + H2 内存库）与前端（Vitest 状态机测试）各有独立测试套件：

| 套件 | 命令 | 覆盖 |
|------|------|------|
| 后端 `src/test` | `mvn test` | 登录/注册、安全配置、消息 CRUD、私信与越权、附件持久化、**邀请/加入/退出/踢人/封禁、角色 CRUD、表情回应/置顶/回复/搜索/输入中、改密/邮箱验证/2FA/登录限流、真实音频转发与静音禁听、越权回归（非成员读消息/数据枚举/权限覆盖自授权/搜索通配符转义/附件魔数/未授权 401）**（45 个用例） |
| 前端 `client/src` | `cd client && npm test` | 网关重连状态机（Identify、幂等 connect、断线 Resume、心跳 ACK）+ **语音帧协议解析/join 帧构造/MIME 探测**（23 个用例） |

> 后端另有 `server/` 树（docker 部署用）也带同一套测试：`mvn -f server/pom.xml test`。
> 测试账号密码哈希已统一修正为 `test123` 对应的 bcrypt 值（原先 `server/` 种子误用了 `password` 的哈希，docker 登录会失败）。
> `server/` 树（H2 文件库）的种子账号由启动时 `SeedUsersRunner` 自动播种——仅当用户表为空时插入，幂等可重复启动。

### 覆盖率与验证工具

- 后端已集成 **JaCoCo**：`mvn test` 后报告输出在 `target/site/jacoco/`，当前行覆盖率 **64.8%**（1869/2885 行）；
- 前端：`cd client && npx vitest run --coverage`，23 个用例全绿（Gateway 状态机 + 音视频帧协议 kind 解析）；
- **视频中继协议验证**：`python tools/test_video_relay.py`（7 项：三 kind 转发、旧帧兼容、静音只挡音频、init 分桶重放）；
- **全功能实测**：后端启动后运行 `python tools/verify_features.py`，对照功能表的 **58 项端到端检查**（注册/2FA/资料/好友/私信/群组/频道/权限覆盖越权/消息全家桶/附件魔数/语音控制/实时推送/踢人封禁/审计日志）逐项打印 PASS/FAIL；
- **性能基准**：`python tools/perf/perf_test.py`（限流需放宽），实测数据：顺序 REST P95 ≤ 16ms、网关广播扇出 10/50/100 会话 = 6.1/9.7/16.6ms、心跳 RTT 0.7ms、语音中继服务器端转发 0.65ms。

---

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         🌐 客户端 (React)                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │ 💬 Chat  │  │ 🎤 Voice │  │ 👥 Friend│  │ ⚙️ Guild 管理    │  │
│  │  Module  │  │  Module  │  │  System  │  │                  │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────────┬─────────┘  │
│       └──────────────┴─────────────┴─────────────────┘            │
│                       ┌─────────────────┐                         │
│                       │  Gateway Client │ WebSocket (wss)         │
│                       └────────┬────────┘                         │
│                       ┌────────┴────────┐                         │
│                       │  REST Client    │ HTTP                    │
│                       └────────┬────────┘                         │
│                       ┌────────┴────────┐                         │
│                       │  Voice Client   │ UDP + WS                │
│                       └─────────────────┘                         │
└──────────────────────────────┬────────────────────────────────────┘
                               │
          ┌────────────────────┼────────────────────────┐
          │                    │                        │
    ┌─────▼──────┐     ┌──────▼──────┐         ┌───────▼───┐
    │  REST API  │     │  Gateway    │         │ Voice SFU │
    │  :8080     │◄───►│  WebSocket  │         │  UDP:4003 │
    │  /api/*    │     │  /ws        │         │           │
    └─────┬──────┘     └──────┬──────┘         └───────┬───┘
          │                   │                        │
    ┌─────▼───────────────────▼────────────────────────▼──────┐
    │                    业务服务层                            │
    │  ┌─────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────┐ │
    │  │  Auth   │ │ Guild  │ │Channel │ │ Message│ │Voice │ │
    │  │ Service │ │Service │ │Service │ │Service │ │Service│ │
    │  └─────────┘ └────────┘ └────────┘ └────────┘ └──────┘ │
    │  ┌─────────┐ ┌────────┐ ┌────────┐ ┌─────────────────┐ │
    │  │  Friend │ │ Perm.  │ │  File  │ │   Notification  │ │
    │  │ Service │ │Service │ │ Service │ │    (Pub/Sub)    │ │
    │  └─────────┘ └────────┘ └────────┘ └─────────────────┘ │
    └─────────────────────────┬───────────────────────────────┘
                              │
    ┌─────────────────────────▼───────────────────────────────┐
    │                      数据层                              │
    │  ┌──────────────────┐    ┌───────────────────────────┐  │
    │  │  PostgreSQL 16   │    │     Redis 7               │  │
    │  │  · 用户/公会      │    │  · Session 缓存 (30s TTL) │  │
    │  │  · 消息(分区表)   │    │  · Gateway 心跳           │  │
    │  │  · 权限/角色      │    │  · Pub/Sub 事件广播       │  │
    │  │  · 好友关系       │    │  · 速率限制               │  │
    │  └──────────────────┘    └───────────────────────────┘  │
    │  ┌────────────────────────────────────────────────────┐  │
    │  │  MinIO (S3 兼容对象存储)                            │  │
    │  │  · 文件附件 / 头像 / 缩略图 / 表情图片              │  │
    │  └────────────────────────────────────────────────────┘  │
    └──────────────────────────────────────────────────────────┘
```

所有服务通过 **Tomcat 同一端口（8080）**对外暴露，REST API 和 WebSocket Gateway 共用端口，简化部署。

---

## 📂 项目结构

```
D:\idea databas\Chorus\              # 项目根目录
│
├── 📁 src/                            # 🖥️ 后端主树 (线上 IDEA 部署用, MySQL)
│   │                                 #    与 server/ 同结构，用 sync.bat 一键同步
│   └── 📁 main/java/org/chorus/  (见下方 server/ 结构)
│
├── 📁 server/                       # 🖥️ 后端 (Spring Boot + Tomcat; docker 用 H2/PostgreSQL)
│   ├── 📁 src/main/java/org/chorus/
│   │   ├── 📄 ChorusApplication.java   # 启动入口 (implements SpringBootServletInitializer)
│   │   ├── 📁 config/                   # 配置类
│   │   │   ├── SecurityConfig.java      # Spring Security (JWT无状态)
│   │   │   ├── JwtAuthFilter.java       # JWT 令牌过滤器
│   │   │   ├── RateLimitFilter.java     # 登录按IP 10次/分、发消息按用户 20次/秒
│   │   │   ├── WebSocketConfig.java     # Gateway + /ws/voice 注册
│   │   │   ├── SecurityHeadersFilter.java # 安全响应头 (nosniff/deny/referrer)
│   │   │   ├── GlobalExceptionHandler.java # 统一异常→JSON (类型化 ApiException)
│   │   │   ├── StaticResourceConfig.java # 静态资源映射 (uploads)
│   │   │   └── SeedUsersRunner.java     # 启动播种演示账号 (表空才插入, 幂等)
│   │   ├── 📁 entity/                   # 📦 数据实体 (19个类)
│   │   │   ├── User.java, Guild.java, GuildMember.java
│   │   │   ├── Channel.java, Message.java (分区表, MessageId)
│   │   │   ├── Role.java, MemberRole.java
│   │   │   ├── ChannelOverwrite.java (权限覆盖)
│   │   │   ├── Relationship.java (好友关系)
│   │   │   ├── VoiceState.java, VoiceAllocation.java, VoiceServer.java
│   │   │   ├── DmChannel.java, DmChannelMember.java
│   │   │   ├── Invite.java (邀请), GuildBan.java (封禁), AuditLogEntry.java (审计日志)
│   │   ├── 📁 repository/               # 🔍 数据访问层 (17个接口)
│   │   ├── 📁 service/                  # ⚙️ 业务逻辑层 (14个服务)
│   │   │   ├── AuthService.java         # 注册/登录/邮箱验证/2FA/改密
│   │   │   ├── UserService.java         # 资料/头像
│   │   │   ├── GuildService.java        # 服务器管理 (成员/角色/频道)
│   │   │   ├── ChannelService.java      # 频道管理
│   │   │   ├── MessageService.java      # 消息CRUD/回应/置顶/搜索
│   │   │   ├── PermissionService.java   # 64位权限引擎 ⭐
│   │   │   ├── FriendService.java       # 好友系统
│   │   │   ├── DmService.java           # 私信
│   │   │   ├── AttachmentService.java   # 文件上传
│   │   │   ├── VoiceService.java        # 语音调度 (联动音频路由)
│   │   │   ├── InviteService.java       # 邀请链接
│   │   │   ├── AuditLogService.java     # 审计日志
│   │   │   ├── RateLimitService.java    # 固定窗口限流
│   │   │   └── CacheService.java        # 进程内缓存 (验证码/mfa_token)
│   │   ├── 📁 controller/               # 🌐 REST API (11个控制器)
│   │   │   ├── AuthController.java      # /api/auth/*
│   │   │   ├── UserController.java      # /api/users/*
│   │   │   ├── GuildController.java     # /api/guilds/*
│   │   │   ├── GuildMessageController.java  # /api/guilds/:id/messages/search
│   │   │   ├── ChannelController.java   # /api/channels/*
│   │   │   ├── MessageController.java   # /api/channels/:id/messages/*
│   │   │   ├── InviteController.java    # /api/invites/*
│   │   │   ├── FriendController.java    # /api/friends/*
│   │   │   ├── DmController.java        # /api/dm/*
│   │   │   ├── AttachmentController.java# /api/uploads
│   │   │   └── VoiceController.java     # /api/voice/*
│   │   ├── 📁 gateway/                  # 🔌 WebSocket Gateway
│   │   │   └── GatewayWebSocketHandler.java  # 协议实现 (Opcode 1-12) + 公会广播
│   │   ├── 📁 voice/                    # 🔊 语音
│   │   │   ├── VoiceAudioHandler.java   # /ws/voice 音频中继 (控制帧+二进制帧)
│   │   │   ├── VoiceAudioRouter.java    # 频道转发表 (静音/禁听/initChunk缓存)
│   │   │   └── VoiceSfuServer.java      # 内嵌 UDP SFU (演进保留)
│   │   ├── 📁 dto/                      # 数据传输对象
│   │   └── 📁 util/
│   │       ├── JwtUtil.java             # JWT 令牌工具
│   │       └── SnowflakeGenerator.java  # Twitter Snowflake ID
│   │
│   ├── 📁 src/main/resources/
│   │   ├── 📄 application.yml           # 配置 (3个Profile: default/postgres/prod)
│   │   ├── 📄 schema.sql                # 建表脚本 (docker postgres 初始化用)
│   │   ├── 📄 data.sql                  # 演示用户种子 (H2 文件库实际由 SeedUsersRunner 播种)
│   │   └── 📄 seed.sql                  # PostgreSQL 容器初始化种子
│   │
│   ├── 📁 src/main/webapp/WEB-INF/
│   │   └── 📄 web.xml                   # Tomcat 部署描述符
│   │
│   ├── 📄 Dockerfile                    # 构建WAR → Tomcat10镜像
│   ├── 📄 deploy.bat                    # 一键部署到本地Tomcat
│   └── 📄 setenv.bat                    # Tomcat环境变量模板
│
├── 📁 client/                           # 🎨 前端 (React 18 + TypeScript)
│   ├── 📁 public/
│   │   └── index.html
│   ├── 📁 src/
│   │   ├── 📁 components/               # UI 组件 (21个)
│   │   │   ├── App.tsx                  # 根组件 (Gateway连接管理 + 事件路由)
│   │   │   ├── MainLayout.tsx           # 主布局 (4列: guilds/channels/main/members)
│   │   │   ├── 📁 auth/
│   │   │   │   └── LoginPage.tsx        # 登录+注册+邮箱验证+2FA 流程
│   │   │   ├── 📁 guild/                # 服务器管理
│   │   │   │   ├── GuildSidebar.tsx     # 服务器图标列表
│   │   │   │   ├── ChannelSidebar.tsx   # 频道树 (分类分组/未读角标)
│   │   │   │   ├── DmSidebar.tsx        # 私信列表
│   │   │   │   ├── MemberList.tsx       # 成员列表 (在线/离线分组)
│   │   │   │   ├── CreateGuildModal.tsx # 创建服务器
│   │   │   │   ├── CreateChannelModal.tsx   # 创建频道 (类型/分类)
│   │   │   │   ├── JoinGuildModal.tsx   # 通过邀请码加入
│   │   │   │   ├── GuildSettingsModal.tsx   # 服务器设置 (概览/成员/角色/邀请)
│   │   │   │   └── ChannelPermissionsModal.tsx # 频道权限覆盖
│   │   │   ├── 📁 chat/                 # 聊天
│   │   │   │   ├── ChatArea.tsx         # 聊天主面板
│   │   │   │   ├── MessageList.tsx      # 消息 (markdown/回应/回复/输入中)
│   │   │   │   ├── MessageInput.tsx     # 输入框 (回复条/附件)
│   │   │   │   ├── PinsPanel.tsx        # 置顶面板
│   │   │   │   └── SearchModal.tsx      # 消息搜索
│   │   │   ├── 📁 voice/
│   │   │   │   └── VoicePanel.tsx       # 语音面板 (真实音频 + 控制)
│   │   │   ├── 📁 friends/
│   │   │   │   └── FriendsPage.tsx      # 好友列表+请求管理
│   │   │   └── 📁 common/
│   │   │       ├── UserPanel.tsx        # 底部用户栏 (状态切换/设置)
│   │   │       ├── ProfileModal.tsx     # 个人资料编辑 (头像/密码/2FA)
│   │   │       └── UserProfileModal.tsx # 他人资料卡
│   │   ├── 📁 gateway/
│   │   │   └── GatewayClient.ts         # WebSocket 客户端 (心跳/重连/事件)
│   │   ├── 📁 voice/
│   │   │   ├── VoiceClient.ts           # 录音/中继/播放管理
│   │   │   ├── RemotePlayback.ts        # MediaSource 播放器 (按远端用户)
│   │   │   ├── voiceProtocol.ts         # 帧协议 (纯函数, 可单测)
│   │   │   └── voiceProtocol.test.ts    # vitest 用例
│   │   ├── 📁 store/
│   │   │   └── index.ts                # Zustand 状态管理 (归一化)
│   │   ├── 📁 utils/
│   │   │   └── api.ts                  # REST API 封装 (axios)
│   │   └── 📁 styles/
│   │       └── chorus.css             # 暗色主题完整样式
│   └── 📄 .env                         # 连接地址配置 (VITE_*)
│
├── 📁 .github/workflows/
│   └── 📄 ci.yml                       # 🤖 CI: 后端 src/server 双树 mvn test + 前端 tsc/vitest
├── 📁 voice-server/                    # 🔊 独立语音转发器 (零依赖 UDP SFU 原型, RTP 4003/控制 4004)
├── 📁 tools/
│   ├── 📄 verify_features.py           # ✅ 全功能逐项实测脚本 (58 项检查, 需后端已启动)
│   └── 📁 perf/
│       └── perf_test.py                # 📊 性能基准 (REST延迟/网关扇出/心跳RTT/语音中继延迟)
├── 📁 docs/                            # 📚 开题报告 + 毕业论文 (md 源稿 + docx 成品 + EA 风格 UML 图 + 界面截图)
├── 📄 sync.bat                         # 🔁 一键同步 src/main/java + src/test → server/
├── 📄 docker-compose.yml               # 🐳 一键编排所有服务
├── 📄 pom.xml                          # Maven 依赖 (WAR打包)
└── 📄 README.md                        # 📖 本文档
```

---

## ⚙️ 详细部署指南

### 🐳 方案一：Docker 一键部署（推荐，最简单）

> 适合：**只想体验功能、不想装任何开发环境**

```bash
# 1. 确保 Docker Desktop 已安装并运行

# 2. 在项目根目录执行（第一次会下载镜像，约 2-5 分钟）
docker compose up -d

# 3. 查看所有服务是否正常启动
docker compose ps
# 应该看到 postgres/redis/minio/server 全部 running

# 4. 后端已经跑在 Tomcat 上了！
#    验证: 浏览器打开 http://localhost:4001/chorus/api/auth/me
#    应该在登录前返回 401，说明API正常

# 5. 启动前端
cd client
npm install
npm start
```

**停止服务：**
```bash
docker compose down          # 停止所有
docker compose down -v       # 停止并删除数据（重新开始）
```

---

### 🪟 方案二：外置 Tomcat 部署（最真实）

> 适合：**想模拟生产环境、学习完整的 Java Web 部署流程**

#### 安装前置软件

| 软件 | 版本 | 下载 | 安装说明 |
|------|------|------|---------|
| Tomcat | 10.1+ | [apache-tomcat-10.1.x.exe](https://tomcat.apache.org/download-10.cgi) | 安装时设置端口 8080 |
| PostgreSQL | 16 | [官网下载](https://www.postgresql.org/download/) | 安装时密码设 `chorus_dev_2026` |
| Redis | 7+ | [Redis for Windows](https://github.com/microsoftarchive/redis/releases) | 默认端口 6379 |
| MinIO | latest | [MinIO Server](https://min.io/download#/windows) | 命令行启动 |
| JDK | 17+ | [Adoptium Temurin 17](https://adoptium.net/temurin/releases/) | 配置 JAVA_HOME |
| Node.js | 18+ | [官网下载](https://nodejs.org/) | 一路下一步 |

#### 配置 Tomcat

```bash
# 1. 把环境变量文件复制到 Tomcat
copy server\setenv.bat "C:\Program Files\Apache Software Foundation\Tomcat 10.0\bin\"

# 2. 或者手动编辑 %TOMCAT_HOME%\bin\setenv.bat，内容如下：
@echo off
set JAVA_OPTS=%JAVA_OPTS% -Dspring.profiles.active=postgres
set JAVA_OPTS=%JAVA_OPTS% -DDB_URL=jdbc:postgresql://localhost:5432/chorus
set JAVA_OPTS=%JAVA_OPTS% -DDB_USER=chorus
set JAVA_OPTS=%JAVA_OPTS% -DDB_PASSWORD=chorus_dev_2026
```

#### 构建和部署

```bash
# 1. 确保 PostgreSQL、Redis、MinIO 都已启动
#    最简单方式：用 Docker 启动中间件
docker compose up -d postgres redis minio

# 2. 构建 WAR 包
cd server
mvn clean package -DskipTests

# 3. 部署到 Tomcat
#    把 target/chorus-1.0.0.war 复制到
#    C:\Program Files\Apache Software Foundation\Tomcat 10.0\webapps\chorus.war

# 4. 启动 Tomcat
net start Tomcat10
# 或双击: C:\...\Tomcat 10.0\bin\startup.bat

# 5. 验证部署
#    浏览器打开 http://localhost:8080/chorus/api/auth/me
#    应该在未登录时返回 401

# 6. 启动前端
cd client
npm install
npm start
```

> 也可以直接用 `server\deploy.bat "你的Tomcat安装路径"` 一键完成第2-3步。

---

### 💨 方案三：嵌入式 Tomcat 快速开发

> 适合：**开发者想快速调试、断点跟踪代码**

```bash
# 1. 启动中间件
docker compose up -d postgres redis minio

# 2. 直接运行 Spring Boot（自带内嵌 Tomcat）
cd server
mvn spring-boot:run

# 3. 验证
#    http://localhost:4001/chorus/api/auth/me

# 4. 启动前端
cd client && npm install && npm start
```

---

## 💻 IntelliJ IDEA 配置

### 第一次打开项目

```bash
File → Open → 选择 D:\idea databas\Chorus 目录
# IDEA 会自动识别为 Maven 项目，下载依赖
```

### 配置 Tomcat 运行按钮（打断点调试用）

```
1. Run → Edit Configurations
2. 点 "+" → Tomcat Server → Local
3. 配置：
   ┌─────────────────────────────────────────┐
   │ Server 标签页                            │
   │   Application server: 选你的 Tomcat      │
   │   HTTP port: 8080                        │
   │   VM options: (留空,默认 profile;勿用 tomcat) │
   │                                  │
   │ Deployment 标签页                       │
   │   + → Artifact → chorus:war exploded    │
   │   Application context: /chorus                 │
   │                                  │
   │ Server 标签页 (下方)                  │
   │   On frame deactivation: Update resources       │
   └─────────────────────────────────────────┘
4. Before launch: Build → Apply
5. 点 ▶️ 运行 / 🐞 调试
```

### 启动前端

```bash
# 在 IntelliJ Terminal 中
cd client && npm start
```

### 常用 Maven 命令

| 命令 | 说明 |
|------|------|
| `mvn clean package -DskipTests` | 打包 WAR |
| `mvn spring-boot:run` | 嵌入式启动 |
| `mvn test` | 运行测试 |
| `mvn dependency:resolve` | 下载依赖 |

---

## 🔗 API 接口一览

### 认证接口 `/api/auth`

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| POST | `/api/auth/register` | 注册（返回 `verified=false`，需先邮箱验证） | `{username, email, password}` |
| POST | `/api/auth/verify-email` | 邮箱验证（验证码打印到后端日志） | `{code}` |
| POST | `/api/auth/login` | 登录（未验证→403；开 2FA→返回 `requires_2fa`+`mfa_token`） | `{email, password}` |
| POST | `/api/auth/2fa/verify` | 2FA 第二步，换取真 token | `{mfa_token, code}` |
| POST | `/api/auth/2fa/enable` | 开启两步验证（打印验证码） | — |
| POST | `/api/auth/2fa/disable` | 关闭两步验证 | — |
| POST | `/api/auth/change-password` | 修改密码 | `{oldPassword, newPassword}` |
| GET | `/api/auth/me` | 当前用户 | Header: `Authorization: Bearer <token>` |

### 用户接口 `/api/users`

| 方法 | 路径 | 说明 |
|------|------|------|
| PATCH | `/api/users/me` | 修改用户名/签名/全局名 |
| POST | `/api/users/me/avatar` | 上传头像（multipart） |
| GET | `/api/users/{id}` | 公开资料 |

### 服务器接口 `/api/guilds`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/guilds` | 我的服务器列表 |
| POST | `/api/guilds` | 创建服务器 |
| GET | `/api/guilds/{id}` | 服务器详情 |
| DELETE | `/api/guilds/{id}` | 解散服务器（仅 owner） |
| GET | `/api/guilds/{id}/channels` | 频道列表 |
| GET | `/api/guilds/{id}/members` | 成员列表 |
| GET | `/api/guilds/{id}/roles` | 角色列表 |
| POST | `/api/guilds/{id}/roles` | 创建角色 |
| PATCH | `/api/guilds/{id}/roles/{roleId}` | 编辑角色（名称/颜色/权限位/排序） |
| DELETE | `/api/guilds/{id}/roles/{roleId}` | 删除角色（@everyone 不可删） |
| PUT | `/api/guilds/{id}/members/{userId}/roles` | 批量分配角色 |
| PATCH | `/api/guilds/{id}/members/me` | 改自己的昵称 |
| PATCH | `/api/guilds/{id}/members/{userId}` | 改他人昵称 |
| PUT | `/api/guilds/{id}/members/{userId}/kick` | 踢出成员 |
| PUT | `/api/guilds/{id}/members/{userId}/ban` | 封禁成员 |
| DELETE | `/api/guilds/{id}/bans/{userId}` | 解除封禁 |
| GET | `/api/guilds/{id}/bans` | 封禁列表 |
| DELETE | `/api/guilds/{id}/members/me` | 退出服务器（owner 需先转让/删除） |
| GET | `/api/guilds/{id}/invites` | 邀请列表 |
| POST | `/api/guilds/{id}/invites` | 创建邀请 |
| GET | `/api/guilds/{id}/messages/search` | 消息搜索（`?query=&channel_id=`） |
| GET | `/api/guilds/{id}/audit-log` | 审计日志 |

### 邀请接口 `/api/invites`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/invites/{code}` | 加入前预览（guild/channel 摘要） |
| POST | `/api/invites/{code}/join` | 通过邀请加入服务器 |
| DELETE | `/api/invites/{code}` | 删除邀请（创建者或 MANAGE_GUILD） |

### 频道接口 `/api/channels`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/channels` | 创建频道（`{type, parent_id}` 支持分类） |
| GET | `/api/channels/{id}` | 频道详情 |
| PATCH | `/api/channels/{id}` | 修改频道（含 `position`/`parent_id` 排序） |
| DELETE | `/api/channels/{id}` | 删除频道 |
| POST | `/api/channels/{id}/typing` | 上报"正在输入" |

### 消息接口 `/api/channels/{id}/messages`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `.../messages?limit=50&before={id}` | 消息历史 |
| POST | `.../messages` | 发送消息（支持 `message_reference` 回复） |
| PATCH | `.../messages/{id}` | 编辑消息 |
| DELETE | `.../messages/{id}` | 删除消息 |
| PUT | `.../messages/{id}/reactions/{emoji}` | 添加表情回应 |
| DELETE | `.../messages/{id}/reactions/{emoji}` | 移除表情回应 |
| GET | `.../pins` | 置顶列表 |
| POST | `.../pins/{messageId}` | 置顶消息 |
| DELETE | `.../pins/{messageId}` | 取消置顶 |

### 好友接口 `/api/friends`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/friends` | 好友列表 |
| POST | `/api/friends/requests` | 发送好友请求 |
| PUT | `/api/friends/requests/{id}/accept` | 接受请求 |
| PUT | `/api/friends/requests/{id}/reject` | 拒绝请求 |
| DELETE | `/api/friends/{id}` | 删除好友 |
| PUT | `/api/friends/blocks` | 屏蔽用户 |

### 语音接口 `/api/voice`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/voice/join` | 加入语音频道（返回分配 token，供 `/ws/voice` join 帧鉴权） |
| POST | `/api/voice/leave` | 离开语音频道 |
| POST | `/api/voice/mute` | 切换静音 |
| POST | `/api/voice/deaf` | 切换禁听 |
| WS | `/ws/voice` | 真实音频中继：文本控制帧（join/leave/ping/pong）+ 二进制 WebM Opus 音频帧 |

---

## 🔐 权限系统

本项目完整实现了业界通行的**角色位掩码 + 频道覆盖**权限模型。

### 权限位一览 (64-bit)

```
Bit   权限                值
──────────────────────────────────────
0     CREATE_INSTANT_INVITE   1
1     KICK_MEMBERS            2
2     BAN_MEMBERS             4
3     ADMINISTRATOR           8          ← 覆盖所有
4     MANAGE_CHANNELS         16
5     MANAGE_GUILD            32
6     ADD_REACTIONS           64
7     VIEW_AUDIT_LOG          128
8     PRIORITY_SPEAKER        256
9     STREAM                  512
10    VIEW_CHANNEL            1024       ← 看频道
11    SEND_MESSAGES           2048       ← 发消息
12    SEND_TTS_MESSAGES       4096
13    MANAGE_MESSAGES         8192
14    EMBED_LINKS             16384
15    ATTACH_FILES            32768
16    READ_MESSAGE_HISTORY    65536
17    MENTION_EVERYONE        131072
18    USE_EXTERNAL_EMOJI      262144
20    CONNECT                 1048576    ← 进语音
21    SPEAK                   2097152    ← 语音说话
22    MUTE_MEMBERS            4194304
23    DEAFEN_MEMBERS          8388608
24    MOVE_MEMBERS            16777216
25    USE_VAD                 33554432
26    CHANGE_NICKNAME         67108864
27    MANAGE_NICKNAMES        134217728
28    MANAGE_ROLES            268435456
...（共 40+ 个权限位）
```

### 权限计算流程

```
用户权限 = @everyone基础权限 + 所有角色权限(Position升序)
         + 频道@everyone覆盖 + 频道角色覆盖(Position升序)
         + 频道成员覆盖(最高优先级)

特殊情况:
  服务器所有者 → 全部权限
  有 ADMIN → 全部权限
```

> 实现代码：`server/.../service/PermissionService.java`

---

## 🗄️ 数据库设计

### ER 概览

```
users ──┬── guild_members ──┬── guilds
        │                   │
        │                   ├── roles ──┬── member_roles
        │                   │           │
        │                   └── channels ──┬── messages (分区表 ×8)
        │                                  │
        │                                  └── channel_overwrites
        │
        ├── relationships (好友)
        │
        ├── dm_channels ──┬── dm_channel_members
        │
        ├── voice_states
        │
        └── attachments
```

### 核心表清单

| 表名 | 行数预测 | 说明 |
|------|---------|------|
| `users` | 用户数 | 用户名、邮箱、头像、flags |
| `guilds` | 服务器数 | 名称、Owner、设置 |
| `guild_members` | 用户×服务器 | 昵称、角色、状态 |
| `roles` | 服务器数×10 | 名称、颜色、权限位 |
| `channels` | 频道数 | 类型(text/voice/category)、配置 |
| `messages` | **巨大** | **按channel_id哈希分区(8张)** |
| `channel_overwrites` | 频道数×5 | 允许/拒绝的权限覆盖 |
| `relationships` | 用户数×好友数 | 类型(friend/blocked/request) |
| `voice_states` | 语音在线用户 | session_id、mute/deaf |
| `attachments` | 文件数 | URL、尺寸、类型 |

### 消息分区表

```sql
-- 消息表按 channel_id 哈希分成 8 个物理分区
-- 查询 WHERE channel_id = ? → 只扫描 1 个分区
CREATE TABLE messages (
    id BIGINT, channel_id BIGINT, ...
    PRIMARY KEY (channel_id, id)  -- 分区键在前！
) PARTITION BY HASH (channel_id);

-- 8 个分区: messages_0 ~ messages_7
```

> 完整的建表 SQL：`server/src/main/resources/schema.sql`

---

## 🌐 Gateway 协议

Gateway 是实时通信的核心，实现了 **Gateway 协议 v9** 的语义。

### 连接生命周期

```
客户端                         服务端
  │                              │
  │──── ws://host:port/ws ──────►│  建立 WebSocket
  │                              │
  │◄──── OP 10 Hello ───────────│  服务端发送心跳间隔
  │                              │
  │──── OP 1 Heartbeat ────────►│  ◄── 每 41.25s
  │◄──── OP 11 Heartbeat ACK ───│  ◄── 服务端确认
  │                              │
  │──── OP 2 Identify ─────────►│  ◄── 发送 Token 认证
  │                              │
  │◄──── OP 0 Ready ────────────│  认证通过!
  │      │ session_id            │
  │      │ guilds               │
  │      │ relationships        │
  │                              │
  │◄──── OP 0 MESSAGE_CREATE ───│  实时消息推送
  │◄──── OP 0 PRESENCE_UPDATE ──│  在线状态
  │◄──── OP 0 VOICE_STATE_UPDATE│  语音状态
```

### Opcode 列表

| Op | 方向 | 名称 | 说明 |
|----|------|------|------|
| 0 | ◀服务端 | Dispatch | 事件推送（MESSAGE_CREATE 等） |
| 1 | ▶客户端 | Heartbeat | 心跳 |
| 2 | ▶客户端 | Identify | 认证 |
| 3 | ▶客户端 | Presence Update | 在线状态更新 |
| 4 | ▶客户端 | Voice State Update | 语音状态 |
| 6 | ▶客户端 | Resume | 断线重连 |
| 7 | ◀服务端 | Reconnect | 服务端要求重连 |
| 8 | ▶客户端 | Request Guild Members | 请求成员 |
| 9 | ◀服务端 | Invalid Session | 会话无效 |
| 10 | ◀服务端 | Hello | 欢迎帧 + 心跳间隔 |
| 11 | ◀服务端 | Heartbeat ACK | 心跳确认 |

### 断线重连 (Resume)

```
断线 → 3次心跳未回复 → 客户端关闭连接
  │
  ├─ 30秒内重连?
  │   ├─ YES → OP 6 Resume(token, session_id, seq)
  │   │        → 服务端从 Redis 读取会话
  │   │        → 从断线位置继续推送事件
  │   │
  │   └─ NO  → 会话过期 → OP 9 Invalid Session
  │             → 重新 OP 2 Identify
```

> 实现代码：`client/src/gateway/GatewayClient.ts`
> 服务端：`server/.../gateway/GatewayWebSocketHandler.java`

---

## 🎤 语音系统架构

> 📌 **已启用：WebSocket Opus 中继**（跨浏览器通用，无需开放 UDP 端口）：
> 前端 `MediaRecorder(audio/webm;codecs=opus)` 每 50ms 产出一片音频 → 直接经 `/ws/voice` 二进制帧发给后端 → `VoiceAudioRouter` 按频道**透明转发**给其他成员（帧前缀 8 字节发送者 ID，跳过发送者本人/禁听者，静音发送者不发，缓存 init segment 供中途加入重放）→ 接收端 `RemotePlayback`（MediaSource/SourceBuffer）播放。
> 下面对应的 **UDP/SFU 架构是当初的愿景**，`VoiceSfuServer` 保留骨架但**未启用**。

```
🎤 麦克风
    │
    ├── Opus 编码 (48kHz / 64kbps / 20ms帧)
    │
    ├── WebRTC PeerConnection
    │
    ├── UDP (端口 4003) ─────►  Voice SFU Server
    │                              │
    │                    ┌─────────┼─────────┐
    │                    │         │         │
    │             选择性转发     选择性转发   选择性转发
    │                    │         │         │
    ▼                    ▼         ▼         ▼
  🎧 用户A            用户B     用户C      用户D
  (听到B+C)         (听到A+C)  (听到A+B)  (听到A+B)
```

### 关键技术参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 采样率 | 48kHz | 语音最优 |
| 帧大小 | 960 samples (20ms) | 每包 |
| 编码码率 | 64kbps | 可调 8-512 |
| 丢包保护 | 15% FEC | 前向纠错 |
| 加密 | xsalsa20_poly1305 | UDP 负载加密 |

### SFU (Selective Forwarding Unit)

本项目使用 **SFU 架构**（非 MCU），原因：

- **MCU**：服务端混音后发一个流 → 服务端 CPU 高、无法单独调音量
- **SFU**：服务端只转发，每个客户端收 N 个独立流 → 低延迟、可单独调音量

> 实际音频链路（已启用）：后端 `src/main/java/org/chorus/voice/VoiceAudioHandler.java` + `VoiceAudioRouter.java`（`/ws/voice`）；前端 `client/src/voice/VoiceClient.ts` + `RemotePlayback.ts` + `voiceProtocol.ts`。  
> UDP SFU 骨架（未启用）：`server/.../voice/VoiceSfuServer.java`

---

## 🧠 学习路线建议

如果你是**初学者**，想通过这个项目理解实时通信平台的底层原理，建议按以下顺序阅读代码：

### 第一阶段：理解业务流程（30分钟）

```
前端 → 后端 → 数据库 的完整请求链
```

推荐追踪一个"**发送消息**"的完整流程：

```
1. 📄 客户端: MessageInput.tsx
   → 用户输入文字 → 回车

2. 📄 前端: api.ts
   → POST /api/channels/:id/messages

3. 📄 后端: MessageController.java
   → 接收 HTTP 请求

4. 📄 后端: MessageService.java
   → 业务逻辑 → 插入数据库

5. 📄 后端: GatewayWebSocketHandler.java
   → 广播 MESSAGE_CREATE 事件

6. 📄 前端: GatewayClient.ts
   → 收到 WS 推送 → 更新界面
```

### 第二阶段：理解实时系统（30分钟）

追踪"**加入语音频道**"流程：

```
1. GatewayClient.ts          → OP 4 Voice State Update
2. GatewayWebSocketHandler   → 调用 VoiceService
3. VoiceService              → 分配 SFU 服务器
4. VoiceSfuServer            → UDP 转发建立
5. VoiceClient.ts            → WebRTC 连接
```

### 第三阶段：理解最难的部分（1小时）

**权限系统 和 Gateway 协议** 是此类平台最核心的两个设计：

- 📄 `PermissionService.java` — 权限计算引擎（核心算法）
- 📄 `GatewayWebSocketHandler.java` — 实时协议处理器
- 📄 `GatewayClient.ts` — 客户端心跳/重连机制

### 第四阶段：挑战（任选）

- 添加消息的 **@提及** 解析和通知
- 添加 **消息中富文本渲染**（Markdown）
- 添加 **Emoji 反应** 功能
- 实现 **Stage 频道**（听众/发言者模式）
- 实现 **真实音频传输**（WebSocket Opus 中继，WebCodecs）
- 给权限覆盖加**可视化位掩码编辑**（目前是简化后的 6 个常用权限位）

---

## 🔧 配置文件速查

### 后端配置 `server/src/main/resources/application.yml`

```yaml
# 三套配置 Profile:
# default  → 嵌入式 Tomcat (mvn spring-boot:run)
# tomcat   → 外置 Tomcat (catalina.sh run)
# prod     → 生产模式 (关闭 debug 日志)

# 关键配置项:
server.port: 4001              # 嵌入式端口
server.servlet.context-path: /chorus  # 上下文路径

spring.datasource.url:          # 数据库连接
spring.data.redis.url:          # Redis 连接
app.jwt.secret:                 # JWT 密钥
app.minio.endpoint:             # MinIO 地址
```

### 前端配置 `client/.env`

```env
# 前端同源，由 Vite 代理转发到后端（见 vite.config.ts proxy）
VITE_API_URL=http://localhost:8080/chorus        # REST API 目标
VITE_GATEWAY_URL=ws://localhost:3000/ws           # Gateway，走 Vite /ws 代理
VITE_VOICE_URL=ws://localhost:3000/ws/voice       # 音频中继，走 Vite /ws 代理（废弃旧 4004）
```

---

## 📊 端口规划

| 端口 | 服务 | 协议 | 说明 |
|------|------|------|------|
| **8080** | Tomcat | HTTP/WS | REST API + Gateway WebSocket + `/ws/voice` 音频中继 |
| **4003** | Voice SFU | UDP | 音频数据转发（骨架保留，未启用） |
| ~~4004~~ | ~~Voice WS~~ | ~~WebSocket~~ | ~~预留~~ **已废弃**：音频改走 8080 的 `/ws/voice` |
| 5432 | PostgreSQL | TCP | 数据库 |
| 6379 | Redis | TCP | 缓存 + Pub/Sub |
| 9000 | MinIO | HTTP | S3 文件存储 API |
| 9001 | MinIO Console | HTTP | 管理控制台 |
| 3000 | React Dev Server | HTTP | 前端开发（/api、/ws 代理到 8080） |

---

## 🚨 常见问题

### 1. `docker-compose` 命令找不到

```bash
# 报错: "无法将"docker-compose"项识别为 cmdlet"
```

**原因**：新版 Docker Desktop 使用 `docker compose`（空格），不再支持 `docker-compose`（横线）。

**解决**：

```bash
# ✅ 新版 Docker: 用空格
docker compose up -d

# ❌ 旧版写法已废弃: docker-compose up -d
```

如果 `docker compose` 也不行，说明 Docker Desktop 没装好 → 去 [docker.com](https://www.docker.com/products/docker-desktop/) 下载安装。

---

### 2. `npm start` 报找不到 package.json

```bash
# 报错: "Could not read package.json"
```

**原因**：在项目根目录直接运行了 `npm start`，但前端代码在 `client` 子目录里。

**解决**：

```bash
# ❌ 错误: 在 D:\idea databas\Chorus\ 下运行
npm start

# ✅ 正确: 先切换到 client 目录
cd client
npm start
```

---

### 3. Docker 启动后访问不到后端

```bash
# 浏览器打开 http://localhost:4001/chorus/api/auth/me
# 返回 404 或连接失败
```

**原因**：Docker 部署用的是 Tomcat 端口映射。

**解决**：

```bash
# 确认所有容器都正常运行
docker compose ps

# 应该看到 4 个服务都是 "Up" 状态
# postgres, redis, minio, server

# Tomcat 的端口映射到 4001，访问地址是:
# http://localhost:4001/chorus/api/auth/me

# 如果 4001 连不上，试试直接访问 Tomcat 端口:
# http://localhost:8080/chorus/api/auth/me
```

---

### 4. 数据库连不上

```bash
# 报错: "Connection refused" 或 "数据库验证失败"
```

**原因**：PostgreSQL 还没完全启动，或密码不匹配。

**解决**：

```bash
# 查看数据库日志
docker compose logs postgres

# 等看到 "database system is ready to accept connections" 再试

# 确认 .env 或 setenv.bat 中的密码一致:
# 用户名: chorus
# 密码: chorus_dev_2026
# 数据库名: chorus
```

---

### 5. 端口被占用

```bash
# 报错: "port is already allocated"
```

**解决**：修改 `docker-compose.yml` 中的端口映射：

```yaml
services:
  postgres:
    ports:
      - "5433:5432"   # 把左边改成其他端口，右边是容器内端口不要动
  server:
    ports:
      - "4005:8080"   # 把 4001 改成 4005
```

改完重启：`docker compose up -d`

---

### 6. 前端 CORS 报错

```bash
# 浏览器控制台: "Access-Control-Allow-Origin" 错误
```

**解决**：确认 `client/.env` 中的地址是否正确指向 Tomcat 地址：

```env
# 外置 Tomcat 部署:
REACT_APP_API_URL=http://localhost:8080/chorus

# Docker 部署:
REACT_APP_API_URL=http://localhost:4001/chorus

# 嵌入式启动:
REACT_APP_API_URL=http://localhost:4001/chorus
```

修改后要**重启前端**（Ctrl+C 停掉，重新 `npm start`）才会生效。

---

## ⚠️ 已知限制

- **WebRTC 点对点未启用**：真实音频走服务端 WS 中继（延迟 ~200-500ms，学习项目可接受）；UDP SFU / 点对点是后续挑战
- **表情选择器是常用集而非全量 Unicode**；@提及是精确用户名匹配，无模糊下拉补全
- **单机内存版**：语音转发表与缓存（`CacheService`）都是进程内实现，不支持多实例横向扩展
- **会话 resume 窗口 30 秒**：超过后重连需重新 Identify
- **docker-compose 配置已修正但本机未装 Docker 验证**：`src/` 与 `server/` 双树用 `sync.bat` 一键同步（仅排除 `ChorusApplication.java`），改完后端记得跑一次

---

## 📋 待办 (Todo)

> ✅ 下列工程化事项已全部完成，项目已"闭环"。剩下的是长期挑战（见"学习路线建议"第四阶段）。

### ☑️ 1. 配置 CI（每次 push 自动跑测试）— ✅ 已完成

`.github/workflows/ci.yml` 已就绪：后端双树 job（`setup-java@17` → 分别跑 `mvn test` 与 `cd server && mvn test`）+ 前端 job（`setup-node@20` → `cd client && npm ci && npx tsc --noEmit && npm test`）。

项目已 `git init -b main`，远端仓库为 `https://github.com/Siegfried-Crowley/Chorus`，本地提交（含音视频通话、安全加固与 Chorus 改名）**已全部推送到 `origin/main`**；`.gitignore` 已排除 `data/`、`client/.env`、`target/`、`dist/`、`docs/build/`（论文构建中间产物）。每次 push 后 GitHub Actions 会自动跑后端双树与前端 job。

### ☑️ 2. 收敛双源码树 — ✅ 已完成（方案 B：同步脚本）

已写 `sync.bat`（robocopy 把 `src\main\java`、`src\test` 同步到 `server\`，仅排除 `ChorusApplication.java`）。改完后端跑一次即可：

```bat
sync.bat
mvn -f server/pom.xml test   :: 双验证
```

---

## 🤝 贡献指南

这是一个学习项目，欢迎提 Issue 和 PR！

**想加功能？** 建议先看上面的"学习路线"，从第四阶段的任务挑一个做。

**发现 Bug？** 直接提 Issue，我会尽快修复。

---

## ⚠️ 免责声明

- 🎯 **本项目纯属学习目的**，仅限本地运行
- 🚫 **不会联网公开、不提供给他人使用、不存在商用**
- 🔍 仅用来拆解学习 Discord 的底层实现逻辑
- ™️ Discord 是 Discord Inc. 的商标

---

<div align="center">
  
**Happy Coding!** 🚀

</div>
