import React, { useEffect, useState } from 'react';
import { useStore } from '../../store';
import { channelApi, guildApi } from '../../utils/api';
import { memberLabel } from '../../utils/message';

// 展示的权限位（权限位掩码）
const PERMS = [
  { bit: 1n << 10n, label: '查看频道' },
  { bit: 1n << 11n, label: '发送消息' },
  { bit: 1n << 13n, label: '管理消息' },
  { bit: 1n << 15n, label: '上传文件' },
  { bit: 1n << 20n, label: '连接语音' },
  { bit: 1n << 21n, label: '语音说话' },
];

interface Overwrite {
  id: string;
  channelId: string;
  type: number; // 0 role, 1 member
  targetId: string;
  allow: string;
  deny: string;
}

interface Props {
  channelId: string;
  guildId: string;
  onClose: () => void;
}

const ChannelPermissionsModal: React.FC<Props> = ({ channelId, guildId, onClose }) => {
  const members = useStore((s) => s.members[guildId] || {});
  const [overwrites, setOverwrites] = useState<Overwrite[]>([]);
  const [roles, setRoles] = useState<any[]>([]);

  // 新覆盖表单
  const [targetType, setTargetType] = useState<0 | 1>(0);
  const [targetId, setTargetId] = useState('');
  const [allowMask, setAllowMask] = useState(0n);
  const [denyMask, setDenyMask] = useState(0n);

  const load = () => {
    channelApi.listOverwrites(channelId).then(setOverwrites).catch(console.error);
  };

  useEffect(() => {
    load();
    guildApi.getRoles(guildId).then(setRoles).catch(console.error);
  }, [channelId, guildId]);

  const targetName = (ow: Overwrite) => {
    const tid = String(ow.targetId ?? '');
    if (ow.type === 0) return roles.find((r) => r.id === tid)?.name || `角色 ${tid}`;
    return memberLabel(members[tid]);
  };

  const bit = (maskStr: string, b: bigint) => {
    try { return (BigInt(maskStr) & b) === b; } catch { return false; }
  };
  const toggleBit = (which: 'allow' | 'deny', b: bigint) => {
    if (which === 'allow') setAllowMask((m) => (m & b) === b ? m & ~b : m | b);
    else setDenyMask((m) => (m & b) === b ? m & ~b : m | b);
  };

  const handleSave = async () => {
    if (!targetId) return;
    try {
      await channelApi.createOverwrite(channelId, {
        type: targetType,
        target_id: targetId,
        allow: allowMask.toString(),
        deny: denyMask.toString(),
      });
      setTargetId(''); setAllowMask(0n); setDenyMask(0n);
      load();
    } catch (err: any) {
      alert(err.response?.data?.message || '保存失败');
    }
  };

  const handleUpdate = async (ow: Overwrite, which: 'allow' | 'deny', b: bigint) => {
    try {
      const allow = which === 'allow'
        ? (bit(ow.allow, b) ? BigInt(ow.allow) & ~b : BigInt(ow.allow) | b).toString()
        : ow.allow;
      const deny = which === 'deny'
        ? (bit(ow.deny, b) ? BigInt(ow.deny) & ~b : BigInt(ow.deny) | b).toString()
        : ow.deny;
      await channelApi.updateOverwrite(channelId, ow.id, { allow, deny });
      load();
    } catch (err) { console.error(err); }
  };

  const handleDelete = async (ow: Overwrite) => {
    try {
      await channelApi.deleteOverwrite(channelId, ow.id);
      load();
    } catch (err) { console.error(err); }
  };

  const candidateTargets = targetType === 0 ? roles : Object.values(members);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal permission-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>频道权限</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        <div className="permission-body">
          {/* 已有覆盖 */}
          {overwrites.length === 0 ? (
            <div className="permission-empty">暂无权限覆盖</div>
          ) : (
            overwrites.map((ow) => (
              <div key={ow.id} className="permission-row">
                <div className="permission-target">
                  <span className="permission-type">{ow.type === 0 ? '角色' : '成员'}</span>
                  <span className="permission-name">{targetName(ow)}</span>
                </div>
                <div className="permission-bits">
                  {PERMS.map((p) => (
                    <button
                      key={p.label}
                      className={`perm-toggle ${bit(ow.allow, p.bit) ? 'allow' : ''} ${bit(ow.deny, p.bit) ? 'deny' : ''}`}
                      title={p.label}
                      onClick={() => handleUpdate(ow, 'allow', p.bit)}
                      onContextMenu={(e) => { e.preventDefault(); handleUpdate(ow, 'deny', p.bit); }}
                    >
                      {p.label}
                    </button>
                  ))}
                  <span className="perm-hint">左键=允许，右键=拒绝</span>
                </div>
                <button className="perm-delete" onClick={() => handleDelete(ow)}>删除</button>
              </div>
            ))
          )}

          {/* 新增覆盖 */}
          <div className="permission-new">
            <div className="permission-new-row">
              <select value={targetType} onChange={(e) => { setTargetType(Number(e.target.value) as 0 | 1); setTargetId(''); }}>
                <option value={0}>角色</option>
                <option value={1}>成员</option>
              </select>
              <select value={targetId} onChange={(e) => setTargetId(e.target.value)}>
                <option value="">选择目标...</option>
                {candidateTargets.map((t: any) => (
                  <option key={t.id} value={t.id}>
                    {t.name || memberLabel({ ...t, userId: t.id })}
                  </option>
                ))}
              </select>
            </div>
            <div className="permission-bits">
              {PERMS.map((p) => (
                <button
                  key={p.label}
                  className={`perm-toggle ${(allowMask & p.bit) === p.bit ? 'allow' : ''} ${(denyMask & p.bit) === p.bit ? 'deny' : ''}`}
                  title={p.label}
                  onClick={() => toggleBit('allow', p.bit)}
                  onContextMenu={(e) => { e.preventDefault(); toggleBit('deny', p.bit); }}
                >
                  {p.label}
                </button>
              ))}
              <span className="perm-hint">左键=允许，右键=拒绝</span>
            </div>
            <button className="perm-save" onClick={handleSave} disabled={!targetId}>添加覆盖</button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ChannelPermissionsModal;
