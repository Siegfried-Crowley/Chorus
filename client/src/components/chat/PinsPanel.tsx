import React, { useEffect, useState } from 'react';
import { useStore } from '../../store';
import { messageApi } from '../../utils/api';
import { toMessage, displayName } from '../../utils/message';
import { Markdown } from '../../utils/markdown';

interface Props {
  channelId: string;
  onClose: () => void;
  onJump: (messageId: string) => void;
}

const PinsPanel: React.FC<Props> = ({ channelId, onClose, onJump }) => {
  const [pins, setPins] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const currentUserId = useStore((s) => s.currentUser?.id);

  useEffect(() => {
    messageApi.getPins(channelId)
      .then((data: any) => setPins(Array.isArray(data) ? data : []))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [channelId]);

  const handleUnpin = async (messageId: string) => {
    try {
      await messageApi.unpin(channelId, messageId);
      setPins((prev) => prev.filter((p) => p.id !== messageId));
    } catch (err: any) {
      alert(err.response?.data?.error || '操作失败');
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>📌 置顶消息</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>
        <div className="pins-body">
          {loading ? (
            <div className="pins-empty">加载中...</div>
          ) : pins.length === 0 ? (
            <div className="pins-empty">这个频道还没有置顶消息</div>
          ) : (
            pins.map((raw) => {
              const m = toMessage(raw);
              return (
                <div key={m.id} className="pin-item">
                  <div className="pin-avatar">{displayName(m.authorId).charAt(0).toUpperCase()}</div>
                  <div className="pin-content">
                    <div className="pin-header">
                      <span className="pin-author">{displayName(m.authorId)}</span>
                      <span className="pin-time">
                        {new Date(m.timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
                      </span>
                    </div>
                    <div className="pin-text"><Markdown text={m.content} /></div>
                  </div>
                  <div className="pin-actions">
                    <button className="msg-action" title="跳转" onClick={() => onJump(m.id)}>↩</button>
                    <button className="msg-action" title="取消置顶" onClick={() => handleUnpin(m.id)}>📌</button>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};

export default PinsPanel;
