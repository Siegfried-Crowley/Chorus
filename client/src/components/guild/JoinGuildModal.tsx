import React, { useState } from 'react';
import { useStore } from '../../store';
import { inviteApi } from '../../utils/api';

interface Props {
  onClose: () => void;
}

const JoinGuildModal: React.FC<Props> = ({ onClose }) => {
  const { addGuild, setChannels, setActiveGuild, setActiveChannel, setSidebar } = useStore();
  const [code, setCode] = useState('');
  const [preview, setPreview] = useState<any>(null);
  const [loading, setLoading] = useState(false);

  // 输入邀请码时尝试预览
  const previewInvite = async (c: string) => {
    setCode(c);
    if (!c.trim()) { setPreview(null); return; }
    try {
      setPreview(await inviteApi.preview(c.trim()));
    } catch {
      setPreview(null);
    }
  };

  const join = async () => {
    if (!code.trim() || loading) return;
    setLoading(true);
    try {
      const res = await inviteApi.join(code.trim());
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
      setActiveChannel(null);
      setSidebar('guilds');
      onClose();
    } catch (err: any) {
      alert(err.response?.data?.error || '加入失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>通过邀请加入</h2>
          <p>输入邀请码加入一个服务器</p>
        </div>

        <div className="form-group">
          <label>邀请码</label>
          <input
            type="text"
            value={code}
            onChange={(e) => previewInvite(e.target.value)}
            placeholder="粘贴邀请码"
            autoFocus
          />
        </div>

        {preview && (
          <div className="invite-preview">
            <div className="invite-preview-icon">
              {preview.guildName?.charAt(0)?.toUpperCase() || '#'}
            </div>
            <div>
              <div className="invite-preview-name">{preview.guildName || '未知服务器'}</div>
              <div className="invite-preview-meta">
                {preview.memberCount} 名成员 · {preview.channelName ? `#${preview.channelName}` : ''}
              </div>
            </div>
          </div>
        )}

        <div className="modal-actions">
          <button type="button" className="btn-cancel" onClick={onClose}>取消</button>
          <button type="submit" className="btn-create" onClick={join} disabled={loading || !code.trim()}>
            {loading ? '加入中...' : '加入服务器'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default JoinGuildModal;
