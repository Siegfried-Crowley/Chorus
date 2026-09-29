import React from 'react';
import { useStore } from '../../store';

const DmSidebar: React.FC = () => {
  const { dmChannels, activeChannelId, setActiveChannel, setActiveGuild, setSidebar, setActiveDmId, unreadCount } = useStore();

  const handleDmClick = (dmId: string) => {
    setActiveDmId(dmId);
    setActiveGuild(null);
    setActiveChannel(dmId);
    setSidebar('dms');
  };

  return (
    <div className="channel-sidebar">
      <div className="sidebar-header">
        <h3>私信</h3>
      </div>

      <div className="sidebar-scroll">
        {dmChannels.length === 0 ? (
          <div className="sidebar-section-title">暂无私信</div>
        ) : (
          <div className="sidebar-section">
            {dmChannels.map((dm) => (
              <div
                key={dm.id}
                className={`channel-item dm-item ${activeChannelId === dm.id ? 'active' : ''}`}
                onClick={() => handleDmClick(dm.id)}
              >
                <div className="dm-avatar">
                  {dm.recipient?.username?.charAt(0).toUpperCase() || '?'}
                </div>
                <span className="channel-name">
                  {dm.recipient?.username || '私信'}
                  <span className="dm-last-message">
                    {dm.lastMessage || (dm.lastMessageId ? '' : '新私信')}
                  </span>
                </span>
                {(unreadCount[dm.id] || 0) > 0 && activeChannelId !== dm.id && (
                  <span className="unread-badge">{unreadCount[dm.id] > 99 ? '99+' : unreadCount[dm.id]}</span>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default DmSidebar;
