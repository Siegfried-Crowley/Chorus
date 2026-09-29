import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../../store';
import { voiceApi } from '../../utils/api';
import { displayName } from '../../utils/message';
import { voiceClient } from '../../voice/VoiceClient';
import { gatewayClient } from '../../gateway/GatewayClient';
import { KIND_CAMERA, KIND_SCREEN } from '../../voice/voiceProtocol';
import type { FrameKind } from '../../voice/voiceProtocol';

/**
 * 语音/视频控制面板(真实音视频,基于 WS WebM 中继)
 * 加入顺序:Gateway OP4(广播 VOICE_STATE_UPDATE,他人可见)→ 本地麦克风 init(失败仅收听)
 *   → REST /voice/join 拿分配 token → /ws/voice connectAudio(MediaRecorder → WS 中继 → MediaSource)
 * 视频:摄像头/屏幕共享各自独立 MediaRecorder,kind 标签区分;远端按 (userId, kind) 渲染瓦片。
 * 离开:OP4 leave(服务端 leaveVoice)+ disconnectAudio(连带停摄像头/共享)。
 */
const VoicePanel: React.FC = () => {
  const {
    activeGuildId, activeChannelId, channels, currentUser,
    isVoiceConnected, isMuted, isDeafened, speakingUsers, voiceStates, videoSenders,
    setVoiceConnected, setMuted, setDeafened, setSpeaking,
  } = useStore();

  // 用 ref 记录当前状态,避免 effect 依赖闭包旧值
  const mutedRef = useRef(false);
  const deafenedRef = useRef(false);
  mutedRef.current = isMuted;
  deafenedRef.current = isDeafened;

  // "点击启用声音"横幅:远端播放以 muted 起播(自动播放策略),手势后恢复
  const [needsUnmute, setNeedsUnmute] = useState(false);
  const [isCameraOn, setCameraOn] = useState(false);
  const [isScreenOn, setScreenOn] = useState(false);
  /** 是否已连接语音:点“断开连接”后为 false(仍停留在频道页,可重新加入) */
  const [joined, setJoined] = useState(true);

  const channel = activeChannelId ? channels[activeChannelId] : null;

  // 切换频道 → 重置为已连接(新频道自动加入)
  useEffect(() => {
    setJoined(true);
  }, [activeChannelId]);

  // 远端视频推流状态 → store(驱动瓦片渲染)
  useEffect(() => {
    voiceClient.onVideoSendersChange((map) => useStore.getState().setVideoSenders(map));
    return () => voiceClient.onVideoSendersChange(null);
  }, []);

  // 加入/离开语音频道(真实音视频全链路)
  useEffect(() => {
    if (!activeChannelId || !activeGuildId || !currentUser) return;
    if (!channel || channel.type !== 2) return;
    if (!joined) return; // 用户已主动断开:不自动加入

    let cancelled = false;

    // 1) Gateway OP4 加入:服务端 joinVoice + 广播 VOICE_STATE_UPDATE
    gatewayClient.updateVoiceState(activeGuildId, activeChannelId, mutedRef.current, deafenedRef.current);
    setVoiceConnected(true);

    (async () => {
      // 2) 本地麦克风(拿不到则仅收听,不阻断)
      try {
        await voiceClient.init(currentUser.id);
      } catch (err) {
        console.warn('[Voice] 麦克风不可用,仅收听模式', err);
      }
      if (cancelled) return;

      // 3) 本地说话检测(VAD)(拿到流才生效,拿不到静默跳过)
      voiceClient.startVoiceActivityDetection((speaking) => {
        if (!cancelled) setSpeaking(currentUser.id, speaking);
      });

      // 4) REST join 拿分配 token → 连接媒体中继
      const sessionId = gatewayClient.getSessionId() || `gw-${currentUser.id}`;
      try {
        const { token } = await voiceApi.join(activeGuildId, activeChannelId, sessionId);
        if (cancelled) return;
        await voiceClient.connectAudio(activeChannelId, token, sessionId);
        if (cancelled) return;
        voiceClient.setMuted(mutedRef.current);
        voiceClient.setDeafened(deafenedRef.current);
        // 禁听时不提示;否则远端播放以静音起播,提示用户点一下启用声音
        setNeedsUnmute(!deafenedRef.current);
      } catch (err) {
        console.warn('[Voice] 媒体中继连接失败', err);
      }
    })();

    return () => {
      cancelled = true;
      setNeedsUnmute(false);
      // 离开清理:任何一步抛错都不能导致整页崩溃
      try {
        if (activeGuildId) {
          gatewayClient.updateVoiceState(activeGuildId, null);
        }
      } catch { /* ignore */ }
      try {
        voiceClient.disconnectAudio();
        voiceClient.disconnect();
      } catch { /* ignore */ }
      try {
        setVoiceConnected(false);
        setSpeaking(currentUser.id, false);
        setCameraOn(false);
        setScreenOn(false);
      } catch { /* ignore */ }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeChannelId, activeGuildId, currentUser, joined, setVoiceConnected, setSpeaking]);

  // 频道内成员变化 → 剔除已离开用户的远端播放器
  useEffect(() => {
    if (!activeChannelId) return;
    const active = new Set(
      Object.values(voiceStates)
        .filter((vs) => vs && vs.userId != null && vs.channelId === activeChannelId)
        .map((vs) => String(vs.userId)),
    );
    voiceClient.removeAbsentUsers(active);
  }, [voiceStates, activeChannelId]);

  const toggleMute = () => {
    const newMute = !isMuted;
    voiceClient.setMuted(newMute);
    setMuted(newMute);
    if (activeGuildId) {
      voiceApi.mute(activeGuildId, newMute).catch(console.error);
    }
  };

  const toggleDeaf = () => {
    const newDeaf = !isDeafened;
    voiceClient.setDeafened(newDeaf);
    setDeafened(newDeaf);
    if (newDeaf) {
      voiceClient.setMuted(true);
      setMuted(true);
    }
    if (activeGuildId) {
      voiceApi.deaf(activeGuildId, newDeaf).catch(console.error);
    }
  };

  const toggleCamera = async () => {
    if (isCameraOn) {
      voiceClient.stopCamera();
      setCameraOn(false);
      return;
    }
    const ok = await voiceClient.startCamera();
    if (!ok) {
      alert('摄像头不可用(无设备或权限被拒),不影响语音通话');
    }
    setCameraOn(voiceClient.isCameraOn);
  };

  const toggleScreen = async () => {
    if (isScreenOn) {
      voiceClient.stopScreen();
      setScreenOn(false);
      return;
    }
    const ok = await voiceClient.startScreen();
    setScreenOn(voiceClient.isScreenOn);
    if (!ok && voiceClient.isScreenOn === false) {
      alert('屏幕共享未开启(已取消或浏览器不支持)');
    }
  };

  const handleEnableSound = () => {
    voiceClient.unmuteAll();
    setNeedsUnmute(false);
  };

  /** 断开连接:保持停留在频道页,但退出语音(可重新加入) */
  const handleDisconnect = () => {
    setJoined(false); // 触发加入 effect 的 cleanup → 发 OP4 leave + 断开媒体中继
  };

  /** 重新加入当前语音频道 */
  const handleReconnect = () => {
    setJoined(true);
  };

  // 频道内用户列表(从 store 的 voiceStates 过滤;脏数据防护)
  const channelUsers = Object.values(voiceStates)
    .filter((vs) => vs && vs.userId != null && vs.channelId === activeChannelId)
    .map((vs) => ({ ...vs, userId: String(vs.userId) }));

  /** 视频瓦片:远端画面从 voiceClient 的播放器元素挂载 */
  const attachVideo = (el: HTMLVideoElement | null, userId: string, kind: FrameKind) => {
    if (!el) return;
    if (el.dataset.attached === '1') return;
    const v = voiceClient.videoElementFor(userId, kind);
    if (v) {
      el.dataset.attached = '1';
      el.appendChild(v);
    }
  };

  const attachSelf = (el: HTMLVideoElement | null, stream: MediaStream | null) => {
    if (el && stream) el.srcObject = stream;
  };

  return (
    <div className="voice-panel">
      <div className="voice-panel-header">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="#43b581">
          <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
          <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
        </svg>
        <span>{channel?.name || '语音频道'}</span>
        <span className="voice-panel-subtitle">{joined ? '音视频通话' : '已断开连接'}</span>
      </div>

      {!joined && (
        <div className="voice-disconnected-banner">已断开语音连接</div>
      )}

      {joined && needsUnmute && (
        <button className="voice-unmute-banner" onClick={handleEnableSound}>
          🔇 点击启用声音
        </button>
      )}

      <div className="voice-tiles">
        {channelUsers.length === 0 ? (
          <div className="voice-empty">频道中暂无其他人</div>
        ) : (
          channelUsers.map((vs) => {
            const isMe = String(vs.userId) === String(currentUser?.id ?? '');
            const vids = isMe ? null : (videoSenders[vs.userId] ?? null);
            const screenActive = vids?.screen === true;
            const cameraActive = vids?.camera === true;
            return (
              <div key={vs.userId} className={`voice-tile ${speakingUsers.has(vs.userId) ? 'speaking' : ''}`}>
                {isMe && voiceClient.cameraViewStream ? (
                  <video ref={(el) => attachSelf(el, voiceClient.cameraViewStream)} autoPlay muted playsInline className="voice-tile-video" />
                ) : isMe && voiceClient.screenViewStream ? (
                  <video ref={(el) => attachSelf(el, voiceClient.screenViewStream)} autoPlay muted playsInline className="voice-tile-video" />
                ) : !isMe && screenActive ? (
                  <div className="voice-tile-video screen"><video ref={(el) => attachVideo(el, vs.userId, KIND_SCREEN)} /></div>
                ) : !isMe && cameraActive ? (
                  <div className="voice-tile-video"><video ref={(el) => attachVideo(el, vs.userId, KIND_CAMERA)} /></div>
                ) : (
                  <div className="voice-user-avatar">{(isMe ? (currentUser?.username || '?') : displayName(vs.userId)).charAt(0).toUpperCase()}</div>
                )}
                <span className="voice-tile-name">
                  {isMe ? '我' : displayName(vs.userId)}
                  {screenActive && <span className="voice-tile-badge">共享中</span>}
                  {cameraActive && !screenActive && <span className="voice-tile-badge">摄像头</span>}
                  {vs.selfMute && <span className="voice-user-muted">🔇</span>}
                </span>
              </div>
            );
          })
        )}
      </div>

      <div className="voice-controls">
        <button className={`voice-btn ${isMuted ? 'danger' : ''}`} onClick={toggleMute} title="麦克风" disabled={!joined}>
          {isMuted ? '🔇' : '🎙️'}
        </button>

        <button className={`voice-btn ${isDeafened ? 'danger' : ''}`} onClick={toggleDeaf} title="耳机" disabled={!joined}>
          {isDeafened ? '🙉' : '🎧'}
        </button>

        <button className={`voice-btn ${isCameraOn ? 'active' : ''}`} onClick={toggleCamera} title="摄像头" disabled={!joined}>
          📷
        </button>

        <button className={`voice-btn ${isScreenOn ? 'active' : ''}`} onClick={toggleScreen} title="屏幕共享" disabled={!joined}>
          🖥️
        </button>

        {joined ? (
          <button className="voice-btn disconnect" onClick={handleDisconnect} title="断开连接">
            📞
          </button>
        ) : (
          <button className="voice-btn reconnect" onClick={handleReconnect} title="加入语音">
            📞
          </button>
        )}
      </div>
    </div>
  );
};

export default VoicePanel;
