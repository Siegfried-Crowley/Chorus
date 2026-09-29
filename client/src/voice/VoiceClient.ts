/**
 * Discord Voice 客户端(真实音频 + 视频,基于 WS WebM 中继)。
 *
 * 传输链路:
 *   getUserMedia(audio) → MediaRecorder(audio/webm;codecs=opus, timeslice 50ms, 32kbps)
 *     → 每 50ms 一个 Blob → ws.send([kind=0][chunk])
 *   getUserMedia(video) / getDisplayMedia → MediaRecorder(video/webm;codecs=vp8, timeslice 100ms)
 *     → ws.send([kind=1|2][chunk])
 *   服务端透明转发(前缀 8 字节 userId,按 kind 缓存 init segment 供新人重放)
 *     → 本端按 (userId, kind) 分发到 RemotePlayback(音频) / VideoPlayback(视频)。
 *
 * 关键点:
 *   - 静音用 recorder.pause()/resume(),不重启(保证 init segment 只发一次);
 *   - 静音时不开 recorder(否则被服务端丢弃的 init 会造成他人无法解码);
 *   - 静音只影响音频,摄像头/屏幕共享照常转发(服务端按 kind 过滤);
 *   - 麦克风被拒 → 仅收听模式,不阻断进入语音频道;摄像头被拒 → 无视频,不阻断;
 *   - 每 25s 发控制帧 ping 保活(服务端 idle timeout 300s);
 *   - 远端视频流 5s 无帧则销毁播放器(发送端静默停止的自愈)。
 */
import { parseRelayedFrame, buildJoinFrame, buildFrame, pickRecorderMime, pickMseMime, pickVideoMime, wsUrlFromLocation } from './voiceProtocol';
import type { FrameKind } from './voiceProtocol';
import { KIND_AUDIO, KIND_CAMERA, KIND_SCREEN } from './voiceProtocol';
import { RemotePlayback } from './RemotePlayback';
import { VideoPlayback } from './VideoPlayback';

type VoiceStatus = 'idle' | 'connected' | 'error';

export interface VideoSenderState {
  camera: boolean;
  screen: boolean;
}

class VoiceClient {
  private ws: WebSocket | null = null;
  private recorder: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private audioContext: AudioContext | null = null;
  private playbacks = new Map<string, RemotePlayback>();
  private videoPlaybacks = new Map<string, VideoPlayback>();
  /** `${userId}:${kind}` → 最近一帧时间戳,用于 5s 超时销毁 */
  private lastFrameAt = new Map<string, number>();
  private localUserId = '';
  private muted = false;
  private deafened = false;
  /** 用户手势后为 true:新加入的远端播放器直接有声,无需再点 */
  private soundEnabled = false;
  private recorderMime: string | null = null;
  private mseMime: string | null = null;
  private videoMime: string | null = null;
  private status: VoiceStatus = 'idle';
  private speakingDetected = false;
  private vadRaf: number | null = null;
  private heartbeat: number | null = null;
  private lastPing = 0;
  private onStatusChange: ((s: VoiceStatus) => void) | null = null;
  private videoSendersListener: ((map: Record<string, VideoSenderState>) => void) | null = null;
  private lastSendersJson = '';

  // 摄像头 / 屏幕共享
  private cameraStream: MediaStream | null = null;
  private cameraRecorder: MediaRecorder | null = null;
  private screenStream: MediaStream | null = null;
  private screenRecorder: MediaRecorder | null = null;

  // 音频配置 — 匹配 Discord 参数
  private readonly AUDIO_CONFIG: MediaTrackConstraints = {
    channelCount: 2,
    sampleRate: 48000,
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true,
  };

  /** 获取麦克风。失败抛错,由调用方降级为仅收听 */
  async init(userId: string): Promise<void> {
    this.localUserId = userId;
    this.stream = await this.withTimeout(
      navigator.mediaDevices.getUserMedia({ audio: this.AUDIO_CONFIG }),
      12_000, 'microphone',
    );
  }

  /**
   * 连接媒体中继:开 WS → 发 join 帧(token 来自 REST /voice/join)→ joined 后开始传输。
   * 返回 Promise,joined 后 resolve;错误帧/超时 reject。
   */
  connectAudio(channelId: string, token: string, sessionId: string): Promise<void> {
    this.disconnectAudio(); // 重置旧连接
    this.recorderMime = pickRecorderMime();
    this.mseMime = pickMseMime();
    this.videoMime = pickVideoMime();

    return new Promise<void>((resolve, reject) => {
      let ws: WebSocket;
      try {
        ws = new WebSocket(wsUrlFromLocation('/ws/voice'));
        ws.binaryType = 'arraybuffer'; // 二进制消息以 ArrayBuffer 到达
      } catch (err) {
        reject(err instanceof Error ? err : new Error(String(err)));
        return;
      }
      this.ws = ws;
      let settled = false;
      const timeout = window.setTimeout(() => {
        if (settled) return;
        settled = true;
        this.status = 'error';
        reject(new Error('voice join timeout'));
        try { ws.close(); } catch { /* ignore */ }
      }, 8000);

      ws.onopen = () => ws.send(buildJoinFrame(token, channelId, sessionId));

      ws.onmessage = (ev) => {
        if (typeof ev.data === 'string') {
          let msg: any;
          try {
            msg = JSON.parse(ev.data);
          } catch {
            return;
          }
          if (msg.type === 'joined') {
            if (settled) return;
            settled = true;
            window.clearTimeout(timeout);
            this.status = 'connected';
            this.startHeartbeat();
            this.startRecorder(); // joined 后才开始上行
            this.onStatusChange?.(this.status);
            resolve();
          } else if (msg.type === 'error') {
            if (settled) return;
            settled = true;
            window.clearTimeout(timeout);
            this.status = 'error';
            this.onStatusChange?.(this.status);
            reject(new Error(msg.message || 'voice error'));
            try { ws.close(); } catch { /* ignore */ }
          }
        } else {
          this.handleRelayedFrame(ev.data as ArrayBuffer);
        }
      };

      ws.onclose = () => {
        window.clearTimeout(timeout);
        this.ws = null;
        if (!settled) {
          settled = true;
          this.status = 'error';
          this.onStatusChange?.(this.status);
          reject(new Error('voice connection closed'));
        }
      };
      ws.onerror = () => { /* 错误由 onclose / error 帧处理 */ };
    });
  }

  private handleRelayedFrame(data: ArrayBuffer): void {
    const { senderId, kind, payload } = parseRelayedFrame(data);
    if (senderId === this.localUserId) return; // 兜底:服务端已去回声
    if (kind !== KIND_AUDIO) {
      this.handleVideoFrame(senderId, kind, payload);
      return;
    }
    if (!this.mseMime) return; // 本端不支持解码 → 忽略
    let pb = this.playbacks.get(senderId);
    if (!pb) {
      pb = new RemotePlayback(this.mseMime);
      // 自动播放策略:手势前静音起播;手势后(或不禁听时)直接有声
      pb.setMuted(this.deafened || !this.soundEnabled);
      this.playbacks.set(senderId, pb);
      pb.play();
    }
    pb.enqueue(payload);
  }

  private handleVideoFrame(senderId: string, kind: FrameKind, payload: Uint8Array): void {
    if (!this.videoMime) return; // 本端无视频解码能力 → 忽略
    const key = `${senderId}:${kind}`;
    let vp = this.videoPlaybacks.get(key);
    if (!vp) {
      vp = new VideoPlayback(this.videoMime, kind);
      this.videoPlaybacks.set(key, vp);
      vp.play();
    }
    vp.enqueue(payload);
    this.lastFrameAt.set(key, Date.now());
    this.notifyVideoSenders();
  }

  /** 供 UI 挂载视频画面:确保该 (用户, kind) 的播放器存在并返回其 <video> 元素;无视频能力返回 null */
  videoElementFor(userId: string, kind: FrameKind): HTMLVideoElement | null {
    if (!this.videoMime) return null;
    const key = `${userId}:${kind}`;
    let vp = this.videoPlaybacks.get(key);
    if (!vp) {
      vp = new VideoPlayback(this.videoMime, kind);
      this.videoPlaybacks.set(key, vp);
      vp.play();
    }
    return vp.element;
  }

  /** 谁在推摄像头/屏幕(近 5s 内有帧)。返回当前快照。 */
  get videoSenderMap(): Record<string, VideoSenderState> {
    const out: Record<string, VideoSenderState> = {};
    const now = Date.now();
    this.videoPlaybacks.forEach((_, key) => {
      const last = this.lastFrameAt.get(key) ?? 0;
      if (now - last > 5000) return;
      const [userId, kindStr] = key.split(':');
      const st = out[userId] ?? (out[userId] = { camera: false, screen: false });
      if (kindStr === String(KIND_CAMERA)) st.camera = true;
      else if (kindStr === String(KIND_SCREEN)) st.screen = true;
    });
    return out;
  }

  private notifyVideoSenders(): void {
    if (!this.videoSendersListener) return;
    const map = this.videoSenderMap;
    const json = JSON.stringify(Object.keys(map).sort().map((k) => [k, map[k]]));
    if (json === this.lastSendersJson) return;
    this.lastSendersJson = json;
    this.videoSendersListener(map);
  }

  onVideoSendersChange(listener: ((map: Record<string, VideoSenderState>) => void) | null): void {
    this.videoSendersListener = listener;
    if (listener) {
      this.lastSendersJson = '';
      listener(this.videoSenderMap);
    }
  }

  /**
   * 给浏览器媒体请求加超时:某些环境(权限弹窗未响应/设备被占用)下 getUserMedia
   * 既不 resolve 也不 reject,会让按钮永久无反馈,这里超时后按失败处理。
   */
  private withTimeout<T>(p: Promise<T>, ms: number, label: string): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const timer = window.setTimeout(() => reject(new Error(label + ' timeout')), ms);
      p.then(
        (v) => { window.clearTimeout(timer); resolve(v); },
        (e) => { window.clearTimeout(timer); reject(e); },
      );
    });
  }

  /** 开启摄像头(640x360, 1Mbps)。无摄像头/无视频编码能力/超时返回 false */
  async startCamera(): Promise<boolean> {
    if (this.cameraRecorder) return true;
    if (!this.videoMime) return false;
    let stream: MediaStream;
    try {
      stream = await this.withTimeout(
        navigator.mediaDevices.getUserMedia({
          video: { width: { ideal: 640 }, height: { ideal: 360 } },
          audio: false,
        }),
        12_000, 'camera',
      );
    } catch {
      return false;
    }
    this.cameraStream = stream;
    this.cameraRecorder = this.createVideoRecorder(stream, KIND_CAMERA, 1_000_000);
    if (!this.cameraRecorder) {
      this.stopTracks(stream);
      this.cameraStream = null;
      return false;
    }
    this.notifyVideoSenders();
    return true;
  }

  stopCamera(): void {
    const rec = this.cameraRecorder;
    this.cameraRecorder = null;
    if (rec && rec.state !== 'inactive') {
      try { rec.stop(); } catch { /* ignore */ }
    }
    if (this.cameraStream) {
      this.stopTracks(this.cameraStream);
      this.cameraStream = null;
    }
    this.notifyVideoSenders();
  }

  /** 开启屏幕共享(2.5Mbps)。用户取消或无编码能力返回 false */
  async startScreen(): Promise<boolean> {
    if (this.screenRecorder) return true;
    if (!this.videoMime) return false;
    let stream: MediaStream;
    try {
      stream = await this.withTimeout(
        navigator.mediaDevices.getDisplayMedia({ video: true }),
        60_000, 'screen share',
      );
    } catch {
      return false; // 用户取消分享或超时
    }
    this.screenStream = stream;
    this.screenRecorder = this.createVideoRecorder(stream, KIND_SCREEN, 2_500_000);
    if (!this.screenRecorder) {
      this.stopTracks(stream);
      this.screenStream = null;
      return false;
    }
    // 用户在浏览器分享栏点“停止共享” → 自动清理
    const track = stream.getVideoTracks()[0];
    if (track) {
      track.addEventListener('ended', () => {
        if (this.screenRecorder) this.stopScreen();
      });
    }
    this.notifyVideoSenders();
    return true;
  }

  stopScreen(): void {
    const rec = this.screenRecorder;
    this.screenRecorder = null;
    if (rec && rec.state !== 'inactive') {
      try { rec.stop(); } catch { /* ignore */ }
    }
    if (this.screenStream) {
      this.stopTracks(this.screenStream);
      this.screenStream = null;
    }
    this.notifyVideoSenders();
  }

  get isCameraOn(): boolean {
    return this.cameraRecorder != null;
  }

  get isScreenOn(): boolean {
    return this.screenRecorder != null;
  }

  /** 本端摄像头画面(自看,服务端无回声不回传) */
  get cameraViewStream(): MediaStream | null {
    return this.cameraStream;
  }

  /** 本端屏幕共享画面(自看) */
  get screenViewStream(): MediaStream | null {
    return this.screenStream;
  }

  private createVideoRecorder(stream: MediaStream, kind: FrameKind, bps: number): MediaRecorder | null {
    let rec: MediaRecorder;
    try {
      rec = new MediaRecorder(stream, { mimeType: this.videoMime!, videoBitsPerSecond: bps });
    } catch {
      try {
        rec = new MediaRecorder(stream);
      } catch {
        return null;
      }
    }
    rec.ondataavailable = (e) => {
      if (!e.data || e.data.size === 0) return;
      this.sendMediaChunk(kind, e.data);
    };
    rec.start(100); // timeslice 100ms
    return rec;
  }

  private sendMediaChunk(kind: FrameKind, blob: Blob): void {
    if (this.ws?.readyState !== WebSocket.OPEN) return;
    blob.arrayBuffer()
      .then((buf) => {
        try {
          this.ws?.send(buildFrame(kind, new Uint8Array(buf)));
        } catch {
          /* ignore */
        }
      })
      .catch(() => {});
  }

  private stopTracks(stream: MediaStream): void {
    try {
      stream.getTracks().forEach((t) => t.stop());
    } catch {
      /* ignore */
    }
  }

  /** 静音:pause/resume 录音器。静音时若 recorder 未开,则在解除静音时再开(保证 init 段发出) */
  setMuted(muted: boolean): void {
    this.muted = muted;
    const rec = this.recorder;
    if (!rec) {
      if (!muted && this.status === 'connected' && this.stream) this.startRecorder();
      return;
    }
    try {
      if (muted && rec.state !== 'paused') rec.pause();
      else if (!muted && rec.state === 'paused') rec.resume();
    } catch {
      /* ignore */
    }
  }

  /** 禁听:静音所有远端播放器(视频与音频均静音,画面保留) */
  setDeafened(deafened: boolean): void {
    this.deafened = deafened;
    this.playbacks.forEach((pb) => pb.setMuted(deafened || !this.soundEnabled));
  }

  /** 用户手势:恢复所有远端播放音量(绕过自动播放策略),后续新播放器也默认有声 */
  unmuteAll(): void {
    this.soundEnabled = true;
    this.playbacks.forEach((pb) => pb.setMuted(this.deafened));
  }

  /** 移除已离开频道的远端播放器 */
  removeAbsentUsers(activeUserIds: Set<string>): void {
    this.playbacks.forEach((pb, userId) => {
      if (!activeUserIds.has(userId)) {
        pb.destroy();
        this.playbacks.delete(userId);
      }
    });
    this.videoPlaybacks.forEach((vp, key) => {
      const userId = key.split(':')[0];
      if (!activeUserIds.has(userId)) {
        vp.destroy();
        this.videoPlaybacks.delete(key);
        this.lastFrameAt.delete(key);
      }
    });
    this.notifyVideoSenders();
  }

  /** 本地说话检测(VAD),驱动说话光环 */
  startVoiceActivityDetection(onSpeaking: (speaking: boolean) => void): void {
    if (!this.stream) return;
    try {
      const ctx = this.audioContext ?? (this.audioContext = new AudioContext());
      const source = ctx.createMediaStreamSource(this.stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      source.connect(analyser);
      const dataArray = new Uint8Array(analyser.frequencyBinCount);
      const detect = () => {
        analyser.getByteFrequencyData(dataArray);
        let sum = 0;
        for (let i = 0; i < dataArray.length; i++) sum += dataArray[i];
        const speaking = sum / dataArray.length > 20;
        if (speaking !== this.speakingDetected) {
          this.speakingDetected = speaking;
          onSpeaking(speaking);
        }
        this.vadRaf = requestAnimationFrame(detect);
      };
      detect();
    } catch {
      /* ignore */
    }
  }

  private startRecorder(): void {
    if (this.recorder || !this.stream || !this.recorderMime || this.muted) return;
    let rec: MediaRecorder;
    try {
      rec = new MediaRecorder(this.stream, { mimeType: this.recorderMime, audioBitsPerSecond: 32_000 });
    } catch {
      try {
        rec = new MediaRecorder(this.stream);
      } catch {
        return;
      }
    }
    this.recorder = rec;
    rec.ondataavailable = (e) => {
      if (!e.data || e.data.size === 0) return;
      if (this.ws?.readyState === WebSocket.OPEN && !this.muted) {
        this.sendMediaChunk(KIND_AUDIO, e.data);
      }
    };
    rec.start(50); // timeslice 50ms
  }

  private stopRecorder(): void {
    const rec = this.recorder;
    this.recorder = null;
    if (rec && rec.state !== 'inactive') {
      try {
        rec.stop();
      } catch {
        /* ignore */
      }
    }
  }

  private startHeartbeat(): void {
    if (this.heartbeat != null) return;
    this.lastPing = Date.now();
    this.heartbeat = window.setInterval(() => {
      // 25s 心跳(服务端 idle timeout 300s)
      if (this.ws?.readyState === WebSocket.OPEN && Date.now() - this.lastPing >= 25_000) {
        this.lastPing = Date.now();
        try { this.ws.send('{"type":"ping"}'); } catch { /* ignore */ }
      }
      // 音频播放维护:跳过积压 + 修剪
      this.playbacks.forEach((pb) => {
        pb.ensureLiveEdge();
        pb.prune();
      });
      // 视频播放维护 + 超时自愈(5s 无帧 → 发送端已停止,销毁播放器)
      const now = Date.now();
      this.videoPlaybacks.forEach((vp, key) => {
        vp.ensureLiveEdge();
        vp.prune();
        const last = this.lastFrameAt.get(key) ?? 0;
        if (now - last > 5000) {
          vp.destroy();
          this.videoPlaybacks.delete(key);
          this.lastFrameAt.delete(key);
          this.notifyVideoSenders();
        }
      });
    }, 1000);
  }

  private stopHeartbeat(): void {
    if (this.heartbeat != null) {
      window.clearInterval(this.heartbeat);
      this.heartbeat = null;
    }
  }

  /** 断开媒体中继:关 WS + 停录音/摄像头/共享 + 销毁所有播放器 */
  disconnectAudio(): void {
    this.stopHeartbeat();
    this.stopRecorder();
    this.stopCamera();
    this.stopScreen();
    this.playbacks.forEach((pb) => pb.destroy());
    this.playbacks.clear();
    this.videoPlaybacks.forEach((vp) => vp.destroy());
    this.videoPlaybacks.clear();
    this.lastFrameAt.clear();
    this.lastSendersJson = '';
    if (this.ws) {
      try { this.ws.close(); } catch { /* ignore */ }
      this.ws = null;
    }
    this.status = 'idle';
  }

  /** 释放麦克风 + 音频上下文(VAD) */
  disconnect(): void {
    this.disconnectAudio();
    if (this.vadRaf != null) cancelAnimationFrame(this.vadRaf);
    this.vadRaf = null;
    if (this.stream) {
      this.stopTracks(this.stream);
      this.stream = null;
    }
    if (this.audioContext) {
      this.audioContext.close().catch(() => {});
      this.audioContext = null;
    }
    this.speakingDetected = false;
  }

  get isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN && this.status === 'connected';
  }

  get localStream(): MediaStream | null {
    return this.stream;
  }

  onStatus(listener: ((s: VoiceStatus) => void) | null): void {
    this.onStatusChange = listener;
  }
}

export const voiceClient = new VoiceClient();

export default VoiceClient;
