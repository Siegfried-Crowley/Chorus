/**
 * 单个远端用户的视频播放器:video + MediaSource + SourceBuffer(video/webm;codecs=vp8/vp9, mode=segments)。
 *
 * 与 RemotePlayback 同构:
 * - append 串行:以 !sourceBuffer.updating 为闸,onupdateend 续灌队列;
 * - 自动播放策略:video 默认 muted + playsinline;
 * - ensureLiveEdge:积压超过阈值跳到实时边缘;prune:只留最近缓冲(视频保留更宽裕,避免关键帧被裁)。
 */
import type { FrameKind } from './voiceProtocol';

export class VideoPlayback {
  readonly element: HTMLVideoElement;
  private mediaSource: MediaSource;
  private sourceBuffer: SourceBuffer | null = null;
  private queue: ArrayBuffer[] = [];
  private started = false;

  constructor(
    private readonly mime: string,
    readonly kind: FrameKind,
  ) {
    this.element = document.createElement('video');
    this.element.muted = true; // 自动播放策略:静音起播(视频无声轨)
    this.element.autoplay = true;
    this.element.playsInline = true;
    this.element.setAttribute('playsinline', '');
    this.mediaSource = new MediaSource();
    this.element.src = URL.createObjectURL(this.mediaSource);

    this.mediaSource.addEventListener('sourceopen', () => {
      try {
        const sb = this.mediaSource.addSourceBuffer(mime);
        sb.mode = 'segments';
        sb.addEventListener('updateend', () => this.tryFlush());
        this.sourceBuffer = sb;
        this.tryFlush();
      } catch (err) {
        console.warn('[Voice] addSourceBuffer(video) failed', err);
      }
    });
  }

  enqueue(chunk: Uint8Array): void {
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
    this.element.play().catch(() => {});
  }

  /** 积压超过 maxLagSec 秒时跳到实时边缘(视频阈值比音频宽裕) */
  ensureLiveEdge(maxLagSec = 10): void {
    if (!this.sourceBuffer || this.sourceBuffer.updating) return;
    const sb = this.sourceBuffer;
    try {
      if (this.element.buffered.length > 0 && sb.buffered.length > 0) {
        const end = sb.buffered.end(sb.buffered.length - 1);
        if (end - this.element.currentTime > maxLagSec) {
          this.element.currentTime = end - 0.1;
        }
      }
    } catch {
      /* 忽略 */
    }
  }

  /** 只保留最近 ~6s 缓冲,防止内存积压 */
  prune(keepSec = 6): void {
    if (!this.sourceBuffer || this.sourceBuffer.updating) return;
    const sb = this.sourceBuffer;
    try {
      if (sb.buffered.length > 1) {
        const end = sb.buffered.end(sb.buffered.length - 1);
        const removeBefore = end - keepSec;
        const start = sb.buffered.start(0);
        if (start < removeBefore && removeBefore < end) {
          sb.remove(start, removeBefore);
        }
      }
    } catch {
      /* 忽略 */
    }
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
    try {
      this.element.pause();
    } catch {
      /* 忽略 */
    }
    try {
      this.element.src = '';
    } catch {
      /* 忽略 */
    }
    try {
      URL.revokeObjectURL(this.element.src);
    } catch {
      /* 忽略 */
    }
  }
}
