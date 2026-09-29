/**
 * Chorus Gateway 客户端 (v9)
 * 管理 WebSocket 连接、心跳、重连、事件分发
 *
 * 修复记录：
 *  - 用 generation 代际计数 + 取消挂起定时器，杜绝"重连计划互相杀掉对方连接"的死循环
 *  - connect() 幂等：同一 token 且连接存活时不重复建连，避免重复 connect 打断当前连接
 *  - Resume 协议对齐：每条连接服务器都会先发 Hello，客户端二选一（有会话发 OP6 Resume，否则发 OP2 Identify），
 *    不再"Resume 与 Identify 同时发送"导致每次重连都重新触发 READY
 */
type EventHandler = (data: any) => void;

class GatewayClient {
  private ws: WebSocket | null = null;
  private token: string = '';
  private sessionId: string | null = null;
  private seq: number | null = null;
  private heartbeatInterval: number = 41250;
  private heartbeatTimer: any = null;
  private lastHeartbeatAck: boolean = true;
  private missedHeartbeats: number = 0;
  private reconnectAttempts: number = 0;
  private reconnectTimer: any = null;
  private resuming: boolean = false;      // 本次连接打算走 OP6 Resume
  private handlers: Map<string, EventHandler[]> = new Map();
  private resumeUrl: string;
  private destroyed: boolean = false;
  private generation: number = 0;         // 代际计数：过期连接的回调/定时器全部失效

  private readonly GATEWAY_URL: string;

  constructor() {
    const defaultWs = import.meta.env.VITE_GATEWAY_URL || 'ws://localhost:3000/ws';
    this.GATEWAY_URL = defaultWs;
    this.resumeUrl = defaultWs;
  }

  connect(token: string) {
    // 幂等：同一 token 且连接已存在（连上或正在握手）时不做任何事，
    // 避免 React effect 重复执行 / 并发调用把当前连接打掉
    if (this.ws
      && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)
      && this.token === token) {
      console.log('[Gateway] Already connected, skip duplicate connect');
      return;
    }

    this.token = token;
    this.destroyed = false;
    this.reconnectAttempts = 0;
    this.resuming = false;      // 全新连接走 OP2 Identify
    this.createConnection();
  }

  private createConnection() {
    // 任何新连接都先取消挂起的重连定时器，保证同一时刻只有一条重连计划
    this.cancelReconnect();
    // 关掉旧连接（摘掉回调），旧连接的 onclose 不会再触发重连逻辑
    this.teardownSocket();

    const gen = ++this.generation;
    console.log('[Gateway] Connecting to', this.resumeUrl);
    const ws = new WebSocket(this.resumeUrl);
    this.ws = ws;

    ws.onopen = () => {
      if (gen !== this.generation) return;
      console.log('[Gateway] Connected');
      // 不在 open 时发 OP6：等 HELLO 到达后再由 handleMessage 决定 Identify / Resume
    };

    ws.onmessage = (event) => {
      if (gen !== this.generation) return;
      try {
        const msg = JSON.parse(event.data);
        // 注意: 网关载荷保持 snake_case 原样分发(客户端协议字段与 toMessage 均按 snake 读取),
        // 仅 REST 响应在 api.ts 统一转 camelCase
        this.handleMessage(msg);
      } catch (e) {
        console.error('[Gateway] Parse error', e);
      }
    };

    ws.onclose = (event) => {
      if (gen !== this.generation) return;   // 过期连接：已被更新代际取代
      if (this.ws !== ws) return;            // 已被更新的连接替换
      this.ws = null;
      this.stopHeartbeat();

      console.log('[Gateway] Closed', event.code, event.reason);

      if (event.code === 4004 || event.code === 4005) {
        // 认证失败，不重连
        this.emit('AUTH_FAILED', {});
        return;
      }

      if (this.destroyed) return;

      // 断线重连
      const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
      this.reconnectAttempts++;
      console.log(`[Gateway] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);

      this.cancelReconnect();
      this.reconnectTimer = setTimeout(() => {
        if (this.destroyed || gen !== this.generation) return;
        this.resume();
      }, delay);
    };

    ws.onerror = (err) => {
      if (gen !== this.generation) return;
      console.error('[Gateway] Error', err);
    };
  }

  /** 关闭并丢弃当前 socket，回调全部摘除，保证不会触发重连逻辑 */
  private teardownSocket() {
    if (this.ws) {
      const old = this.ws;
      this.ws = null;
      old.onopen = old.onmessage = old.onclose = old.onerror = null;
      try { old.close(1000); } catch (e) { /* ignore */ }
    }
  }

  private handleMessage(msg: any) {
    const { op, t, s, d } = msg;

    switch (op) {
      case 10: // Hello
        this.heartbeatInterval = d.heartbeat_interval || 41250;
        this.startHeartbeat();
        // 协议：每条连接服务器都会先发 Hello，由客户端决定走 Resume 还是 Identify
        if (this.resuming && this.sessionId != null) {
          console.log('[Gateway] Resuming on Hello (OP6)');
          this.send(6, {
            token: this.token,
            session_id: this.sessionId,
            seq: this.seq,
          });
          this.resuming = false;
        } else {
          this.identify();
        }
        break;

      case 11: // Heartbeat ACK
        this.lastHeartbeatAck = true;
        this.missedHeartbeats = 0;
        this.emit('HEARTBEAT_ACK', {});
        break;

      case 0: // Dispatch
        this.seq = s;
        this.handleDispatch(t, d);
        break;

      case 7: // Reconnect
        console.log('[Gateway] Server requested reconnect');
        this.reconnect();
        break;

      case 9: // Invalid Session
        console.log('[Gateway] Invalid session', d);
        if (d === true) {
          // 可以 resume
          this.resume();
        } else {
          // 需要重新 identify
          this.sessionId = null;
          this.seq = null;
          this.resuming = false;
          this.identify();
        }
        break;
    }
  }

  private handleDispatch(type: string, data: any) {
    if (type === 'READY') {
      this.sessionId = data.session_id;
      this.resumeUrl = data.resume_gateway_url || this.resumeUrl;
      this.reconnectAttempts = 0;
      console.log('[Gateway] Ready, session:', this.sessionId);
    } else if (type === 'RESUMED') {
      console.log('[Gateway] Resumed');
      this.reconnectAttempts = 0;
    }

    this.emit(type, data);
    // 也触发通用事件
    this.emit('DISPATCH', { type, data });
  }

  private identify() {
    this.resuming = false;
    this.send(2, {
      token: this.token,
      capabilities: 16381,
      properties: {
        os: navigator.platform,
        browser: 'Chorus',
        device: '',
        system_locale: navigator.language,
      },
      presence: {
        status: 'online',
        since: 0,
        activities: [],
        afk: false,
      },
      compress: false,
      intents: 32767,
    });
  }

  private resume() {
    if (this.sessionId != null) {
      console.log('[Gateway] Attempting resume', this.sessionId, this.seq);
      this.resuming = true;
      this.createConnection();
    } else {
      console.log('[Gateway] No session to resume, fresh connect');
      this.resuming = false;
      this.createConnection();
    }
  }

  private reconnect() {
    // 服务器要求重连 / 心跳超时：有会话则走 Resume，否则重新 Identify
    this.stopHeartbeat();
    this.resuming = this.sessionId != null;
    this.createConnection();
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    this.lastHeartbeatAck = true;

    this.heartbeatTimer = setInterval(() => {
      if (!this.lastHeartbeatAck) {
        this.missedHeartbeats++;
        console.warn(`[Gateway] Missed heartbeat ${this.missedHeartbeats}/3`);
        if (this.missedHeartbeats >= 3) {
          console.log('[Gateway] Too many missed heartbeats, reconnecting');
          this.reconnect();
          return;
        }
      }
      this.lastHeartbeatAck = false;
      this.send(1, this.seq);
    }, this.heartbeatInterval);
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private cancelReconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private send(op: number, data: any) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ op, d: data }));
    }
  }

  // 事件注册
  on(event: string, handler: EventHandler) {
    if (!this.handlers.has(event)) {
      this.handlers.set(event, []);
    }
    this.handlers.get(event)!.push(handler);
    return () => this.off(event, handler);
  }

  off(event: string, handler: EventHandler) {
    const handlers = this.handlers.get(event);
    if (handlers) {
      const idx = handlers.indexOf(handler);
      if (idx >= 0) handlers.splice(idx, 1);
    }
  }

  private emit(event: string, data: any) {
    const handlers = this.handlers.get(event);
    if (handlers) {
      handlers.forEach((h) => h(data));
    }
  }

  // 更新在线状态
  updatePresence(status: string) {
    this.send(3, {
      since: Date.now(),
      activities: [],
      status,
      afk: false,
    });
  }

  // 更新语音状态
  updateVoiceState(guildId: string, channelId: string | null, selfMute = false, selfDeaf = false) {
    this.send(4, {
      guild_id: guildId,
      channel_id: channelId,
      self_mute: selfMute,
      self_deaf: selfDeaf,
    });
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  /** 当前 Gateway 会话 ID(供语音鉴权使用;未 READY 时为 null) */
  getSessionId(): string | null {
    return this.sessionId;
  }

  get ping(): number {
    return this.heartbeatInterval;
  }

  destroy() {
    this.destroyed = true;
    this.generation++;           // 使所有挂起的重连定时器/回调失效
    this.cancelReconnect();
    this.stopHeartbeat();
    this.teardownSocket();
    this.handlers.clear();
    this.sessionId = null;
    this.seq = null;
    this.resuming = false;
  }
}

// 单例
export const gatewayClient = new GatewayClient();
export default GatewayClient;
