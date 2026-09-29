import React, { useEffect, useState } from 'react';
import { useStore } from '../../store';
import { guildApi, inviteApi, uploadApi } from '../../utils/api';
import { PERMS, PERM_LABELS, hasPerm, isOwnerOf } from '../../utils/permissions';
import { memberLabel } from '../../utils/message';

interface Props {
  guildId: string;
  onClose: () => void;
}

type Tab = 'overview' | 'members' | 'roles' | 'invites';

const GuildSettingsModal: React.FC<Props> = ({ guildId, onClose }) => {
  const store = useStore();
  const { guilds, roles, members, currentUser, removeGuild, setActiveGuild } = store;
  const guild = guilds[guildId];
  const [tab, setTab] = useState<Tab>('overview');

  useEffect(() => {
    guildApi.getRoles(guildId)
      .then((rs: any[]) => store.setRoles(guildId, rs.map((r) => ({
        id: r.id, guildId: r.guildId ?? r.guild_id, name: r.name, color: r.color,
        hoist: !!r.hoist, position: r.position || 0,
        permissions: r.permissions?.toString?.() || '0', mentionable: !!r.mentionable,
      }))))
      .catch(console.error);
    if (store.members[guildId] === undefined) {
      guildApi.getMembers(guildId).then((ms: any[]) => store.setMembers(guildId, ms)).catch(console.error);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [guildId]);

  if (!guild || !currentUser) return null;
  const isOwner = isOwnerOf(guild, currentUser.id);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="settings-layout">
          <div className="settings-nav">
            <button className={`settings-nav-item ${tab === 'overview' ? 'active' : ''}`} onClick={() => setTab('overview')}>概览</button>
            <button className={`settings-nav-item ${tab === 'members' ? 'active' : ''}`} onClick={() => setTab('members')}>成员</button>
            <button className={`settings-nav-item ${tab === 'roles' ? 'active' : ''}`} onClick={() => setTab('roles')}>角色</button>
            <button className={`settings-nav-item ${tab === 'invites' ? 'active' : ''}`} onClick={() => setTab('invites')}>邀请</button>
            <button className="settings-nav-item danger" onClick={onClose}>关闭</button>
          </div>
          <div className="settings-body">
            {tab === 'overview' && <OverviewTab guildId={guildId} />}
            {tab === 'members' && <MembersTab guildId={guildId} />}
            {tab === 'roles' && <RolesTab guildId={guildId} />}
            {tab === 'invites' && <InvitesTab guildId={guildId} />}
          </div>
        </div>
        <div className="modal-actions">
          <button type="button" className="btn-cancel" onClick={onClose}>完成</button>
        </div>
      </div>
    </div>
  );
};

// ===== 概览 =====
const OverviewTab: React.FC<{ guildId: string }> = ({ guildId }) => {
  const { guilds, currentUser, updateGuild, removeGuild, setActiveGuild, setActiveChannel, setSidebar } = useStore();
  const guild = guilds[guildId];
  const [name, setName] = useState(guild?.name || '');
  const [saving, setSaving] = useState(false);

  if (!guild || !currentUser) return null;
  const isOwner = isOwnerOf(guild, currentUser.id);

  const save = async () => {
    if (!name.trim() || saving) return;
    setSaving(true);
    try {
      const g = await guildApi.update(guildId, { name: name.trim() });
      updateGuild({ ...g, memberCount: guild.memberCount });
    } catch (err: any) {
      alert(err.response?.data?.error || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const uploadIcon = async (file: File) => {
    try {
      const meta = await uploadApi.upload(file);
      const g = await guildApi.update(guildId, { icon: meta.url });
      updateGuild({ ...g, memberCount: guild.memberCount });
    } catch (err: any) {
      alert('上传失败');
    }
  };

  const deleteGuild = async () => {
    if (!window.confirm(`确定删除服务器「${guild.name}」?此操作不可恢复!`)) return;
    try {
      await guildApi.remove(guildId);
      removeGuild(guildId);
      setActiveGuild(null);
      setActiveChannel(null);
      setSidebar('friends');
      // 关闭整个弹窗由父组件处理
    } catch (err: any) {
      alert(err.response?.data?.error || '删除失败');
    }
  };

  const leaveGuild = async () => {
    if (!window.confirm(`确定离开服务器「${guild.name}」?`)) return;
    try {
      await guildApi.leave(guildId);
      removeGuild(guildId);
      setActiveGuild(null);
      setActiveChannel(null);
      setSidebar('friends');
    } catch (err: any) {
      alert(err.response?.data?.error || '离开失败');
    }
  };

  return (
    <div className="settings-tab">
      <h3 className="settings-title">服务器概览</h3>
      <div className="form-group">
        <label>服务器图标</label>
        <div className="guild-icon-picker">
          {guild.icon ? <img src={guild.icon} alt="icon" /> : <span>{guild.name.charAt(0).toUpperCase()}</span>}
          <input type="file" accept="image/*" onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) uploadIcon(f);
          }} />
        </div>
      </div>
      <div className="form-group">
        <label>服务器名称</label>
        <input type="text" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
      </div>
      <button className="btn-create" onClick={save} disabled={saving || !name.trim()}>
        {saving ? '保存中...' : '保存更改'}
      </button>

      <div className="settings-danger">
        <div className="danger-title">危险区域</div>
        {isOwner ? (
          <button className="btn-danger" onClick={deleteGuild}>删除服务器</button>
        ) : (
          <button className="btn-danger" onClick={leaveGuild}>离开服务器</button>
        )}
      </div>
    </div>
  );
};

// ===== 成员 =====
const MembersTab: React.FC<{ guildId: string }> = ({ guildId }) => {
  const { members, guilds, currentUser, roles, removeGuildMember, updateGuildMember } = useStore();
  const guild = guilds[guildId];
  const [expanded, setExpanded] = useState<string | null>(null);

  if (!guild || !currentUser || !members[guildId]) return null;
  const isOwner = isOwnerOf(guild, currentUser.id);
  const roleList = Object.values(roles[guildId] || {}).filter((r: any) => r.id !== guildId);
  const list = Object.values(members[guildId]).sort((a: any, b: any) =>
    a.userId === guild.ownerId ? -1 : b.userId === guild.ownerId ? 1 : 0
  );

  const kick = async (userId: string) => {
    if (!window.confirm('确定踢出该成员?')) return;
    try { await guildApi.kick(guildId, userId); removeGuildMember(guildId, userId); }
    catch (err: any) { alert(err.response?.data?.error || '操作失败'); }
  };
  const ban = async (userId: string) => {
    if (!window.confirm('确定封禁该成员?')) return;
    try { await guildApi.ban(guildId, userId); removeGuildMember(guildId, userId); }
    catch (err: any) { alert(err.response?.data?.error || '操作失败'); }
  };
  const toggleRole = async (userId: string, roleId: string, checked: boolean) => {
    const m = members[guildId][userId];
    const next = checked ? [...m.roles, roleId] : m.roles.filter((r) => r !== roleId);
    try { await guildApi.assignRoles(guildId, userId, next); updateGuildMember(guildId, { userId, roles: next }); }
    catch (err: any) { alert(err.response?.data?.error || '操作失败'); }
  };

  return (
    <div className="settings-tab">
      <h3 className="settings-title">成员 ({list.length})</h3>
      <div className="settings-member-list">
        {list.map((m: any) => (
          <div key={m.userId} className="settings-member-row">
            <div className="settings-member-main" onClick={() => setExpanded(expanded === m.userId ? null : m.userId)}>
              <span className="settings-member-name">
                {String(m.userId) === String(guild.ownerId) ? '👑 ' : ''}
                {memberLabel(m)}
              </span>
            </div>
            {expanded === m.userId && m.userId !== guild.ownerId && (
              <div className="settings-member-expand">
                <div className="member-menu-label">分配角色</div>
                {roleList.map((r: any) => (
                  <label key={r.id} className="role-check">
                    <input type="checkbox" checked={m.roles.includes(r.id)}
                      onChange={(e) => toggleRole(m.userId, r.id, e.target.checked)} />
                    <span>{r.name}</span>
                  </label>
                ))}
                <div className="settings-member-actions">
                  <button className="btn-danger" onClick={() => kick(m.userId)}>踢出</button>
                  <button className="btn-danger" onClick={() => ban(m.userId)}>封禁</button>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};

// ===== 角色 =====
const RolesTab: React.FC<{ guildId: string }> = ({ guildId }) => {
  const { roles, addRole, updateRole, removeRole } = useStore();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [perms, setPerms] = useState<string>('0');
  const [color, setColor] = useState('#99aab5');
  const [creating, setCreating] = useState(false);

  const roleList = Object.values(roles[guildId] || {}).sort(
    (a: any, b: any) => (a.position || 0) - (b.position || 0)
  );

  const select = (r: any) => {
    setSelectedId(r.id);
    setName(r.name);
    setPerms(r.permissions || '0');
    setColor(`#${(r.color || 0).toString(16).padStart(6, '0')}`);
  };

  const createRole = async () => {
    setCreating(true);
    try {
      const r = await guildApi.createRole(guildId, { name: '新角色', permissions: '0' });
      addRole(guildId, r);
      select(r);
    } catch (err: any) {
      alert(err.response?.data?.error || '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const saveRole = async () => {
    if (!selectedId) return;
    const colorNum = parseInt(color.replace('#', ''), 16) || 0;
    try {
      const r = await guildApi.updateRole(guildId, selectedId, { name, permissions: perms, color: colorNum });
      updateRole(guildId, r);
    } catch (err: any) {
      alert(err.response?.data?.error || '保存失败');
    }
  };

  const deleteRole = async () => {
    if (!selectedId) return;
    if (!window.confirm('确定删除该角色?')) return;
    try {
      await guildApi.deleteRole(guildId, selectedId);
      removeRole(guildId, selectedId);
      setSelectedId(null);
    } catch (err: any) {
      alert(err.response?.data?.error || '删除失败');
    }
  };

  const togglePerm = (bit: bigint) => {
    const cur = BigInt(perms || '0');
    const next = (cur & bit) === bit ? cur & ~bit : cur | bit;
    setPerms(next.toString());
  };

  return (
    <div className="settings-tab roles-tab">
      <h3 className="settings-title">角色</h3>
      <div className="roles-layout">
        <div className="roles-list">
          <button className="roles-add" onClick={createRole} disabled={creating}>
            {creating ? '创建中...' : '+ 创建角色'}
          </button>
          {roleList.map((r: any) => (
            <div key={r.id} className={`roles-item ${selectedId === r.id ? 'active' : ''}`} onClick={() => select(r)}>
              <span className={`role-dot`} style={{ background: `#${(r.color || 0).toString(16).padStart(6, '0')}` }} />
              <span>{r.name}</span>
            </div>
          ))}
        </div>
        <div className="roles-editor">
          {selectedId ? (
            <>
              <div className="form-group">
                <label>角色名称</label>
                <input value={name} onChange={(e) => setName(e.target.value)}
                  disabled={selectedId === guildId} title={selectedId === guildId ? '@everyone 名称不可改' : ''} />
              </div>
              <div className="form-group">
                <label>角色颜色</label>
                <input type="color" value={color} onChange={(e) => setColor(e.target.value)} />
              </div>
              <div className="form-group">
                <label>权限</label>
                <div className="perms-grid">
                  {PERM_LABELS.map((p) => (
                    <label key={p.label} className="role-check">
                      <input type="checkbox" checked={hasPerm(perms, p.bit)} onChange={() => togglePerm(p.bit)} />
                      <span>{p.label}</span>
                    </label>
                  ))}
                </div>
              </div>
              <div className="settings-member-actions">
                <button className="btn-create" onClick={saveRole}>保存</button>
                {selectedId !== guildId && (
                  <button className="btn-danger" onClick={deleteRole}>删除角色</button>
                )}
              </div>
            </>
          ) : (
            <div className="roles-empty">选择一个角色进行编辑</div>
          )}
        </div>
      </div>
    </div>
  );
};

// ===== 邀请 =====
export const InvitesTab: React.FC<{ guildId: string }> = ({ guildId }) => {
  const { channels, addGuild, setChannels, setActiveGuild, setSidebar } = useStore();
  const [invites, setInvites] = useState<any[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [joinCode, setJoinCode] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = () => {
    inviteApi.list(guildId)
      .then((data: any) => { setInvites(Array.isArray(data) ? data : []); setLoadError(null); })
      .catch((err: any) => setLoadError(err.response?.data?.error || '无法加载邀请列表'));
  };
  useEffect(refresh, [guildId]);

  const createInvite = async () => {
    setBusy(true);
    try {
      const textCh = Object.values(channels).find(
        (c) => c.guildId === guildId && (c.type === 0 || c.type === 5)
      );
      const inv = await inviteApi.create(guildId, { channel_id: textCh?.id });
      setInvites((prev) => [inv, ...prev]);
    } catch (err: any) {
      alert(err.response?.data?.error || '创建失败');
    } finally {
      setBusy(false);
    }
  };

  const copy = (code: string) => {
    const link = `${window.location.origin}/invite/${code}`;
    navigator.clipboard?.writeText(link).then(
      () => alert(`邀请链接已复制:\n${link}`),
      () => alert(link)
    );
  };

  const removeInvite = async (code: string) => {
    try { await inviteApi.remove(code); setInvites((prev) => prev.filter((i) => i.code !== code)); }
    catch (err: any) { alert(err.response?.data?.error || '删除失败'); }
  };

  const join = async () => {
    if (!joinCode.trim() || busy) return;
    setBusy(true);
    try {
      const res = await inviteApi.join(joinCode.trim());
      const guildId = res.guildId;
      if (!guildId) throw new Error('加入失败:缺少公会ID');
      addGuild({
        id: guildId,
        name: res.name,
        icon: res.icon,
        ownerId: res.ownerId,
        memberCount: 0,
      });
      setChannels(guildId, (res.channels || []).map((c: any) => ({ ...c, guildId })));
      setActiveGuild(guildId);
      setSidebar('guilds');
      setJoinCode('');
    } catch (err: any) {
      alert(err.response?.data?.error || '加入失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="settings-tab">
      <h3 className="settings-title">邀请</h3>

      <div className="form-group">
        <label>通过邀请码加入服务器</label>
        <div className="join-inline">
          <input value={joinCode} onChange={(e) => setJoinCode(e.target.value)}
            placeholder="粘贴邀请码，如 abcdef12" />
          <button className="btn-create" onClick={join} disabled={busy || !joinCode.trim()}>加入</button>
        </div>
      </div>

      <button className="btn-create" onClick={createInvite} disabled={busy}>
        {busy ? '处理中...' : '+ 生成邀请链接'}
      </button>

      <div className="invite-list">
        {loadError && <div className="roles-empty">{loadError}</div>}
        {!loadError && invites.length === 0 && <div className="roles-empty">还没有邀请链接</div>}
        {invites.map((inv) => (
          <div key={inv.code} className="invite-row">
            <code>{inv.code}</code>
            <span className="invite-meta">
              {inv.maxUses ? `${inv.uses}/${inv.maxUses} 次` : '∞ 次'}
            </span>
            <button className="btn-cancel" onClick={() => copy(inv.code)}>复制</button>
            <button className="btn-danger" onClick={() => removeInvite(inv.code)}>删除</button>
          </div>
        ))}
      </div>
    </div>
  );
};

export default GuildSettingsModal;
