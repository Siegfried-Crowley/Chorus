import React, { useEffect, useState } from 'react';
import { useStore } from '../store';
import { gatewayClient } from '../gateway/GatewayClient';
import { authApi, dmApi, friendApi, messageApi } from '../utils/api';
import { toMessage } from '../utils/message';
import LoginPage from './auth/LoginPage';
import MainLayout from './MainLayout';

// 后端网关事件的 JSON → store 类型转换
const toRole = (data: any) => ({
  id: data.id,
  guildId: data.guild_id,
  name: data.name,
  color: data.color,
  hoist: !!data.hoist,
  position: data.position || 0,
  permissions: data.permissions?.toString?.() || '0',
  mentionable: !!data.mentionable,
});

const toChannel = (data: any) => ({
  id: data.id,
  guildId: data.guild_id,
  name: data.name,
  type: data.type,
  position: data.position,
  parentId: data.parent_id ?? undefined,
  bitrate: data.bitrate,
  topic: data.topic,
});

const App: React.FC = () => {
  const { token, setToken, setCurrentUser, setGatewayStatus, currentUser } = useStore();
  const [loading, setLoading] = useState(true);

  // 检查登录状态
  useEffect(() => {
    if (token) {
      authApi.me()
        .then((user) => {
          // 刚登录时 currentUser 已由登录响应写入，这里不再用新对象覆盖，
          // 否则新引用会触发 gateway effect 二次执行（导致断开重连）
          if (!useStore.getState().currentUser) {
            setCurrentUser(user);
          }
        })
        .catch(() => {
          setToken(null);
        })
        .finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, [token, setToken, setCurrentUser]);

  // 连接 Gateway
  useEffect(() => {
    if (!token || !currentUser) return;

    setGatewayStatus('connecting');

    // 注册事件处理
    const unsubReady = gatewayClient.on('READY', (data: any) => {
      setGatewayStatus('ready');
      console.log('[App] Gateway Ready', data);
    });

    const unsubResumed = gatewayClient.on('RESUMED', () => {
      setGatewayStatus('ready');
    });

    const unsubMsg = gatewayClient.on('MESSAGE_CREATE', (data: any) => {
      const st = useStore.getState();
      const msg = toMessage(data);
      st.addMessage(msg.channelId, msg);
      // 不在当前频道 → 未读 +1
      if (msg.channelId !== st.activeChannelId) {
        st.incrementUnread(msg.channelId);
      }
      // DM 消息：刷新私信侧边栏预览
      if (!msg.guildId && st.sidebar === 'dms') {
        dmApi.list().then(st.setDmChannels).catch(console.error);
      }
    });

    // 消息反应(增/删) → 更新对应消息的 reactions
    const applyReaction = (data: any, remove: boolean) => {
      const st = useStore.getState();
      const msgs = st.messages[data.channel_id];
      if (!msgs) return;
      const idx = msgs.findIndex((m) => m.id === data.message_id);
      if (idx < 0) return;
      const reactions = { ...(msgs[idx].reactions || {}) };
      const users = [...(reactions[data.emoji] || [])];
      if (remove) {
        const next = users.filter((u) => u !== data.user_id);
        if (next.length) reactions[data.emoji] = next;
        else delete reactions[data.emoji];
      } else if (!users.includes(data.user_id)) {
        reactions[data.emoji] = [...users, data.user_id];
      }
      st.updateMessage(data.channel_id, { ...msgs[idx], reactions });
    };
    const unsubReactionAdd = gatewayClient.on('MESSAGE_REACTION_ADD', (data: any) => applyReaction(data, false));
    const unsubReactionRemove = gatewayClient.on('MESSAGE_REACTION_REMOVE', (data: any) => applyReaction(data, true));

    // 置顶更新 → 拉取最新置顶列表,同步各消息的 pinned 标记
    const unsubPins = gatewayClient.on('CHANNEL_PINS_UPDATE', (data: any) => {
      messageApi.getPins(data.channel_id)
        .then((pins: any[]) => {
          const st = useStore.getState();
          const ids = new Set(pins.map((p: any) => p.id));
          const msgs = st.messages[data.channel_id];
          if (msgs) {
            msgs.forEach((m) => {
              const target = ids.has(m.id);
              if (!!m.pinned !== target) st.updateMessage(data.channel_id, { ...m, pinned: target });
            });
          }
        })
        .catch(() => {});
    });

    // 输入中指示
    const unsubTyping = gatewayClient.on('TYPING_START', (data: any) => {
      const st = useStore.getState();
      if (data.user_id === st.currentUser?.id) return;
      st.setTyping(data.channel_id, data.user_id);
    });

    // 好友关系变化（接受请求等）：刷新好友列表
    const unsubRelSync = gatewayClient.on('RELATIONSHIPS_SYNC', (data: any) => {
      if (!Array.isArray(data.relationships)) return;
      useStore.getState().setRelationships(data.relationships.map((r: any) => ({
        id: String(r.id ?? ''),
        type: r.type ?? 1,
        username: r.username ?? '未知用户',
        discriminator: r.discriminator ?? '0000',
        avatar: r.avatar ?? undefined,
        globalName: r.globalName ?? r.global_name ?? undefined,
      })));
    });
    const unsubRelUpdate = gatewayClient.on('RELATIONSHIP_UPDATE', (data: any) => {
      // 收到新好友，刷新好友列表 + 若在 DM 视图刷新私信列表
      friendApi.list().then(useStore.getState().setRelationships).catch(console.error);
      dmApi.list().then(useStore.getState().setDmChannels).catch(console.error);
    });

    const unsubVoice = gatewayClient.on('VOICE_STATE_UPDATE', (data: any) => {
      const userId = data.user_id != null ? String(data.user_id) : '';
      if (!userId || data.guild_id == null) return;
      if (data.channel_id) {
        useStore.getState().setVoiceState({
          guildId: String(data.guild_id),
          channelId: String(data.channel_id),
          userId,
          sessionId: data.session_id ?? '',
          selfMute: !!data.self_mute,
          selfDeaf: !!data.self_deaf,
          mute: !!data.mute,
          deaf: !!data.deaf,
        });
      } else {
        useStore.getState().removeVoiceState(userId);
      }
    });

    const unsubPresence = gatewayClient.on('PRESENCE_UPDATE', (data: any) => {
      useStore.getState().updatePresence(data.user_id, {
        status: data.status,
        activities: data.activities,
      });
    });

    // ===== 公会/成员/角色/频道实时同步 =====
    const unsubGuildUpdate = gatewayClient.on('GUILD_UPDATE', (data: any) => {
      const st = useStore.getState();
      if (st.guilds[data.id]) {
        st.updateGuild({ id: data.id, name: data.name, icon: data.icon });
      }
    });
    const unsubGuildDelete = gatewayClient.on('GUILD_DELETE', (data: any) => {
      useStore.getState().removeGuild(data.id);
    });

    const unsubMemberAdd = gatewayClient.on('GUILD_MEMBER_ADD', (data: any) => {
      useStore.getState().addGuildMember(data.guild_id, {
        userId: data.user_id,
        joinedAt: data.joined_at || new Date().toISOString(),
        roles: data.roles || [],
      });
    });
    const unsubMemberUpdate = gatewayClient.on('GUILD_MEMBER_UPDATE', (data: any) => {
      const st = useStore.getState();
      if (data.roles) st.updateGuildMember(data.guild_id, { userId: data.user_id, roles: data.roles });
      if (data.nickname !== undefined) {
        st.updateGuildMember(data.guild_id, { userId: data.user_id, nickname: data.nickname });
      }
    });
    const unsubMemberRemove = gatewayClient.on('GUILD_MEMBER_REMOVE', (data: any) => {
      useStore.getState().removeGuildMember(data.guild_id, data.user_id);
    });

    const unsubRoleCreate = gatewayClient.on('ROLE_CREATE', (data: any) => {
      const st = useStore.getState();
      if (st.guilds[data.guild_id]) st.addRole(data.guild_id, toRole(data));
    });
    const unsubRoleUpdate = gatewayClient.on('ROLE_UPDATE', (data: any) => {
      const st = useStore.getState();
      if (st.roles[data.guild_id]) st.updateRole(data.guild_id, toRole(data));
    });
    const unsubRoleDelete = gatewayClient.on('ROLE_DELETE', (data: any) => {
      useStore.getState().removeRole(data.guild_id, data.id);
    });

    const unsubChannelCreate = gatewayClient.on('CHANNEL_CREATE', (data: any) => {
      useStore.getState().addChannel(toChannel(data));
    });
    const unsubChannelUpdate = gatewayClient.on('CHANNEL_UPDATE', (data: any) => {
      const st = useStore.getState();
      if (st.channels[data.id]) st.updateChannel(toChannel(data));
    });
    // 消息编辑/删除的实时同步(服务端 MessageController 广播)
    const unsubMsgUpdate = gatewayClient.on('MESSAGE_UPDATE', (data: any) => {
      const msg = toMessage(data);
      if (msg.channelId) useStore.getState().updateMessage(msg.channelId, msg);
    });
    const unsubMsgDelete = gatewayClient.on('MESSAGE_DELETE', (data: any) => {
      if (data.channel_id && data.id) {
        useStore.getState().removeMessage(String(data.channel_id), String(data.id));
      }
    });

    const unsubChannelDelete = gatewayClient.on('CHANNEL_DELETE', (data: any) => {
      useStore.getState().removeChannel(data.id);
    });

    // 连接
    gatewayClient.connect(token);

    return () => {
      unsubReady();
      unsubResumed();
      unsubMsg();
      unsubReactionAdd();
      unsubReactionRemove();
      unsubPins();
      unsubTyping();
      unsubRelSync();
      unsubRelUpdate();
      unsubVoice();
      unsubPresence();
      unsubGuildUpdate();
      unsubGuildDelete();
      unsubMemberAdd();
      unsubMemberUpdate();
      unsubMemberRemove();
      unsubRoleCreate();
      unsubRoleUpdate();
      unsubRoleDelete();
      unsubChannelCreate();
      unsubChannelUpdate();
      unsubChannelDelete();
      unsubMsgUpdate();
      unsubMsgDelete();
      gatewayClient.destroy();
    };
  }, [token, currentUser, setGatewayStatus]);

  if (loading) {
    return (
      <div className="loading-screen">
        <div className="loading-spinner" />
        <p>正在连接...</p>
      </div>
    );
  }

  if (!token || !currentUser) {
    return <LoginPage />;
  }

  return <MainLayout />;
};

export default App;
