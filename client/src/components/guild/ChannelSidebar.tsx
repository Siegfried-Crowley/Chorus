import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../../store';
import { channelApi, guildApi } from '../../utils/api';
import { PERMS, computeGuildPerms, isOwnerOf } from '../../utils/permissions';
import ChannelPermissionsModal from './ChannelPermissionsModal';
import CreateChannelModal from './CreateChannelModal';
import GuildSettingsModal, { InvitesTab } from './GuildSettingsModal';

const ChannelSidebar: React.FC = () => {
  const { guilds, channels, activeGuildId, activeChannelId, setActiveChannel,
    voiceStates, currentUser, roles, members, updateChannel, removeChannel, unreadCount,
    setActiveGuild, removeGuild, setSidebar } = useStore();
  const [permChannelId, setPermChannelId] = useState<string | null>(null);
  const [createFor, setCreateFor] = useState<{ type: number; parentId?: string } | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [menu, setMenu] = useState<{ channelId: string; x: number; y: number } | null>(null);
  const [showInvite, setShowInvite] = useState(false);
  const [showGuildMenu, setShowGuildMenu] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const guildMenuRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  const guild = activeGuildId ? guilds[activeGuildId] : null;

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenu(null);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  if (!guild || !currentUser) return null;

  const isOwner = isOwnerOf(guild, currentUser.id);
  const perms = computeGuildPerms(guild.id, currentUser.id, roles, members);
  const canManage = isOwner || (perms & PERMS.MANAGE_CHANNELS) === PERMS.MANAGE_CHANNELS;

  const guildChannels = Object.values(channels).filter((c) => c.guildId === activeGuildId);
  const categories = guildChannels.filter((c) => c.type === 4).sort((a, b) => (a.position || 0) - (b.position || 0));
  const topLevel = guildChannels.filter((c) => c.type !== 4 && !c.parentId);
  const textChannels = topLevel.filter((c) => c.type === 0 || c.type === 5);
  const voiceChannels = topLevel.filter((c) => c.type === 2);

  const voiceUserCount = (channelId: string) =>
    Object.values(voiceStates).filter((vs) => vs.channelId === channelId).length;

  const leaveGuild = async () => {
    if (!guild || !activeGuildId) return;
    if (isOwner) {
      alert('你是该服务器的所有者，不能离开。可在「服务器设置 → 概览」中删除服务器。');
      setShowGuildMenu(false);
      return;
    }
    if (!window.confirm(`确定离开服务器「${guild.name}」?`)) { setShowGuildMenu(false); return; }
    try {
      await guildApi.leave(activeGuildId);
      removeGuild(activeGuildId);
      setActiveGuild(null);
      setActiveChannel(null);
      setSidebar('friends');
    } catch (err: any) {
      alert(err.response?.data?.error || '操作失败');
    }
    setShowGuildMenu(false);
  };

  // 点菜单外部关闭
  useEffect(() => {
    if (!showGuildMenu) return;
    const handler = (e: MouseEvent) => {
      if (guildMenuRef.current && !guildMenuRef.current.contains(e.target as Node)) setShowGuildMenu(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showGuildMenu]);

  const openMenu = (e: React.MouseEvent, channelId: string) => {
    e.preventDefault();
    e.stopPropagation();
    setMenu({ channelId, x: e.clientX, y: e.clientY });
  };

  const renameChannel = async (channelId: string) => {
    const ch = channels[channelId];
    const name = window.prompt('重命名频道', ch?.name);
    if (name === null || !name.trim()) { setMenu(null); return; }
    try {
      const updated = await channelApi.update(channelId, { name: name.trim() });
      updateChannel(updated);
    } catch (err: any) {
      alert(err.response?.data?.error || '操作失败');
    }
    setMenu(null);
  };

  const deleteChannel = async (channelId: string) => {
    const ch = channels[channelId];
    if (!window.confirm(`确定删除频道 #${ch?.name}?`)) { setMenu(null); return; }
    try {
      await channelApi.delete(channelId);
      removeChannel(channelId);
    } catch (err: any) {
      alert(err.response?.data?.error || '操作失败');
    }
    setMenu(null);
  };

  const renderChannelItem = (ch: any, indent = false) => {
    const isVoice = ch.type === 2;
    const userCount = isVoice ? voiceUserCount(ch.id) : 0;
    const unread = unreadCount[ch.id] || 0;
    return (
      <div
        key={ch.id}
        className={`channel-item ${isVoice ? 'voice-channel' : ''} ${activeChannelId === ch.id ? 'active' : ''} ${indent ? 'indented' : ''}`}
        onClick={() => setActiveChannel(ch.id)}
        onContextMenu={(e) => canManage && openMenu(e, ch.id)}
        title={ch.topic || ch.name}
      >
        <span className="channel-prefix">
          {isVoice ? (
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm-7 3c0 3 2.5 5 7 5s7-2 7-5h-2c0 2-2 3-5 3s-5-1-5-3H5z"/>
            </svg>
          ) : (
            '#'
          )}
        </span>
        <span className="channel-name">{ch.name}</span>
        {userCount > 0 && <span className="voice-user-count">{userCount}</span>}
        {!isVoice && unread > 0 && activeChannelId !== ch.id && (
          <span className="unread-badge">{unread > 99 ? '99+' : unread}</span>
        )}
        {canManage && (
          <button
            className="channel-settings"
            title="设置"
            onClick={(e) => {
              e.stopPropagation();
              setMenu({ channelId: ch.id, x: e.clientX, y: e.clientY });
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/>
            </svg>
          </button>
        )}
      </div>
    );
  };

  const renderSection = (title: string, items: any[], createType: number | null) => (
    <div className="sidebar-section">
      <div className="sidebar-section-title">
        <span>{title}</span>
        {canManage && createType !== null && (
          <button
            className="sidebar-add-btn"
            title={`创建${createType === 2 ? '语音频道' : createType === 4 ? '分类' : '文字频道'}`}
            onClick={() => setCreateFor({ type: createType })}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/>
            </svg>
          </button>
        )}
      </div>
      {items.map((ch) => renderChannelItem(ch))}
    </div>
  );

  return (
    <div className="channel-sidebar">
      <div className="sidebar-header" ref={guildMenuRef}>
        <h3>{guild.name}</h3>
        {/* 显式的服务器菜单:此前"服务器设置/离开服务器"只能右键图标触发,界面上无从发现 */}
        <button
          className="sidebar-more-btn"
          title="服务器菜单"
          onClick={() => setShowGuildMenu((v) => !v)}
        >
          ︙
        </button>
        {showGuildMenu && (
          <div className="guild-menu">
            <button className="guild-menu-item" onClick={() => { setShowInvite(true); setShowGuildMenu(false); }}>
              🔗 邀请好友
            </button>
            {canManage && (
              <button className="guild-menu-item" onClick={() => { setCreateFor({ type: 0 }); setShowGuildMenu(false); }}>
                ➕ 创建频道
              </button>
            )}
            <button className="guild-menu-item" onClick={() => { setShowSettings(true); setShowGuildMenu(false); }}>
              ⚙️ 服务器设置
            </button>
            {!isOwner && (
              <button className="guild-menu-item danger" onClick={leaveGuild}>
                🚪 离开服务器
              </button>
            )}
          </div>
        )}
      </div>

      <div className="sidebar-scroll">
        {/* 分类组 */}
        {categories.map((cat) => {
          const children = guildChannels
            .filter((c) => c.parentId === cat.id && c.type !== 4)
            .sort((a, b) => (a.position || 0) - (b.position || 0));
          const isCollapsed = !!collapsed[cat.id];
          return (
            <div key={cat.id} className="sidebar-section">
              <div
                className="sidebar-section-title category-title"
                onClick={() => setCollapsed({ ...collapsed, [cat.id]: !isCollapsed })}
              >
                <span className={`category-arrow ${isCollapsed ? 'collapsed' : ''}`}>▾</span>
                <span>{cat.name}</span>
                {canManage && (
                  <button
                    className="sidebar-add-btn"
                    title="在此分类下创建频道"
                    onClick={(e) => { e.stopPropagation(); setCreateFor({ type: 0, parentId: cat.id }); }}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/>
                    </svg>
                  </button>
                )}
              </div>
              {!isCollapsed && children.map((ch) => renderChannelItem(ch, true))}
            </div>
          );
        })}

        {/* 顶层文字频道 */}
        {textChannels.length > 0 && renderSection('文字频道', textChannels, 0)}

        {/* 顶层语音频道 */}
        {voiceChannels.length > 0 && renderSection('语音频道', voiceChannels, 2)}

        {/* 空状态:没有任何频道时给一个创建入口 */}
        {guildChannels.length === 0 && canManage && (
          <div className="sidebar-empty" onClick={() => setCreateFor({ type: 0 })}>
            这个服务器还没有频道，点击创建第一个
          </div>
        )}
      </div>

      {showSettings && activeGuildId && (
        <GuildSettingsModal guildId={activeGuildId} onClose={() => setShowSettings(false)} />
      )}

      {showInvite && activeGuildId && (
        <div className="modal-overlay" onClick={() => setShowInvite(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3>🔗 邀请好友</h3>
              <button className="modal-close" onClick={() => setShowInvite(false)}>✕</button>
            </div>
            <InvitesTab guildId={activeGuildId} />
          </div>
        </div>
      )}

      {permChannelId && activeGuildId && (
        <ChannelPermissionsModal
          channelId={permChannelId}
          guildId={activeGuildId}
          onClose={() => setPermChannelId(null)}
        />
      )}

      {createFor && activeGuildId && (
        <CreateChannelModal
          guildId={activeGuildId}
          defaultType={createFor.type}
          defaultParentId={createFor.parentId}
          onClose={() => setCreateFor(null)}
        />
      )}

      {menu && (
        <div className="context-menu" ref={menuRef} style={{ top: menu.y, left: menu.x }}>
          <button className="context-menu-item" onClick={() => { setPermChannelId(menu.channelId); setMenu(null); }}>
            ⚙️ 权限设置
          </button>
          <button className="context-menu-item" onClick={() => renameChannel(menu.channelId)}>
            ✏️ 重命名
          </button>
          <button className="context-menu-item danger" onClick={() => deleteChannel(menu.channelId)}>
            🗑️ 删除频道
          </button>
        </div>
      )}
    </div>
  );
};

export default ChannelSidebar;
