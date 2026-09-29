// 权限位(与后端 PermissionService 64-bit 常量一一对应)
export const PERMS = {
  CREATE_INSTANT_INVITE: 1n << 0n,
  KICK_MEMBERS: 1n << 1n,
  BAN_MEMBERS: 1n << 2n,
  ADMINISTRATOR: 1n << 3n,
  MANAGE_CHANNELS: 1n << 4n,
  MANAGE_GUILD: 1n << 5n,
  ADD_REACTIONS: 1n << 6n,
  VIEW_AUDIT_LOG: 1n << 7n,
  PRIORITY_SPEAKER: 1n << 8n,
  STREAM: 1n << 9n,
  VIEW_CHANNEL: 1n << 10n,
  SEND_MESSAGES: 1n << 11n,
  SEND_TTS_MESSAGES: 1n << 12n,
  MANAGE_MESSAGES: 1n << 13n,
  EMBED_LINKS: 1n << 14n,
  ATTACH_FILES: 1n << 15n,
  READ_MESSAGE_HISTORY: 1n << 16n,
  MENTION_EVERYONE: 1n << 17n,
  CONNECT: 1n << 20n,
  SPEAK: 1n << 21n,
  MUTE_MEMBERS: 1n << 22n,
  DEAFEN_MEMBERS: 1n << 23n,
  MOVE_MEMBERS: 1n << 24n,
  USE_VAD: 1n << 25n,
  CHANGE_NICKNAME: 1n << 26n,
  MANAGE_NICKNAMES: 1n << 27n,
  MANAGE_ROLES: 1n << 28n,
  MANAGE_WEBHOOKS: 1n << 29n,
  MANAGE_GUILD_EXPRESSIONS: 1n << 30n,
  MODERATE_MEMBERS: 1n << 40n,
} as const;

export const PERM_LABELS: { bit: bigint; label: string }[] = [
  { bit: PERMS.CREATE_INSTANT_INVITE, label: '创建邀请' },
  { bit: PERMS.KICK_MEMBERS, label: '踢出成员' },
  { bit: PERMS.BAN_MEMBERS, label: '封禁成员' },
  { bit: PERMS.ADMINISTRATOR, label: '管理员' },
  { bit: PERMS.MANAGE_CHANNELS, label: '管理频道' },
  { bit: PERMS.MANAGE_GUILD, label: '管理服务器' },
  { bit: PERMS.ADD_REACTIONS, label: '添加反应' },
  { bit: PERMS.VIEW_AUDIT_LOG, label: '查看审计日志' },
  { bit: PERMS.VIEW_CHANNEL, label: '查看频道' },
  { bit: PERMS.SEND_MESSAGES, label: '发送消息' },
  { bit: PERMS.MANAGE_MESSAGES, label: '管理消息' },
  { bit: PERMS.EMBED_LINKS, label: '嵌入链接' },
  { bit: PERMS.ATTACH_FILES, label: '上传附件' },
  { bit: PERMS.READ_MESSAGE_HISTORY, label: '阅读历史消息' },
  { bit: PERMS.MENTION_EVERYONE, label: '@所有人' },
  { bit: PERMS.CONNECT, label: '连接语音' },
  { bit: PERMS.SPEAK, label: '语音说话' },
  { bit: PERMS.MUTE_MEMBERS, label: '静音成员' },
  { bit: PERMS.DEAFEN_MEMBERS, label: '禁听成员' },
  { bit: PERMS.MOVE_MEMBERS, label: '移动成员' },
  { bit: PERMS.CHANGE_NICKNAME, label: '修改昵称' },
  { bit: PERMS.MANAGE_NICKNAMES, label: '管理昵称' },
  { bit: PERMS.MANAGE_ROLES, label: '管理角色' },
  { bit: PERMS.MANAGE_WEBHOOKS, label: '管理 Webhook' },
];

export const hasPerm = (permString: string | undefined, bit: bigint): boolean => {
  if (!permString) return false;
  try {
    return (BigInt(permString) & bit) === bit;
  } catch {
    return false;
  }
};

/**
 * 计算某用户在公会内的有效权限(与后端算法一致: @everyone + 已分配角色)
 * rolesByGuild: store 的 roles (guildId -> roleId -> Role)
 * membersByGuild: store 的 members (guildId -> userId -> member)
 */
export const computeGuildPerms = (
  guildId: string,
  currentUserId: string,
  rolesByGuild: Record<string, Record<string, any>> | undefined,
  membersByGuild: Record<string, Record<string, any>> | undefined,
): bigint => {
  const allRoles = Object.values(rolesByGuild?.[guildId] || {}) as any[];
  const everyone = allRoles.find((r: any) => r.id === guildId);
  const toPerm = (v: any): bigint => {
    if (v == null) return 0n;
    try { return BigInt(v); } catch { return 0n; }
  };
  const base = toPerm(everyone?.permissions);
  if ((base & PERMS.ADMINISTRATOR) === PERMS.ADMINISTRATOR) return 0xffffffffffffffn;

  let perms = base;
  const member = membersByGuild?.[guildId]?.[currentUserId];
  if (member?.roles) {
    for (const roleId of member.roles) {
      const role = allRoles.find((r: any) => r.id === roleId);
      if (role?.permissions) perms |= toPerm(role.permissions);
    }
  }
  if ((perms & PERMS.ADMINISTRATOR) === PERMS.ADMINISTRATOR) return 0xffffffffffffffn;
  return perms;
};

export const isOwnerOf = (guild: { ownerId: string } | undefined, userId: string) =>
  String(guild?.ownerId ?? '') === String(userId ?? '');
