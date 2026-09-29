import { Message, useStore } from '../store';

/**
 * 后端消息 JSON → store Message(camelCase)。
 * REST 响应已在 api.ts 统一转 camelCase，这里对 snake_case 旧字段做兜底兼容。
 */
export const toMessage = (data: any): Message => ({
  id: data.id,
  channelId: data.channelId ?? data.channel_id,
  guildId: data.guildId ?? data.guild_id ?? undefined,
  authorId: data.authorId ?? data.author_id,
  content: data.content || '',
  timestamp: data.timestamp,
  editedTimestamp: data.editedTimestamp ?? data.edited_timestamp ?? undefined,
  type: data.type || 0,
  nonce: data.nonce ?? undefined,
  flags: data.flags || 0,
  attachments: (data.attachments || []).map((a: any) => ({
    ...a,
    // 内层双兼容:REST 经 deepCamel 后为 contentType,网关直传为 content_type
    content_type: a?.content_type ?? a?.contentType ?? null,
  })),
  embeds: data.embeds || [],
  reactions: data.reactions || {},
  mentions: data.mentions
    ? { ...data.mentions, userIds: data.mentions.userIds ?? data.mentions.user_ids ?? [] }
    : { everyone: false, userIds: [] },
  mentionEveryone: !!(data.mentionEveryone ?? data.mention_everyone),
  pinned: !!data.pinned,
  messageReference: (() => {
    const ref = data.messageReference ?? data.message_reference;
    if (!ref || typeof ref !== 'object') return null;
    return {
      message_id: ref.message_id ?? ref.messageId ?? null,
      channel_id: ref.channel_id ?? ref.channelId ?? null,
    };
  })(),
});

/**
 * 成员对象 → 显示名(唯一入口)。
 * 回退顺序：公会昵称 → 显示名称 → 用户名 → 用户+ID后4位。
 * 所有渲染成员名的地方都应调用它，避免各处各写一份回退链而漏改。
 */
export const memberLabel = (m: {
  nickname?: string | null;
  globalName?: string | null;
  username?: string | null;
  userId?: string | null;
} | null | undefined): string => {
  if (!m) return '未知用户';
  return m.nickname || m.globalName || m.username || `用户 ${String(m.userId ?? '').slice(-4)}`;
};

/** 用户显示名：当前用户→用户名；公会成员→昵称；否则 用户+id 尾4位 */
export const displayName = (userId: string | null | undefined): string => {
  if (userId == null || userId === '') return '未知用户';
  const st = useStore.getState();
  if (userId === st.currentUser?.id) return st.currentUser.username;
  const member = st.activeGuildId ? st.members[st.activeGuildId]?.[userId] : null;
  // 依次回退:公会昵称 → 显示名 → 用户名 → 尾号兜底(避免直接暴露雪花 ID)
  return member?.nickname || member?.globalName || member?.username || `用户 ${userId.slice(-4)}`;
};
