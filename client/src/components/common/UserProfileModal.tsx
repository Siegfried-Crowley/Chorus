import React, { useEffect, useState } from 'react';
import { useStore } from '../../store';
import { dmApi, userApi } from '../../utils/api';

interface Props {
  userId: string;
  onClose: () => void;
}

interface Profile {
  id: string;
  username: string;
  discriminator?: string;
  globalName?: string;
  avatar?: string;
  aboutMe?: string;
  banner?: string;
  accent_color?: number;
}

const UserProfileModal: React.FC<Props> = ({ userId, onClose }) => {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState('');
  const [dming, setDming] = useState(false);

  useEffect(() => {
    userApi.getProfile(userId)
      .then(setProfile)
      .catch(() => setError('加载用户资料失败'));
  }, [userId]);

  const openDm = async () => {
    if (dming) return;
    setDming(true);
    try {
      const dm = await dmApi.open(userId);
      const st = useStore.getState();
      // 把新私信并入列表与 channels,然后切到该私信
      st.setDmChannels(
        st.dmChannels.some((d) => d.id === dm.id)
          ? st.dmChannels
          : [...st.dmChannels, dm]
      );
      st.setActiveDmId(dm.id);
      st.setActiveGuild(null);
      st.setActiveChannel(dm.id);
      st.setSidebar('dms');
      onClose();
    } catch (err: any) {
      alert(err.response?.data?.error || '发起私信失败');
    } finally {
      setDming(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content user-profile-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>用户资料</h2>
        </div>

        {error && <div className="auth-error">{error}</div>}

        {profile && (
          <>
            <div className="user-profile-body">
              <div className="user-profile-avatar">
                {profile.avatar ? (
                  <img src={profile.avatar} alt="avatar" />
                ) : (
                  <span>{(profile.globalName || profile.username || '?').charAt(0).toUpperCase()}</span>
                )}
              </div>
              <div className="user-profile-info">
                <div className="user-profile-name">
                  {profile.globalName || profile.username}
                  <span className="user-profile-tag">#{profile.discriminator || '0000'}</span>
                </div>
                <div className="user-profile-username">@{profile.username}</div>
              </div>
            </div>

            <div className="user-profile-about">
              <div className="profile-section-title">关于我</div>
              <p>{profile.aboutMe || '这个人很懒，什么都没写~'}</p>
            </div>
          </>
        )}

        <div className="modal-actions">
          <button type="button" className="btn-cancel" onClick={onClose}>关闭</button>
          {profile && (
            <button type="button" className="btn-create" onClick={openDm} disabled={dming}>
              {dming ? '发起中...' : '💬 发私信'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default UserProfileModal;
