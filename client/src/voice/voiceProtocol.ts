/**
 * 音视频中继协议纯函数(无副作用,可单测)。
 *
 * 线协议:
 *   上行(客户端→服务端): [1 字节 kind][WebM 分块];kind 省略时(旧客户端)首字节为 EBML 头 0x1A。
 *   下行(服务端→客户端): [8 字节大端 senderUserId][1 字节 kind][WebM 分块]。
 *   控制帧是 JSON 文本。
 *
 * kind 定义: 0=音频(Opus), 1=摄像头视频, 2=屏幕共享。
 * 兼容性:kind 检测依赖"WebM EBML 头固定以 0x1A 开头,不会是 0/1/2"这一事实,
 * 旧音频帧自然归入 kind=0。
 */

export type FrameKind = 0 | 1 | 2;

export const KIND_AUDIO: FrameKind = 0;
export const KIND_CAMERA: FrameKind = 1;
export const KIND_SCREEN: FrameKind = 2;

export interface RelayedFrame {
  /** 发送者 userId(字符串形式) */
  senderId: string;
  /** 媒体流类型:0=音频 1=摄像头 2=屏幕 */
  kind: FrameKind;
  /** 去掉前缀与 kind 字节后的媒体字节(WebM chunk) */
  payload: Uint8Array;
}

/** 帧首字节 ∈ {0,1,2} → kind 标签;否则(旧帧,EBML 头 0x1A)按音频处理 */
export function detectKind(payload: Uint8Array): FrameKind {
  if (payload.length === 0) return KIND_AUDIO;
  const b = payload[0];
  return b <= 2 ? (b as FrameKind) : KIND_AUDIO;
}

/** 解析中继帧:[8 字节大端 userId][1 字节 kind?][payload];无 kind 标签的旧帧不剥离首字节 */
export function parseRelayedFrame(data: ArrayBuffer | ArrayBufferView): RelayedFrame {
  const bytes =
    data instanceof ArrayBuffer
      ? new Uint8Array(data)
      : new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength));
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const senderId = view.getBigUint64(0, false).toString(); // 大端
  const rest = bytes.slice(8);
  const first = rest.length > 0 ? rest[0] : -1;
  const hasKindTag = first >= 0 && first <= 2; // 0/1/2 = kind 标签;EBML 头 0x1A 为旧帧
  const kind: FrameKind = hasKindTag ? (first as FrameKind) : KIND_AUDIO;
  const payload = hasKindTag ? rest.slice(1) : rest;
  return { senderId, kind, payload };
}

/** 构造上行帧:[1 字节 kind][chunk] */
export function buildFrame(kind: FrameKind, chunk: Uint8Array): Uint8Array {
  const out = new Uint8Array(1 + chunk.length);
  out[0] = kind;
  out.set(chunk, 1);
  return out;
}

/** 构造 join 控制帧 */
export function buildJoinFrame(token: string, channelId: string, sessionId: string): string {
  return JSON.stringify({ type: 'join', token, channelId, sessionId });
}

/** 录音端可选 MIME:只支持 WebM(服务端中继单一编码);都不支持 → null = 本端仅收听 */
export function pickRecorderMime(): string | null {
  if (typeof MediaRecorder === 'undefined') return null;
  for (const m of ['audio/webm;codecs=opus', 'audio/webm']) {
    try {
      if (MediaRecorder.isTypeSupported(m)) return m;
    } catch {
      /* 忽略 */
    }
  }
  return null;
}

/** 播放端可选 MIME:WebM 不支持则该端无法解码 → 收到的帧被忽略 */
export function pickMseMime(): string | null {
  if (typeof MediaSource === 'undefined') return null;
  for (const m of ['audio/webm;codecs=opus', 'audio/webm']) {
    try {
      if (MediaSource.isTypeSupported(m)) return m;
    } catch {
      /* 忽略 */
    }
  }
  return null;
}

/** 视频编码可选 MIME(摄像头与屏幕共享共用):VP8 优先,回退 VP9;不支持 → null = 本端无视频能力 */
export function pickVideoMime(): string | null {
  if (typeof MediaRecorder === 'undefined' || typeof MediaSource === 'undefined') return null;
  for (const m of ['video/webm;codecs=vp8', 'video/webm;codecs=vp9', 'video/webm']) {
    try {
      if (MediaRecorder.isTypeSupported(m) && MediaSource.isTypeSupported(m)) return m;
    } catch {
      /* 忽略 */
    }
  }
  return null;
}

/** 音频 WS 地址:优先 .env 的 VITE_VOICE_URL,否则按当前页派生(Vite 代理 /ws 到后端) */
export function wsUrlFromLocation(path: string, envUrl?: string): string {
  const configured = envUrl ?? (import.meta.env?.VITE_VOICE_URL as string | undefined);
  if (configured) return configured;
  // 非浏览器环境(如 vitest node)没有宿主地址,返回空串由调用方判定
  if (typeof location === 'undefined') return '';
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.host}${path}`;
}
