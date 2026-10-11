import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import GatewayClient from './GatewayClient';

/**
 * Mock WebSocket：捕获实例与发帧，测试可手动驱动 open / receive / close。
 * 模拟浏览器 WebSocket 的最小协议面（属性事件、readyState、send、close）。
 */
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static OPEN = 1;
  static CONNECTING = 0;
  static CLOSING = 2;
  static CLOSED = 3;

  url: string;
  readyState: number = MockWebSocket.CONNECTING;
  sent: string[] = [];
  onopen: ((e?: any) => void) | null = null;
  onmessage: ((e: any) => void) | null = null;
  onclose: ((e: any) => void) | null = null;
  onerror: ((e?: any) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send(payload: string) { this.sent.push(payload); }

  close(code?: number) {
    this.readyState = MockWebSocket.CLOSED;
    if (this.onclose) this.onclose({ code: code ?? 1000, reason: '' });
  }

  // —— 测试辅助 ——
  open() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
  receive(payload: string) { this.onmessage?.({ data: payload }); }
  /** 模拟服务器/网络断开 */
  drop() { this.close(1000); }
}

describe('GatewayClient 状态机', () => {
  beforeEach(() => {
    MockWebSocket.instances = [];
    vi.useFakeTimers();
    (globalThis as any).WebSocket = MockWebSocket;
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('收到 Hello 后发送 Identify (OP2)', () => {
    const c = new GatewayClient();
    c.connect('token');
    const ws = MockWebSocket.instances[0];
    expect(ws).toBeDefined();

    ws.open();
    ws.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));

    expect(ws.sent.length).toBe(1);
    expect(JSON.parse(ws.sent[0]).op).toBe(2);
    c.destroy();
  });

  it('连接存活时重复 connect 不新建连接（幂等）', () => {
    const c = new GatewayClient();
    c.connect('token');
    const ws = MockWebSocket.instances[0];
    ws.open();
    ws.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));
    ws.receive(JSON.stringify({ op: 0, t: 'READY', s: 1, d: { session_id: 'sess', resume_gateway_url: 'ws://x' } }));

    c.connect('token'); // 重复 connect

    expect(MockWebSocket.instances.length).toBe(1);
    c.destroy();
  });

  it('断线重连走 Resume (OP6)，不再重新 Identify', async () => {
    const c = new GatewayClient();
    c.connect('token');
    const ws1 = MockWebSocket.instances[0];
    ws1.open();
    ws1.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));
    ws1.receive(JSON.stringify({ op: 0, t: 'READY', s: 1, d: { session_id: 'sess', resume_gateway_url: 'ws://x' } }));

    // 模拟网络断开 → onclose(code 1000) → 调度重连
    ws1.drop();
    await vi.advanceTimersByTimeAsync(1000);

    const ws2 = MockWebSocket.instances[1];
    expect(ws2).toBeDefined();
    ws2.open();
    // 服务器对新连接发 Hello，客户端应据此走 Resume 而不是 Identify
    ws2.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));

    const ops = ws2.sent.map((s) => JSON.parse(s).op);
    expect(ops).toContain(6);
    expect(ops).not.toContain(2);
    c.destroy();
  });

  it('重连后连接保持稳定（不自我关闭、不无限循环）', async () => {
    const c = new GatewayClient();
    c.connect('token');
    const ws1 = MockWebSocket.instances[0];
    ws1.open();
    ws1.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));
    ws1.receive(JSON.stringify({ op: 0, t: 'READY', s: 1, d: { session_id: 'sess', resume_gateway_url: 'ws://x' } }));

    ws1.drop();
    await vi.advanceTimersByTimeAsync(1000);

    const ws2 = MockWebSocket.instances[1];
    ws2.open();
    ws2.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));
    ws2.receive(JSON.stringify({ op: 0, t: 'RESUMED', s: 2, d: {} }));

    // 再过 5 秒：不应再有新的连接/关闭（旧实现会在 1 秒内自我 close 再重连）
    const before = MockWebSocket.instances.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(MockWebSocket.instances.length).toBe(before);
    c.destroy();
  });

  it('destroy 后旧连接的 onclose 不再触发重连', async () => {
    const c = new GatewayClient();
    c.connect('token');
    const ws1 = MockWebSocket.instances[0];
    ws1.open();
    ws1.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));
    ws1.receive(JSON.stringify({ op: 0, t: 'READY', s: 1, d: { session_id: 'sess', resume_gateway_url: 'ws://x' } }));

    c.destroy();
    ws1.drop(); // 过期 close 事件
    await vi.advanceTimersByTimeAsync(5000);

    expect(MockWebSocket.instances.length).toBe(1);
  });

  it('收到 HEARTBEAT_ACK 时不再误判掉线', async () => {
    const c = new GatewayClient();
    c.connect('token');
    const ws = MockWebSocket.instances[0];
    ws.open();
    ws.receive(JSON.stringify({ op: 10, d: { heartbeat_interval: 41250 } }));
    ws.receive(JSON.stringify({ op: 0, t: 'READY', s: 1, d: { session_id: 'sess', resume_gateway_url: 'ws://x' } }));

    // 心跳确认
    ws.receive(JSON.stringify({ op: 11, d: null }));

    // 推进超过一个心跳周期，不应触发重连
    await vi.advanceTimersByTimeAsync(50000);
    expect(MockWebSocket.instances.length).toBe(1);
    c.destroy();
  });
});
