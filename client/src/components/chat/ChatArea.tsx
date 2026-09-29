import React, { useEffect, useState } from 'react';
import { useStore } from '../../store';
import { messageApi } from '../../utils/api';
import { toMessage, displayName } from '../../utils/message';
import MessageList from './MessageList';
import MessageInput from './MessageInput';
import PinsPanel from './PinsPanel';
import SearchModal from './SearchModal';
import UserProfileModal from '../common/UserProfileModal';

const ChatArea: React.FC = () => {
  const { activeChannelId, activeGuildId, channels, setMessages, typingUsers, currentUser } = useStore();
  const channel = activeChannelId ? channels[activeChannelId] : null;

  // 回复状态(在 ChatArea 提升,MessageList 设置 / MessageInput 展示)
  const [replyingTo, setReplyingTo] = useState<{ id: string; authorId: string; content: string; channelId: string } | null>(null);
  const [showPins, setShowPins] = useState(false);
  const [showSearch, setShowSearch] = useState(false);
  const [profileUserId, setProfileUserId] = useState<string | null>(null);

  // 加载消息
  useEffect(() => {
    if (!activeChannelId) return;
    messageApi.list(activeChannelId, { limit: 50 })
      .then((msgs: any) => setMessages(activeChannelId, (Array.isArray(msgs) ? msgs : []).map(toMessage).reverse()))
      .then(() => useStore.getState().markChannelRead(activeChannelId))
      .catch(console.error);
  }, [activeChannelId, setMessages]);

  // 输入中指示:每秒刷新,自动剔除已过期的
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const typingNames = activeChannelId
    ? Object.entries(typingUsers[activeChannelId] || {})
        .filter(([userId, exp]) => userId !== currentUser?.id && exp > now)
        .map(([userId]) => displayName(userId))
    : [];

  const handleJumpToMessage = (channelId: string, messageId: string) => {
    const st = useStore.getState();
    st.setActiveChannel(channelId);
    st.setActiveGuild(activeGuildId);
    // 高亮目标消息(MessageList 滚动到该消息并短暂高亮)
    const target = st.messages[channelId]?.find((m) => m.id === messageId);
    if (target) st.updateMessage(channelId, { ...target, highlight: true });
  };

  if (!channel) {
    return (
      <div className="chat-area">
        <div className="no-channel-selected">
          <h2>选择一个频道开始聊天</h2>
        </div>
      </div>
    );
  }

  const isVoice = channel.type === 2;

  return (
    <div className="chat-area">
      {/* 频道头 */}
      <div className="chat-header">
        <div className="chat-header-info">
          <span className="chat-header-prefix">#</span>
          <h3>{channel.name}</h3>
          {channel.topic && <span className="chat-topic">| {channel.topic}</span>}
        </div>
        <div className="chat-header-actions">
          {!isVoice && (
            <button className="chat-header-btn" title="搜索消息" onClick={() => setShowSearch(true)}>
              🔍
            </button>
          )}
          <button className="chat-header-btn" title="置顶消息" onClick={() => setShowPins(true)}>
            📌
          </button>
        </div>
      </div>

      {/* 消息列表 */}
      <MessageList channelId={activeChannelId!} onReply={setReplyingTo} onOpenProfile={setProfileUserId} />

      {/* 输入框 */}
      {!isVoice && (
        <>
          <MessageInput channelId={activeChannelId!} replyingTo={replyingTo} onCancelReply={() => setReplyingTo(null)} />
          {typingNames.length > 0 && (
            <div className="typing-indicator">
              <span className="typing-dots"><i /><i /><i /></span>
              {typingNames.join('、')} 正在输入...
            </div>
          )}
        </>
      )}

      {showPins && (
        <PinsPanel channelId={activeChannelId!} onClose={() => setShowPins(false)} onJump={(mid) => handleJumpToMessage(activeChannelId!, mid)} />
      )}
      {showSearch && activeGuildId && (
        <SearchModal guildId={activeGuildId} onClose={() => setShowSearch(false)} onOpenMessage={handleJumpToMessage} />
      )}
      {profileUserId && (
        <UserProfileModal userId={profileUserId} onClose={() => setProfileUserId(null)} />
      )}
    </div>
  );
};

export default ChatArea;
