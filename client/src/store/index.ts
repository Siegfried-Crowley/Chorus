import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';

// ===== Types =====
export interface User {
  id: string;
  username: string;
  discriminator: string;
  globalName?: string;
  email?: string;
  avatar?: string;
  banner?: string;
  accentColor?: number;
  aboutMe?: string;
  locale: string;
  verified: boolean;
  mfaEnabled?: boolean;
  flags: number;
  premiumType: number;
}

export interface Guild {
  id: string;
  name: string;
  icon?: string;
  ownerId: string;
  memberCount: number;
}

export interface Channel {
  id: string;
  guildId?: string;
  name: string;
  type: number; // 0:text, 2:voice, 4:category
  position?: number;
  parentId?: string;
  bitrate?: number;
  topic?: string;
}

export interface MessageReference {
  message_id?: string;
  channel_id?: string;
}

export interface Message {
  id: string;
  channelId: string;
  guildId?: string;
  authorId: string;
  content: string;
  timestamp: string;
  editedTimestamp?: string;
  type: number;
  nonce?: string;
  flags?: number;
  attachments?: { url: string; filename: string; content_type?: string | null; size?: number }[];
  embeds?: any[];
  reactions?: Record<string, string[]>;   // emoji -> userId[]
  mentions?: { everyone: boolean; user_ids: string[] };
  mentionEveryone?: boolean;
  pinned?: boolean;
  messageReference?: MessageReference | null;
  highlight?: boolean;  // 搜索结果/置顶跳转后短暂高亮
}

export interface GuildMember {
  userId: string;
  nickname?: string;
  joinedAt: string;
  roles: string[];
  /** 用户资料(服务端 /guilds/{id}/members 一并返回,用于无昵称时显示用户名) */
  username?: string;
  globalName?: string;
  discriminator?: string;
  avatar?: string;
}

// 后端成员 JSON 为 snake_case 且可能缺省 roles，统一归一化后再入 store
const normalizeMember = (m: any): GuildMember => ({
  userId: String(m.userId ?? m.user_id ?? ''),
  nickname: m.nickname ?? undefined,
  joinedAt: String(m.joinedAt ?? m.joined_at ?? ''),
  roles: Array.isArray(m.roles) ? m.roles : [],
  username: m.username ?? undefined,
  globalName: m.globalName ?? m.global_name ?? undefined,
  discriminator: m.discriminator ?? undefined,
  avatar: m.avatar ?? undefined,
});

export interface Role {
  id: string;
  guildId: string;
  name: string;
  color?: number;
  hoist: boolean;
  position: number;
  permissions: string;
  mentionable: boolean;
}

export interface VoiceStateData {
  guildId: string;
  channelId: string;
  userId: string;
  sessionId: string;
  selfMute: boolean;
  selfDeaf: boolean;
  mute: boolean;
  deaf: boolean;
}

/** 远端视频推流状态:谁在推摄像头/屏幕共享 */
export interface VideoSenderState {
  camera: boolean;
  screen: boolean;
}

export interface Relationship {
  id: string;
  type: number; // 1:friend, 2:blocked, 3:incoming, 4:outgoing
  username: string;
  discriminator: string;
  avatar?: string;
  globalName?: string;
}

export interface DmChannel {
  id: string;
  type: number; // 1
  name?: string | null;
  icon?: string | null;
  lastMessageId?: string | null;
  lastMessage?: string | null;
  // 旧 snake 形态兼容(网关直传或旧缓存)
  last_message_id?: string | null;
  last_message?: string | null;
  recipient?: {
    id: string;
    username: string;
    discriminator: string;
    avatar?: string | null;
    global_name?: string | null;
  } | null;
}

// ===== Store =====
interface AppState {
  // Auth
  token: string | null;
  currentUser: User | null;

  // Connection
  gatewayStatus: 'disconnected' | 'connecting' | 'ready' | 'resuming';
  ping: number;

  // Data (normalized)
  guilds: Record<string, Guild>;
  channels: Record<string, Channel>;
  roles: Record<string, Record<string, Role>>;            // guildId -> roleId -> role
  members: Record<string, Record<string, GuildMember>>;  // guildId -> userId -> member
  messages: Record<string, Message[]>;                    // channelId -> messages[]
  relationships: Relationship[];
  dmChannels: DmChannel[];                                // 私信频道列表
  voiceStates: Record<string, VoiceStateData>;            // userId -> state
  videoSenders: Record<string, VideoSenderState>;         // userId -> {camera, screen}
  presences: Record<string, { status: string; activities: any[] }>;

  // 未读 + 输入中
  unreadCount: Record<string, number>;                    // channelId -> 未读数
  lastReadId: Record<string, string>;                     // channelId -> 已读到的消息 id
  typingUsers: Record<string, Record<string, number>>;    // channelId -> userId -> 过期时间戳

  // UI
  activeGuildId: string | null;
  activeChannelId: string | null;
  sidebar: 'friends' | 'guilds' | 'dms';
  status: string;                         // 当前用户在线状态
  isVoiceConnected: boolean;
  isMuted: boolean;
  isDeafened: boolean;
  speakingUsers: Set<string>;
  activeDmId: string | null;

  // Actions
  setToken: (token: string | null) => void;
  setCurrentUser: (user: User | null) => void;
  setGatewayStatus: (status: AppState['gatewayStatus']) => void;
  setPing: (ping: number) => void;

  // Guild actions
  addGuild: (guild: Guild) => void;
  setGuilds: (guilds: Guild[]) => void;
  updateGuild: (guild: Partial<Guild> & { id: string }) => void;
  removeGuild: (guildId: string) => void;

  // Role actions
  setRoles: (guildId: string, roles: Role[]) => void;
  addRole: (guildId: string, role: Role) => void;
  updateRole: (guildId: string, role: Role) => void;
  removeRole: (guildId: string, roleId: string) => void;

  // Channel actions
  addChannel: (channel: Channel) => void;
  setChannels: (guildId: string, channels: Channel[]) => void;
  updateChannel: (channel: Partial<Channel> & { id: string }) => void;
  removeChannel: (channelId: string) => void;

  // Message actions
  addMessage: (channelId: string, msg: Message) => void;
  updateMessage: (channelId: string, msg: Message) => void;
  removeMessage: (channelId: string, msgId: string) => void;
  setMessages: (channelId: string, msgs: Message[]) => void;
  addOptimisticMessage: (channelId: string, msg: Message) => void;
  replaceOptimisticMessage: (channelId: string, tempId: string, real: Message) => void;

  // Unread / typing
  markChannelRead: (channelId: string) => void;
  incrementUnread: (channelId: string) => void;
  setTyping: (channelId: string, userId: string) => void;
  clearTyping: (channelId: string, userId: string) => void;

  // Member actions
  setMembers: (guildId: string, members: GuildMember[]) => void;
  addGuildMember: (guildId: string, member: GuildMember) => void;
  removeGuildMember: (guildId: string, userId: string) => void;
  updateGuildMember: (guildId: string, member: Partial<GuildMember> & { userId: string }) => void;

  // Relationship actions
  setRelationships: (rels: Relationship[]) => void;

  // DM actions
  setDmChannels: (dms: DmChannel[]) => void;
  setActiveDmId: (id: string | null) => void;

  // Voice actions
  setVoiceState: (state: VoiceStateData) => void;
  setVideoSenders: (map: Record<string, VideoSenderState>) => void;
  removeVoiceState: (userId: string) => void;
  setSpeaking: (userId: string, speaking: boolean) => void;
  setVoiceConnected: (connected: boolean) => void;
  setMuted: (muted: boolean) => void;
  setDeafened: (deafened: boolean) => void;

  // UI actions
  setActiveGuild: (guildId: string | null) => void;
  setActiveChannel: (channelId: string | null) => void;
  setSidebar: (sidebar: AppState['sidebar']) => void;
  setPresence: (status: string) => void;

  // Presence
  updatePresence: (userId: string, presence: { status: string; activities?: any[] }) => void;
}

export const useStore = create<AppState>()(
  immer((set, get) => ({
    // Initial state
    token: localStorage.getItem('discord_token'),
    currentUser: null,
    gatewayStatus: 'disconnected',
    ping: 0,
    guilds: {},
    channels: {},
    roles: {},
    members: {},
    messages: {},
    relationships: [],
    dmChannels: [],
    voiceStates: {},
    videoSenders: {},
    presences: {},
    unreadCount: {},
    lastReadId: {},
    typingUsers: {},
    activeGuildId: null,
    activeChannelId: null,
    sidebar: 'friends',
    status: 'online',
    isVoiceConnected: false,
    isMuted: false,
    isDeafened: false,
    speakingUsers: new Set(),
    activeDmId: null,

    // Auth
    setToken: (token) => {
      set((state) => { state.token = token; });
      if (token) localStorage.setItem('discord_token', token);
      else localStorage.removeItem('discord_token');
    },
    setCurrentUser: (user) => set((state) => {
      // /auth/me 的 DTO 里 id 是数字,而 guild.ownerId 等均为字符串,统一转字符串避免严格比较失败
      state.currentUser = user
        ? { ...user, id: String(user.id), discriminator: user.discriminator ?? '0000' }
        : null;
    }),
    setGatewayStatus: (status) => set((state) => { state.gatewayStatus = status; }),
    setPing: (ping) => set((state) => { state.ping = ping; }),

    // Guilds
    addGuild: (guild) => set((state) => { state.guilds[guild.id] = guild; }),
    setGuilds: (guilds) => set((state) => {
      state.guilds = {};
      guilds.forEach((g) => { state.guilds[g.id] = g; });
    }),
    updateGuild: (guild) => set((state) => {
      if (state.guilds[guild.id]) state.guilds[guild.id] = { ...state.guilds[guild.id], ...guild };
    }),
    removeGuild: (guildId) => set((state) => {
      delete state.guilds[guildId];
      delete state.roles[guildId];
      delete state.members[guildId];
      // 删除该公会的频道 + 消息
      Object.keys(state.channels).forEach((k) => {
        if (state.channels[k].guildId === guildId) {
          delete state.messages[k];
          delete state.channels[k];
        }
      });
      // 语音状态
      Object.keys(state.voiceStates).forEach((k) => {
        if (state.voiceStates[k].guildId === guildId) delete state.voiceStates[k];
      });
      if (state.activeGuildId === guildId) state.activeGuildId = null;
      if (state.activeChannelId && !state.channels[state.activeChannelId]) state.activeChannelId = null;
    }),

    // Roles
    setRoles: (guildId, roles) => set((state) => {
      state.roles[guildId] = {};
      roles.forEach((r) => { state.roles[guildId][r.id] = r; });
    }),
    addRole: (guildId, role) => set((state) => {
      if (!state.roles[guildId]) state.roles[guildId] = {};
      state.roles[guildId][role.id] = role;
    }),
    updateRole: (guildId, role) => set((state) => {
      if (state.roles[guildId] && state.roles[guildId][role.id]) {
        state.roles[guildId][role.id] = { ...state.roles[guildId][role.id], ...role };
      }
    }),
    removeRole: (guildId, roleId) => set((state) => {
      if (state.roles[guildId]) delete state.roles[guildId][roleId];
      // 从成员的 roles 数组移除
      const members = state.members[guildId];
      if (members) {
        Object.values(members).forEach((m) => {
          if (m.roles.includes(roleId)) {
            m.roles = m.roles.filter((r) => r !== roleId);
          }
        });
      }
    }),

    // Channels
    addChannel: (channel) => set((state) => { state.channels[channel.id] = channel; }),
    setChannels: (guildId, channels) => set((state) => {
      // 只替换该 guild 的频道
      const oldKeys = Object.keys(state.channels).filter(
        (k) => state.channels[k].guildId === guildId
      );
      oldKeys.forEach((k) => delete state.channels[k]);
      channels.forEach((c) => { state.channels[c.id] = c; });
    }),
    updateChannel: (channel) => set((state) => {
      if (state.channels[channel.id]) {
        state.channels[channel.id] = { ...state.channels[channel.id], ...channel };
      }
    }),
    removeChannel: (channelId) => set((state) => {
      delete state.channels[channelId];
      delete state.messages[channelId];
      delete state.unreadCount[channelId];
      delete state.lastReadId[channelId];
      delete state.typingUsers[channelId];
      if (state.activeChannelId === channelId) state.activeChannelId = null;
    }),

    // Messages
    addMessage: (channelId, msg) => set((state) => {
      if (!state.messages[channelId]) state.messages[channelId] = [];
      // 去重
      const exists = state.messages[channelId].some((m) => m.id === msg.id);
      if (!exists) {
        state.messages[channelId].unshift(msg);
        // 限制 200 条
        if (state.messages[channelId].length > 200) {
          state.messages[channelId] = state.messages[channelId].slice(0, 200);
        }
      }
    }),
    updateMessage: (channelId, msg) => set((state) => {
      const msgs = state.messages[channelId];
      if (msgs) {
        const idx = msgs.findIndex((m) => m.id === msg.id);
        if (idx >= 0) msgs[idx] = msg;
      }
    }),
    removeMessage: (channelId, msgId) => set((state) => {
      if (state.messages[channelId]) {
        state.messages[channelId] = state.messages[channelId].filter(
          (m) => m.id !== msgId
        );
      }
    }),
    setMessages: (channelId, msgs) => set((state) => {
      state.messages[channelId] = msgs;
    }),
    addOptimisticMessage: (channelId, msg) => set((state) => {
      if (!state.messages[channelId]) state.messages[channelId] = [];
      state.messages[channelId].unshift(msg);
    }),
    replaceOptimisticMessage: (channelId, tempId, real) => set((state) => {
      const msgs = state.messages[channelId];
      if (!msgs) return;
      const idx = msgs.findIndex((m) => m.id === tempId);
      if (idx < 0) return;
      // 自己发的消息会同时经 REST 响应与 Gateway 推送到达:若网关已先写入同一条
      // 真实消息,则只移除临时条目(否则会渲染出两条相同消息并触发 React key 冲突)
      const alreadyArrived = msgs.some((m, i) => i !== idx && m.id === real.id);
      if (alreadyArrived) {
        msgs.splice(idx, 1);
        return;
      }
      msgs[idx] = real;
    }),

    // Unread / typing
    markChannelRead: (channelId) => set((state) => {
      state.unreadCount[channelId] = 0;
      const msgs = state.messages[channelId];
      if (msgs && msgs.length) state.lastReadId[channelId] = msgs[0].id;
    }),
    incrementUnread: (channelId) => set((state) => {
      state.unreadCount[channelId] = (state.unreadCount[channelId] || 0) + 1;
    }),
    setTyping: (channelId, userId) => set((state) => {
      if (!state.typingUsers[channelId]) state.typingUsers[channelId] = {};
      state.typingUsers[channelId][userId] = Date.now() + 6000;
    }),
    clearTyping: (channelId, userId) => set((state) => {
      if (state.typingUsers[channelId]) delete state.typingUsers[channelId][userId];
    }),

    // Members
    setMembers: (guildId, members) => set((state) => {
      state.members[guildId] = {};
      members.forEach((m) => {
        const nm = normalizeMember(m);
        state.members[guildId][nm.userId] = nm;
      });
    }),
    addGuildMember: (guildId, member) => set((state) => {
      if (!state.members[guildId]) state.members[guildId] = {};
      const nm = normalizeMember(member);
      const existed = !!state.members[guildId][nm.userId];
      state.members[guildId][nm.userId] = nm;
      if (!existed && state.guilds[guildId]) state.guilds[guildId].memberCount++;
    }),
    removeGuildMember: (guildId, userId) => set((state) => {
      if (state.members[guildId]) {
        delete state.members[guildId][userId];
        if (state.guilds[guildId]) {
          state.guilds[guildId].memberCount = Math.max(0, state.guilds[guildId].memberCount - 1);
        }
      }
      delete state.voiceStates[userId];
    }),
    updateGuildMember: (guildId, member) => set((state) => {
      const m = state.members[guildId]?.[member.userId ?? (member as any).user_id];
      if (m) {
        const nm = normalizeMember({ ...m, ...member });
        state.members[guildId][nm.userId] = nm;
      }
    }),

    // Relationships
    setRelationships: (rels) => set((state) => { state.relationships = rels; }),

    // DM
    setDmChannels: (dms) => set((state) => {
      state.dmChannels = dms;
      // 把 DM 频道也并入 channels，让聊天组件按 channelId 直接工作
      dms.forEach((dm) => {
        if (!state.channels[dm.id]) {
          state.channels[dm.id] = {
            id: dm.id,
            name: dm.recipient?.username || '私信',
            type: 1,
            guildId: undefined,
          };
        }
      });
    }),
    setActiveDmId: (id) => set((state) => { state.activeDmId = id; }),

    // Voice
    setVoiceState: (voiceState) => set((state) => {
      // 脏数据防护:userId 缺失的广播直接忽略(否则 voiceStates["undefined"] 会让渲染崩溃)
      if (!voiceState || voiceState.userId == null || String(voiceState.userId).length === 0) return;
      const userId = String(voiceState.userId);
      state.voiceStates[userId] = { ...voiceState, userId };
    }),
    removeVoiceState: (userId) => set((state) => {
      if (userId == null || userId === '') return;
      delete state.voiceStates[userId];
    }),
    setVideoSenders: (map) => set((state) => {
      state.videoSenders = map;
    }),
    setSpeaking: (userId, speaking) => set((state) => {
      if (speaking) state.speakingUsers.add(userId);
      else state.speakingUsers.delete(userId);
      // 触发重渲染需创建新 Set
      state.speakingUsers = new Set(state.speakingUsers);
    }),
    setVoiceConnected: (connected) => set((state) => { state.isVoiceConnected = connected; }),
    setMuted: (muted) => set((state) => { state.isMuted = muted; }),
    setDeafened: (deafened) => set((state) => { state.isDeafened = deafened; }),

    // UI
    setActiveGuild: (guildId) => set((state) => { state.activeGuildId = guildId; }),
    setActiveChannel: (channelId) => set((state) => {
      state.activeChannelId = channelId;
      // 切到该频道即视为已读
      if (channelId) {
        state.unreadCount[channelId] = 0;
        const msgs = state.messages[channelId];
        if (msgs && msgs.length) state.lastReadId[channelId] = msgs[0].id;
      }
    }),
    setSidebar: (sidebar) => set((state) => { state.sidebar = sidebar; }),
    setPresence: (status) => set((state) => { state.status = status; }),

    // Presence
    updatePresence: (userId, presence) => set((state) => {
      state.presences[userId] = {
        status: presence.status,
        activities: presence.activities || [],
      };
    }),
  }))
);

