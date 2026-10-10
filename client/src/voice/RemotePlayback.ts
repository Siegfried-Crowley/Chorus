/**
 * 单个远端用户的音频播放器:Audio + MediaSource + SourceBuffer(audio/webm;codecs=opus, mode=segments)。
 *
 * - append 串行:以 `!sourceBuffer.updating` 为闸,onupdateend 续灌队列;
 * - 自动播放策略:以 muted 起播,由用户手势调用 unmute() 恢复;
 * - ensureLiveEdge:积压超过阈值跳到实时边缘;prune:只留最近 ~3s buffered。
 */
export class RemotePlayback {
  private audio: HTMLAudioElement;
  private mediaSource: MediaSource;
  /** createObjectURL 产生的 blob URL：必须保存下来，销毁时用它 revoke(读 element.src 会拿到解析后的页面地址) */
  private readonly objectUrl: string;
  private sourceBuffer: SourceBuffer | null = null;
  private queue: ArrayBuffer[] = [];
  private started = false;
  /** 是否已收到过第一段(init segment),用于判断 SourceBuffer 是否就绪 */
  private receivedInit = false;

  constructor(private readonly mime: string) {
    this.audio = new Audio();
    this.audio.muted = true; // 自动播放策略:先静音起播
    this.mediaSource = new MediaSource();
    this.objectUrl = URL.createObjectURL(this.mediaSource);
    this.audio.src = this.objectUrl;

    this.mediaSource.addEventListener('sourceopen', () => {
      try {
        const sb = this.mediaSource.addSourceBuffer(mime);
        sb.mode = 'segments';
        sb.addEventListener('updateend', () => this.tryFlush());
        this.sourceBuffer = sb;
        this.tryFlush();
      } catch (err) {
        // 不支持该 MIME:放弃本用户播放
        console.warn('[Voice] addSourceBuffer failed', err);
      }
    });
  }

  enqueue(chunk: Uint8Array): void {
    // 复制一份,避免底层 ArrayBuffer 被复用/覆盖
    this.queue.push(chunk.slice().buffer as ArrayBuffer);
    this.tryFlush();
  }

  /** 追加队列 → 播放。以 !updating 为闸,一次只挂一个 append,updateend 续灌 */
  private tryFlush(): void {
    const sb = this.sourceBuffer;
    if (!sb || sb.updating) return;
    while (this.queue.length > 0) {
      const chunk = this.queue.shift()!;
      try {
        sb.appendBuffer(chunk);
        this.receivedInit = true;
        break; // 已进入 updating,等 updateend 再续
      } catch (err) {
        // 损坏/越界段:跳过继续
      }
    }
  }

  /** 静音起播(绕过自动播放策略) */
  play(): void {
    if (this.started) return;
    this.started = true;
    this.audio.play().catch(() => {});
  }

  unmute(): void {
    this.audio.muted = false;
  }

  setMuted(muted: boolean): void {
    this.audio.muted = muted;
  }

  /** 积压超过 maxLagSec 秒时跳到实时边缘,跳过语音积压 */
  ensureLiveEdge(maxLagSec = 5): void {
    const sb = this.sourceBuffer;
    if (!sb || sb.updating || sb.buffered.length === 0) return;
    try {
      const end = sb.buffered.end(sb.buffered.length - 1);
      if (end - this.audio.currentTime > maxLagSec) {
        this.audio.currentTime = Math.max(0, end - 1);
      }
    } catch {
      /* 忽略 */
    }
  }

  /** 只保留 buffered 里最近 ~maxDurationSec 秒,丢掉陈旧段 */
  prune(maxDurationSec = 3): void {
    const sb = this.sourceBuffer;
    if (!sb || sb.updating || sb.buffered.length === 0) return;
    try {
      const end = sb.buffered.end(sb.buffered.length - 1);
      const start = sb.buffered.start(0);
      if (end - start > maxDurationSec) {
        const newStart = end - maxDurationSec;
        if (newStart > start && this.audio.currentTime > start) {
          sb.remove(start, newStart);
        }
      }
    } catch {
      /* 忽略 */
    }
  }

  get isActive(): boolean {
    return this.started && this.audio.currentTime > 0;
  }

  destroy(): void {
    this.queue = [];
    try {
      this.sourceBuffer?.abort();
    } catch {
      /* 忽略 */
    }
    try {
      if (this.mediaSource.readyState === 'open') this.mediaSource.endOfStream();
    } catch {
      /* 忽略 */
    }
    this.audio.pause();
    try {
      this.audio.removeAttribute('src');
      this.audio.load();
    } catch {
      /* 忽略 */
    }
    try {
      URL.revokeObjectURL(this.objectUrl);
    } catch {
      /* 忽略 */
    }
  }
}
