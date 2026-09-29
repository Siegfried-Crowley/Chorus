import { afterEach, describe, expect, it } from 'vitest';
import { buildFrame, buildJoinFrame, detectKind, parseRelayedFrame, pickMseMime, pickRecorderMime, wsUrlFromLocation } from './voiceProtocol';

/** 构造 [8 字节大端 userId][payload] 帧 */
function frameFor(userId: string, payload: Uint8Array): ArrayBuffer {
  const buf = new ArrayBuffer(8 + payload.length);
  const view = new DataView(buf);
  view.setBigUint64(0, BigInt(userId), false); // big-endian
  new Uint8Array(buf).set(payload, 8);
  return buf;
}

describe('parseRelayedFrame', () => {
  it('解析 8 字节大端前缀 + 旧式无 kind 标签的音频帧', () => {
    // WebM EBML 头固定 0x1A 开头,不落入 kind 值 0/1/2 → 按音频(kind=0)处理
    const payload = new Uint8Array([0x1a, 2, 3, 4]);
    const { senderId, kind, payload: out } = parseRelayedFrame(frameFor('1000000000000001', payload));
    expect(senderId).toBe('1000000000000001');
    expect(kind).toBe(0);
    expect(Array.from(out)).toEqual([0x1a, 2, 3, 4]);
  });

  it('解析 kind=1 摄像头帧(剥离 kind 字节)', () => {
    const payload = new Uint8Array([1, 0x9d, 0x01, 0x2a]);
    const { senderId, kind, payload: out } = parseRelayedFrame(frameFor('42', payload));
    expect(senderId).toBe('42');
    expect(kind).toBe(1);
    expect(Array.from(out)).toEqual([0x9d, 0x01, 0x2a]);
  });

  it('解析 kind=2 屏幕共享帧', () => {
    const payload = new Uint8Array([2, 0x81, 0x88]);
    const { kind, payload: out } = parseRelayedFrame(frameFor('7', payload));
    expect(kind).toBe(2);
    expect(Array.from(out)).toEqual([0x81, 0x88]);
  });

  it('kind=0 音频帧(带显式标签)正确剥离', () => {
    const payload = new Uint8Array([0, 0x1a, 0x45]);
    const { kind, payload: out } = parseRelayedFrame(frameFor('9', payload));
    expect(kind).toBe(0);
    expect(Array.from(out)).toEqual([0x1a, 0x45]);
  });

  it('前缀按大端编码(userId 高低字节序正确)', () => {
    // userId=1 → 前缀应为 00 00 00 00 00 00 00 01
    const frame = frameFor('1', new Uint8Array([0x1a]));
    const bytes = new Uint8Array(frame);
    expect(Array.from(bytes.slice(0, 8))).toEqual([0, 0, 0, 0, 0, 0, 0, 1]);
    expect(bytes[8]).toBe(0x1a);
  });

  it('空 payload 也能解析(kind 默认 0)', () => {
    const { senderId, kind, payload } = parseRelayedFrame(frameFor('42', new Uint8Array(0)));
    expect(senderId).toBe('42');
    expect(kind).toBe(0);
    expect(payload.length).toBe(0);
  });

  it('接受 ArrayBufferView(如 Buffer/Blob 转来的视图)', () => {
    const frame = frameFor('7', new Uint8Array([0x1a, 5]));
    const view = new Uint8Array(frame, 0, frame.byteLength);
    const { senderId, kind, payload } = parseRelayedFrame(view);
    expect(senderId).toBe('7');
    expect(kind).toBe(0);
    expect(payload[0]).toBe(0x1a);
  });
});

describe('buildFrame / detectKind', () => {
  it('buildFrame 生成 [kind][chunk] 上行帧', () => {
    const out = buildFrame(1, new Uint8Array([0x9d, 0x01]));
    expect(Array.from(out)).toEqual([1, 0x9d, 0x01]);
  });

  it('detectKind:首字节 0/1/2 为 kind,其余(EBML 0x1A)按音频', () => {
    expect(detectKind(new Uint8Array([0]))).toBe(0);
    expect(detectKind(new Uint8Array([1]))).toBe(1);
    expect(detectKind(new Uint8Array([2]))).toBe(2);
    expect(detectKind(new Uint8Array([0x1a, 0x45]))).toBe(0);
    expect(detectKind(new Uint8Array(0))).toBe(0);
  });
});

describe('buildJoinFrame', () => {
  it('生成 type=join 的 JSON 控制帧', () => {
    const frame = JSON.parse(buildJoinFrame('tok-1', '100', 'sess-9'));
    expect(frame).toEqual({ type: 'join', token: 'tok-1', channelId: '100', sessionId: 'sess-9' });
  });
});

describe('pickRecorderMime / pickMseMime', () => {
  const realRecorder = (globalThis as any).MediaRecorder;
  const realMse = (globalThis as any).MediaSource;

  afterEach(() => {
    (globalThis as any).MediaRecorder = realRecorder;
    (globalThis as any).MediaSource = realMse;
  });

  it('无 MediaRecorder(如 node 环境)→ null,表示该端仅收听', () => {
    (globalThis as any).MediaRecorder = undefined;
    expect(pickRecorderMime()).toBeNull();
  });

  it('支持 opus 时优先返回 audio/webm;codecs=opus', () => {
    class FakeRecorder {
      static isTypeSupported(m: string): boolean {
        return m === 'audio/webm;codecs=opus';
      }
    }
    (globalThis as any).MediaRecorder = FakeRecorder;
    expect(pickRecorderMime()).toBe('audio/webm;codecs=opus');
  });

  it('只支持通用 webm 时降级返回 audio/webm', () => {
    class FakeRecorder {
      static isTypeSupported(m: string): boolean {
        return m === 'audio/webm';
      }
    }
    (globalThis as any).MediaRecorder = FakeRecorder;
    expect(pickRecorderMime()).toBe('audio/webm');
  });

  it('无 MediaSource → null', () => {
    (globalThis as any).MediaSource = undefined;
    expect(pickMseMime()).toBeNull();
  });

  it('MediaSource 支持 opus 时返回对应 MIME', () => {
    class FakeMse {
      static isTypeSupported(m: string): boolean {
        return m === 'audio/webm;codecs=opus';
      }
    }
    (globalThis as any).MediaSource = FakeMse;
    expect(pickMseMime()).toBe('audio/webm;codecs=opus');
  });
});

describe('wsUrlFromLocation', () => {
  it('显式传入的音频 URL 优先于 .env', () => {
    expect(wsUrlFromLocation('/ws/voice', 'wss://relay.example.com/ws/voice'))
      .toBe('wss://relay.example.com/ws/voice');
  });

  it('无显式值时从 .env 的 VITE_VOICE_URL 读取', () => {
    // vitest 会加载 client/.env → VITE_VOICE_URL=ws://localhost:3000/ws/voice
    const cfg = import.meta.env?.VITE_VOICE_URL as string | undefined;
    expect(wsUrlFromLocation('/ws/voice')).toBe(cfg ?? '');
  });
});
