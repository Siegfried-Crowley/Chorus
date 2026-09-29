import React, { useEffect, useState } from 'react';
import { useStore } from '../store';
import { guildApi, dmApi } from '../utils/api';
import GuildSidebar from './guild/GuildSidebar';
import ChannelSidebar from './guild/ChannelSidebar';
import DmSidebar from './guild/DmSidebar';
import MemberList from './guild/MemberList';
import ChatArea from './chat/ChatArea';
import UserPanel from './common/UserPanel';
import VoicePanel from './voice/VoicePanel';
import FriendsPage from './friends/FriendsPage';
import CreateGuildModal from './guild/CreateGuildModal';

const normalizeRole = (r: any) => ({
  id: r.id,
  guildId: r.guildId ?? r.guild_id,
  name: r.name,
  color: r.color,
  hoist: !!r.hoist,
  position: r.position || 0,
  permissions: r.permissions?.toString?.() || '0',
  mentionable: !!r.mentionable,
});

const MainLayout: React.FC = () => {
  const {
    activeGuildId, activeChannelId, sidebar, channels,
    setGuilds, setChannels, setMembers, setRoles, setDmChannels, activeDmId,
  } = useStore();
  const [showCreateGuild, setShowCreateGuild] = useState(false);

  // 加载公会列表
  useEffect(() => {
    guildApi.list().then(setGuilds).catch(console.error);
  }, [setGuilds]);

  // 进入私信视图时加载 DM 列表
  useEffect(() => {
    if (sidebar === 'dms') {
      dmApi.list().then(setDmChannels).catch(console.error);
    }
  }, [sidebar, setDmChannels]);

  // 当选中公会时，加载频道、成员和角色
  useEffect(() => {
    if (!activeGuildId) return;
    guildApi.getChannels(activeGuildId)
      .then((channels: any[]) => setChannels(activeGuildId, channels))
      .catch(console.error);
    guildApi.getMembers(activeGuildId)
      .then((members: any[]) => setMembers(activeGuildId, members))
      .catch(console.error);
    guildApi.getRoles(activeGuildId)
      .then((roles: any[]) => setRoles(activeGuildId, roles.map(normalizeRole)))
      .catch(console.error);
  }, [activeGuildId, setChannels, setMembers, setRoles]);

  const showFriends = sidebar === 'friends' || (!activeGuildId && sidebar !== 'dms');
  const showDms = sidebar === 'dms';
  const showGuild = !showFriends && !showDms;
  // 当前选中的是否为语音频道：是则渲染语音面板
  const activeChannel = activeChannelId ? channels[activeChannelId] : null;
  const isVoiceChannel = activeChannel?.type === 2;

  return (
    <div className={`app-layout ${showGuild ? 'has-members' : ''}`}>
      {/* 左侧：服务器图标栏 */}
      <GuildSidebar onCreateGuild={() => setShowCreateGuild(true)} />

      {/* 第二列：频道/好友/DM 列表 */}
      {showDms ? (
        <DmSidebar />
      ) : showGuild ? (
        <ChannelSidebar />
      ) : (
        <div className="channel-sidebar">
          <div className="sidebar-header">
            <h3>好友</h3>
          </div>
          <div className="sidebar-section">
            <div className="sidebar-section-title">在线好友</div>
          </div>
        </div>
      )}

      {/* 主内容区 */}
      {showFriends ? <FriendsPage /> : <ChatArea />}

      {/* 成员列表(仅服务器视图) */}
      {showGuild && <MemberList />}

      {/* 底部用户控制面板 */}
      <UserPanel />

      {/* 语音面板 */}
      {isVoiceChannel && <VoicePanel />}

      {/* 创建公会 Modal */}
      {showCreateGuild && (
        <CreateGuildModal onClose={() => setShowCreateGuild(false)} />
      )}
    </div>
  );
};

export default MainLayout;
