import React, { useRef, useEffect, useState } from 'react';
import { useStore } from '../../store';
import { messageApi } from '../../utils/api';
import { toMessage, displayName } from '../../utils/message';
import { Markdown } from '../../utils/markdown';

interface Props {
  channelId: string;
  onReply: (msg: { id: string; authorId: string; content: string; channelId: string } | null) => void;
  onOpenProfile?: (userId: string) => void;
}

const QUICK_EMOJIS = ['👍', '❤️', '😂', '😮', '😢', '🔥', '🎉', '👀'];

const MessageList: React.FC<Props> = ({ channelId, onReply, onOpenProfile }) => {
  const messages = useStore((state) => state.messages[channelId] || []);
  const bottomRef = useRef<HTMLDivElement>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  const [pickerFor, setPickerFor] = useState<string | null>(null);

  const currentUserId = useStore((s) => s.currentUser?.id);

  // 新消息 → 滚到底部
  const isNearBottom = () => {
    const el = document.querySelector('.message-list');
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight < 200;
  };
  useEffect(() => {
    if (isNearBottom()) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages.length]);

  // 跳转高亮:找到带 highlight 的消息,滚动到中间,2.5s 后清除
  useEffect(() => {
    const hl = messages.find((m) => m.highlight);
    if (!hl) return;
    const el = document.getElementById(`msg-${hl.id}`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const t = setTimeout(() => {
      useStore.getState().updateMessage(channelId, { ...hl, highlight: false });
    }, 2500);
    return () => clearTimeout(t);
  }, [messages, channelId]);

  const handleLoadMore = async () => {
    if (messages.length === 0) return;
    const oldestId = messages[messages.length - 1].id;
    if (oldestId.startsWith('temp-')) return;
    try {
      const older = await messageApi.list(channelId, { limit: 30, before: oldestId });
      if (older.length > 0) {
        useStore.getState().setMessages(channelId, [...older.map(toMessage).reverse(), ...messages]);
      }
    } catch (err) { console.error(err); }
  };

  const handleEdit = async (msgId: string) => {
    if (!editText.trim() || editText === messages.find((m) => m.id === msgId)?.content) {
      setEditingId(null); return;
    }
    const cur = messages.find((m) => m.id === msgId);
    if (!cur) { setEditingId(null); return; }
    try {
      await messageApi.edit(channelId, msgId, editText);
      useStore.getState().updateMessage(channelId, {
        ...cur,
        content: editText,
        editedTimestamp: new Date().toISOString(),
      });
      setEditingId(null);
    } catch (err) { console.error(err); }
  };

  const handleDelete = async (msgId: string) => {
    if (!window.confirm('删除这条消息？')) return;
    try {
      await messageApi.delete(channelId, msgId);
      useStore.getState().removeMessage(channelId, msgId);
    } catch (err) { console.error(err); }
  };

  // 切换反应(乐观更新 + 失败回滚)
  const toggleReaction = async (msg: any, emoji: string) => {
    if (!currentUserId) return;
    const st = useStore.getState();
    const cur = st.messages[channelId]?.find((m) => m.id === msg.id);
    if (!cur) return;
    const reactions = { ...(cur.reactions || {}) };
    const users = [...(reactions[emoji] || [])];
    const has = users.includes(currentUserId);
    let next: Record<string, string[]> = { ...reactions };
    if (has) {
      const rest = users.filter((u) => u !== currentUserId);
      if (rest.length) next[emoji] = rest;
      else delete next[emoji];
    } else {
      next[emoji] = [...users, currentUserId];
    }
    st.updateMessage(channelId, { ...cur, reactions: next });
    try {
      if (has) await messageApi.removeReaction(channelId, msg.id, emoji);
      else await messageApi.addReaction(channelId, msg.id, emoji);
    } catch (err) {
      st.updateMessage(channelId, { ...cur, reactions }); // 回滚
      alert('操作失败');
    }
    setPickerFor(null);
  };

  // 置顶 / 取消置顶
  const togglePin = async (msg: any) => {
    try {
      if (msg.pinned) {
        await messageApi.unpin(channelId, msg.id);
        useStore.getState().updateMessage(channelId, { ...msg, pinned: false });
      } else {
        await messageApi.pin(channelId, msg.id);
        useStore.getState().updateMessage(channelId, { ...msg, pinned: true });
      }
    } catch (err: any) {
      alert(err.response?.data?.error || '操作失败');
    }
  };

  const reactionRows = (msg: any): [string, string[]][] => {
    const map: Record<string, string[]> = (msg.reactions as Record<string, string[]> | null) || {};
    return Object.entries(map).filter(([, users]) => users && users.length > 0);
  };

  if (messages.length === 0) {
    return (
      <div className="message-list">
        <div className="no-messages">
          <p>暂无消息，开始聊天吧</p>
        </div>
      </div>
    );
  }

  return (
    <div className="message-list">
      <div className="load-more">
        <button className="load-more-btn" onClick={handleLoadMore}>加载更早的消息</button>
      </div>
      {messages.map((msg) => {
        const isOwn = String(msg.authorId) === String(currentUserId);
        const refMsg = msg.messageReference?.message_id
          ? messages.find((m) => m.id === msg.messageReference!.message_id)
          : null;
        return (
          <div
            key={msg.id}
            id={`msg-${msg.id}`}
            className={`message-item ${isOwn ? 'own' : ''} ${msg.highlight ? 'highlight' : ''}`}
          >
            <div
              className="message-avatar"
              title="查看资料"
              onClick={() => onOpenProfile?.(msg.authorId)}
            >
              <div className="avatar-circle">{displayName(msg.authorId).charAt(0).toUpperCase()}</div>
            </div>
            <div className="message-content">
              <div className="message-header">
                <span className="message-author">{displayName(msg.authorId)}</span>
                <span className="message-time">
                  {new Date(msg.timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
                  {msg.editedTimestamp ? ' (已编辑)' : ''}
                </span>
                {msg.pinned && <span className="pinned-tag">📌 已置顶</span>}
              </div>

              {/* 引用回复 */}
              {msg.messageReference?.message_id && (
                <div className="reply-quote">
                  {refMsg ? (
                    <>
                      <span className="reply-quote-author">{displayName(refMsg.authorId)}</span>
                      <span className="reply-quote-text">{refMsg.content || '(附件)'}</span>
                    </>
                  ) : (
                    <span className="reply-quote-text">引用已删除的消息</span>
                  )}
                </div>
              )}

              {editingId === msg.id ? (
                <div className="message-edit">
                  <input
                    className="message-edit-input"
                    value={editText}
                    onChange={(e) => setEditText(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') handleEdit(msg.id); if (e.key === 'Escape') setEditingId(null); }}
                    autoFocus
                  />
                  <button className="msg-edit-save" onClick={() => handleEdit(msg.id)}>保存</button>
                  <button className="msg-edit-cancel" onClick={() => setEditingId(null)}>取消</button>
                </div>
              ) : (
                <div className="message-text"><Markdown text={msg.content} /></div>
              )}

              {msg.attachments && msg.attachments.length > 0 && (
                <div className="message-attachments">
                  {msg.attachments.map((att, i) => {
                    const isImage = att.content_type?.startsWith('image/') || /\.(png|jpe?g|gif|webp)$/i.test(att.filename);
                    return isImage ? (
                      <a key={i} href={att.url} target="_blank" rel="noreferrer">
                        <img src={att.url} alt={att.filename} className="message-image" />
                      </a>
                    ) : (
                      <a key={i} href={att.url} target="_blank" rel="noreferrer" className="message-file">
                        📄 {att.filename}
                      </a>
                    );
                  })}
                </div>
              )}

              {/* 反应 */}
              {reactionRows(msg).length > 0 && (
                <div className="reaction-row">
                  {reactionRows(msg).map(([emoji, users]) => {
                    const active = users.includes(currentUserId || '');
                    return (
                      <button
                        key={emoji}
                        className={`reaction-pill ${active ? 'active' : ''}`}
                        onClick={() => toggleReaction(msg, emoji)}
                      >
                        {emoji} <span className="reaction-count">{users.length}</span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            {!editingId && (
              <div className="message-actions">
                <button className="msg-action" title="回应" onClick={() => setPickerFor(pickerFor === msg.id ? null : msg.id)}>
                  🙂
                </button>
                <button className="msg-action" title="回复" onClick={() => onReply({ id: msg.id, authorId: msg.authorId, content: msg.content, channelId })}>
                  ↩
                </button>
                <button className="msg-action" title={msg.pinned ? '取消置顶' : '置顶'} onClick={() => togglePin(msg)}>
                  📌
                </button>
                {isOwn && (
                  <>
                    <button className="msg-action" title="编辑"
                      onClick={() => { setEditingId(msg.id); setEditText(msg.content); }}>
                      ✏️
                    </button>
                    <button className="msg-action" title="删除" onClick={() => handleDelete(msg.id)}>
                      🗑️
                    </button>
                  </>
                )}
              </div>
            )}

            {/* emoji 快捷选择 */}
            {pickerFor === msg.id && (
              <div className="reaction-picker" onClick={(e) => e.stopPropagation()}>
                {QUICK_EMOJIS.map((e) => (
                  <button key={e} className="reaction-picker-emoji" onClick={() => toggleReaction(msg, e)}>
                    {e}
                  </button>
                ))}
              </div>
            )}
          </div>
        );
      })}
      <div ref={bottomRef} />
    </div>
  );
};

export default MessageList;
