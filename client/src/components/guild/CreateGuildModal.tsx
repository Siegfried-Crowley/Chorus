import React, { useState } from 'react';
import { useStore } from '../../store';
import { guildApi } from '../../utils/api';

interface Props {
  onClose: () => void;
}

const CreateGuildModal: React.FC<Props> = ({ onClose }) => {
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const addGuild = useStore((s) => s.addGuild);
  const setActiveGuild = useStore((s) => s.setActiveGuild);
  const setSidebar = useStore((s) => s.setSidebar);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || loading) return;

    setLoading(true);
    try {
      const guild = await guildApi.create(name.trim());
      addGuild({ ...guild, memberCount: guild.memberCount ?? 1 });
      setActiveGuild(guild.id);
      setSidebar('guilds');
      onClose();
    } catch (err: any) {
      alert(err.response?.data?.message || '创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>创建服务器</h2>
          <p>创建一个新的服务器，邀请好友一起聊天</p>
        </div>

        <form onSubmit={handleCreate}>
          <div className="form-group">
            <label>服务器名称</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="输入服务器名称"
              maxLength={100}
              required
              autoFocus
            />
          </div>

          <div className="modal-actions">
            <button type="button" className="btn-cancel" onClick={onClose}>
              取消
            </button>
            <button type="submit" className="btn-create" disabled={loading || !name.trim()}>
              {loading ? '创建中...' : '创建'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default CreateGuildModal;
