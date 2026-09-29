import React, { useState, useRef } from 'react';
import { useStore } from '../../store';
import { messageApi, uploadApi } from '../../utils/api';
import { displayName, toMessage } from '../../utils/message';

interface Props {
  channelId: string;
  replyingTo: { id: string; authorId: string; content: string; channelId: string } | null;
  onCancelReply: () => void;
}

interface AttachmentMeta {
  url: string;
  filename: string;
  content_type?: string | null;
  size?: number;
}

const TYPING_THROTTLE_MS = 5000;

const MessageInput: React.FC<Props> = ({ channelId, replyingTo, onCancelReply }) => {
  const [content, setContent] = useState('');
  const [pendingFiles, setPendingFiles] = useState<File[]>([]);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const lastTypingSent = useRef(0);

  // 输入中节流上报
  const notifyTyping = () => {
    if (!content.trim()) return;
    const now = Date.now();
    if (now - lastTypingSent.current < TYPING_THROTTLE_MS) return;
    lastTypingSent.current = now;
    messageApi.typing(channelId);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const text = content.trim();
    if ((!text && pendingFiles.length === 0) || uploading) return;

    const nonce = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

    // 乐观更新
    useStore.getState().addOptimisticMessage(channelId, {
      id: `temp-${nonce}`,
      channelId,
      authorId: useStore.getState().currentUser?.id || '',
      content: text,
      timestamp: new Date().toISOString(),
      type: 0,
      nonce,
      messageReference: replyingTo ? { message_id: replyingTo.id, channel_id: replyingTo.channelId } : null,
    });

    setContent('');
    setPendingFiles([]);
    if (replyingTo) onCancelReply();

    try {
      // 先上传附件，拿到 URL 后再发消息
      const attachments: AttachmentMeta[] = [];
      if (pendingFiles.length > 0) {
        setUploading(true);
        for (const file of pendingFiles) {
          const meta = await uploadApi.upload(file);
          attachments.push(meta);
        }
      }

      const msg = await messageApi.send(channelId, {
        content: text,
        nonce,
        attachments: attachments.length > 0 ? JSON.stringify(attachments) : undefined,
        messageReference: replyingTo
          ? JSON.stringify({ message_id: replyingTo.id, channel_id: replyingTo.channelId })
          : undefined,
      });
      useStore.getState().replaceOptimisticMessage(channelId, `temp-${nonce}`, toMessage(msg));
    } catch (err) {
      console.error('Failed to send message', err);
      useStore.getState().removeMessage(channelId, `temp-${nonce}`);
    } finally {
      setUploading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const onPickFiles = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    if (files.length) setPendingFiles((prev) => [...prev, ...files]);
    e.target.value = '';
  };

  const channelName = useStore.getState().channels[channelId]?.name || '';

  return (
    <div className="message-input-container">
      {/* 回复引用条 */}
      {replyingTo && (
        <div className="reply-bar">
          <span className="reply-bar-label">回复 <span className="reply-bar-author">{displayName(replyingTo.authorId)}</span></span>
          <span className="reply-bar-text">{replyingTo.content || '(附件)'}</span>
          <button className="reply-bar-close" onClick={onCancelReply}>✕</button>
        </div>
      )}

      {pendingFiles.length > 0 && (
        <div className="pending-attachments">
          {pendingFiles.map((f, i) => (
            <div key={i} className="pending-attachment">
              <span className="pending-attachment-name">{f.name}</span>
              <button
                className="pending-attachment-remove"
                onClick={() => setPendingFiles((prev) => prev.filter((_, j) => j !== i))}
              >
                ✕
              </button>
            </div>
          ))}
        </div>
      )}
      <form onSubmit={handleSubmit} className="message-input-form">
        <button
          type="button"
          className="attach-btn"
          title="上传文件"
          onClick={() => fileInputRef.current?.click()}
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
            <path d="M16.5 6v11.5a4 4 0 0 1-8 0V5a2.5 2.5 0 0 1 5 0v10.5a1 1 0 0 1-2 0V6H10v9.5a2.5 2.5 0 0 0 5 0V5a4 4 0 0 0-8 0v12.5a5.5 5.5 0 0 0 11 0V6h-1.5z"/>
          </svg>
        </button>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          onChange={onPickFiles}
        />
        <textarea
          className="message-input"
          placeholder={`发送消息到 #${channelName || '私信'}${uploading ? ' (上传中...)' : ''}`}
          value={content}
          onChange={(e) => { setContent(e.target.value); notifyTyping(); }}
          onKeyDown={handleKeyDown}
          rows={1}
          maxLength={2000}
        />
        <button
          type="submit"
          className="send-btn"
          disabled={(!content.trim() && pendingFiles.length === 0) || uploading}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
          </svg>
        </button>
      </form>
    </div>
  );
};

export default MessageInput;
