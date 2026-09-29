import axios from 'axios';
import { deepCamel } from './case';

const API_BASE = '';

const api = axios.create({
  baseURL: `${API_BASE}/api`,
  headers: { 'Content-Type': 'application/json' },
});

// 自动携带 JWT token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('discord_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 401 时清除 token；响应体统一 snake_case → camelCase
api.interceptors.response.use(
  (res) => {
    if (res.data && typeof res.data === 'object') {
      res.data = deepCamel(res.data);
    }
    return res;
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('discord_token');
      window.location.hash = '#/login';
    }
    return Promise.reject(err);
  }
);

export default api;

// ===== Auth =====
export const authApi = {
  register: (data: { username: string; email: string; password: string }) =>
    api.post('/auth/register', data).then((r) => r.data),
  login: (data: { email: string; password: string }) =>
    api.post('/auth/login', data).then((r) => r.data),
  verifyEmail: (data: { email: string; code: string }) =>
    api.post('/auth/verify-email', data).then((r) => r.data),
  verify2fa: (data: { mfaToken: string; code: string }) =>
    api.post('/auth/2fa/verify', data).then((r) => r.data),
  enable2fa: () => api.post('/auth/2fa/enable').then((r) => r.data),
  disable2fa: () => api.post('/auth/2fa/disable').then((r) => r.data),
  changePassword: (data: { oldPassword: string; newPassword: string }) =>
    api.post('/auth/change-password', data).then((r) => r.data),
  me: () => api.get('/auth/me').then((r) => r.data),
};

// ===== Guilds =====
export const guildApi = {
  create: (name: string) => api.post('/guilds', { name }).then((r) => r.data),
  list: () => api.get('/guilds').then((r) => r.data),
  get: (id: string) => api.get(`/guilds/${id}`).then((r) => r.data),
  getChannels: (id: string) => api.get(`/guilds/${id}/channels`).then((r) => r.data),
  getMembers: (id: string) => api.get(`/guilds/${id}/members`).then((r) => r.data),
  getRoles: (id: string) => api.get(`/guilds/${id}/roles`).then((r) => r.data),
  update: (id: string, data: { name?: string; icon?: string }) =>
    api.patch(`/guilds/${id}`, data).then((r) => r.data),
  remove: (id: string) => api.delete(`/guilds/${id}`),
  leave: (id: string) => api.delete(`/guilds/${id}/members/me`),
  updateMyNickname: (id: string, nickname: string) =>
    api.patch(`/guilds/${id}/members/me`, { nickname }).then((r) => r.data),
  updateMemberNickname: (id: string, userId: string, nickname: string) =>
    api.patch(`/guilds/${id}/members/${userId}`, { nickname }).then((r) => r.data),
  getMemberRoles: (id: string, userId: string) =>
    api.get(`/guilds/${id}/members/${userId}/roles`).then((r) => r.data),
  kick: (id: string, userId: string) =>
    api.put(`/guilds/${id}/members/${userId}/kick`),
  ban: (id: string, userId: string, reason?: string) =>
    api.put(`/guilds/${id}/members/${userId}/ban`, { reason }),
  unban: (id: string, userId: string) =>
    api.delete(`/guilds/${id}/bans/${userId}`),
  getBans: (id: string) => api.get(`/guilds/${id}/bans`).then((r) => r.data),
  createRole: (id: string, data: {
    name?: string; color?: number; hoist?: boolean; permissions?: string; mentionable?: boolean;
  }) => api.post(`/guilds/${id}/roles`, data).then((r) => r.data),
  updateRole: (id: string, roleId: string, data: {
    name?: string; color?: number; hoist?: boolean; permissions?: string; mentionable?: boolean;
  }) => api.patch(`/guilds/${id}/roles/${roleId}`, data).then((r) => r.data),
  deleteRole: (id: string, roleId: string) =>
    api.delete(`/guilds/${id}/roles/${roleId}`),
  assignRoles: (id: string, userId: string, roleIds: string[]) =>
    api.put(`/guilds/${id}/members/${userId}/roles`, { role_ids: roleIds }),
};

// ===== 邀请 =====
export const inviteApi = {
  create: (guildId: string, data?: { channel_id?: string; max_uses?: number; max_age?: number }) =>
    api.post(`/guilds/${guildId}/invites`, data || {}).then((r) => r.data),
  list: (guildId: string) => api.get(`/guilds/${guildId}/invites`).then((r) => r.data),
  remove: (code: string) => api.delete(`/invites/${code}`),
  preview: (code: string) => api.get(`/invites/${code}`).then((r) => r.data),
  join: (code: string) => api.post(`/invites/${code}/join`, {}).then((r) => r.data),
};

// ===== 用户资料 =====
export const userApi = {
  updateProfile: (data: { username?: string; global_name?: string; about_me?: string }) =>
    api.patch('/users/me', data).then((r) => r.data),
  uploadAvatar: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post('/users/me/avatar', form, { headers: { 'Content-Type': 'multipart/form-data' } })
      .then((r) => r.data);
  },
  getProfile: (userId: string) => api.get(`/users/${userId}`).then((r) => r.data),
};

// ===== Channels =====
export const channelApi = {
  create: (data: { guild_id: string; name: string; type?: number; parent_id?: string }) =>
    api.post('/channels', data).then((r) => r.data),
  get: (id: string) => api.get(`/channels/${id}`).then((r) => r.data),
  update: (id: string, data: { name?: string; topic?: string; rate_limit?: number; position?: number; parent_id?: string }) =>
    api.patch(`/channels/${id}`, data).then((r) => r.data),
  delete: (id: string) => api.delete(`/channels/${id}`),
  listOverwrites: (id: string) => api.get(`/channels/${id}/permissions`).then((r) => r.data),
  createOverwrite: (id: string, data: { type: number; target_id: string; allow?: string; deny?: string }) =>
    api.post(`/channels/${id}/permissions`, data).then((r) => r.data),
  updateOverwrite: (id: string, overwriteId: string, data: { allow?: string; deny?: string }) =>
    api.put(`/channels/${id}/permissions/${overwriteId}`, data).then((r) => r.data),
  deleteOverwrite: (id: string, overwriteId: string) =>
    api.delete(`/channels/${id}/permissions/${overwriteId}`),
};

// ===== Messages =====
export const messageApi = {
  list: (channelId: string, params?: { limit?: number; before?: string; after?: string }) =>
    api.get(`/channels/${channelId}/messages`, { params }).then((r) => r.data),
  send: (channelId: string, data: {
    content: string; nonce?: string; attachments?: string; messageReference?: string;
  }) => api.post(`/channels/${channelId}/messages`, data).then((r) => r.data),
  edit: (channelId: string, messageId: string, content: string) =>
    api.patch(`/channels/${channelId}/messages/${messageId}`, { content }).then((r) => r.data),
  delete: (channelId: string, messageId: string) =>
    api.delete(`/channels/${channelId}/messages/${messageId}`),

  // 反应
  addReaction: (channelId: string, messageId: string, emoji: string) =>
    api.put(`/channels/${channelId}/messages/${messageId}/reactions/${encodeURIComponent(emoji)}`)
      .then((r) => r.data),
  removeReaction: (channelId: string, messageId: string, emoji: string) =>
    api.delete(`/channels/${channelId}/messages/${messageId}/reactions/${encodeURIComponent(emoji)}`)
      .then((r) => r.data),

  // 置顶
  getPins: (channelId: string) =>
    api.get(`/channels/${channelId}/messages/pins`).then((r) => r.data),
  pin: (channelId: string, messageId: string) =>
    api.put(`/channels/${channelId}/messages/pins/${messageId}`).then((r) => r.data),
  unpin: (channelId: string, messageId: string) =>
    api.delete(`/channels/${channelId}/messages/pins/${messageId}`).then((r) => r.data),

  // 输入中
  typing: (channelId: string) =>
    api.post(`/channels/${channelId}/messages/typing`, {}).catch(() => {}),

  // 搜索(公会内)
  search: (guildId: string, query: string, channelId?: string) =>
    api.get(`/guilds/${guildId}/messages/search`, { params: { query, channel_id: channelId } })
      .then((r) => r.data),
};

// ===== Friends =====
export const friendApi = {
  list: () => api.get('/friends').then((r) => r.data),
  sendRequest: (userId: string) => api.post('/friends/requests', { user_id: userId }),
  acceptRequest: (userId: string) => api.put(`/friends/requests/${userId}/accept`),
  rejectRequest: (userId: string) => api.put(`/friends/requests/${userId}/reject`),
  remove: (friendId: string) => api.delete(`/friends/${friendId}`),
  block: (userId: string) => api.put('/friends/blocks', { user_id: userId }),
  unblock: (blockedId: string) => api.delete(`/friends/blocks/${blockedId}`),
};

// ===== 附件上传 =====
export const uploadApi = {
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post('/uploads', form, { headers: { 'Content-Type': 'multipart/form-data' } })
      .then((r) => r.data);
  },
};

// ===== DM (私信) =====
export const dmApi = {
  list: () => api.get('/dm/channels').then((r) => r.data),
  open: (userId: string) => api.post('/dm/channels', { user_id: userId }).then((r) => r.data),
};

// ===== Voice =====
export const voiceApi = {
  join: (guildId: string, channelId: string, sessionId: string) =>
    api.post('/voice/join', { guild_id: guildId, channel_id: channelId, session_id: sessionId })
      .then((r) => r.data),
  leave: (guildId: string) =>
    api.post('/voice/leave', { guild_id: guildId }),
  mute: (guildId: string, mute: boolean) =>
    api.post('/voice/mute', { guild_id: guildId, mute }),
  deaf: (guildId: string, deaf: boolean) =>
    api.post('/voice/deaf', { guild_id: guildId, deaf }),
};
