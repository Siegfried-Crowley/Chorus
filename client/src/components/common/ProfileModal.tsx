import React, { useRef, useState } from 'react';
import { useStore } from '../../store';
import { authApi, userApi } from '../../utils/api';

interface Props {
  onClose: () => void;
}

const ProfileModal: React.FC<Props> = ({ onClose }) => {
  const { currentUser, setCurrentUser, setToken } = useStore();
  const [username, setUsername] = useState(currentUser?.username || '');
  const [globalName, setGlobalName] = useState(currentUser?.globalName || '');
  const [aboutMe, setAboutMe] = useState(currentUser?.aboutMe || '');
  const [saving, setSaving] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  // 安全设置
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [pwMsg, setPwMsg] = useState('');
  const [pwErr, setPwErr] = useState('');
  const [changingPw, setChangingPw] = useState(false);
  const [toggling2fa, setToggling2fa] = useState(false);

  if (!currentUser) return null;

  const save = async () => {
    if (saving) return;
    setSaving(true);
    try {
      const res = await userApi.updateProfile({
        username: username.trim() || undefined,
        global_name: globalName.trim() || undefined,
        about_me: aboutMe.trim() || undefined,
      });
      setCurrentUser({
        ...currentUser,
        username: res.username,
        globalName: res.globalName,
        aboutMe: res.aboutMe,
        avatar: res.avatar ?? currentUser.avatar,
      });
      onClose();
    } catch (err: any) {
      alert(err.response?.data?.error || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const uploadAvatar = async (file: File) => {
    try {
      const res = await userApi.uploadAvatar(file);
      setCurrentUser({ ...currentUser, avatar: res.avatar });
    } catch (err: any) {
      alert('头像上传失败');
    }
  };

  const changePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setPwErr('');
    setPwMsg('');
    if (newPassword.length < 6) {
      setPwErr('新密码至少 6 位');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPwErr('两次输入的新密码不一致');
      return;
    }
    setChangingPw(true);
    try {
      await authApi.changePassword({ oldPassword, newPassword });
      setPwMsg('密码已修改，下次登录请使用新密码');
      setOldPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err: any) {
      setPwErr(err.response?.data?.error || '修改失败');
    } finally {
      setChangingPw(false);
    }
  };

  const toggle2fa = async () => {
    if (toggling2fa) return;
    setToggling2fa(true);
    setPwMsg('');
    setPwErr('');
    try {
      if (currentUser.mfaEnabled) {
        const res = await authApi.disable2fa();
        setCurrentUser({ ...currentUser, mfaEnabled: res.mfaEnabled });
        setPwMsg('两步验证已关闭');
      } else {
        const res = await authApi.enable2fa();
        setCurrentUser({ ...currentUser, mfaEnabled: res.mfaEnabled });
        setPwMsg('两步验证已开启，验证码已打印到后端日志。下次登录时需要输入验证码');
      }
    } catch (err: any) {
      setPwErr(err.response?.data?.error || '操作失败');
    } finally {
      setToggling2fa(false);
    }
  };

  const logout = () => {
    setToken(null);
    setCurrentUser(null);
    onClose();
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>我的资料</h2>
        </div>

        <div className="profile-avatar">
          {currentUser.avatar ? (
            <img src={currentUser.avatar} alt="avatar" />
          ) : (
            <span>{currentUser.username.charAt(0).toUpperCase()}</span>
          )}
          <button className="profile-avatar-edit" onClick={() => fileRef.current?.click()}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 15.5A3.5 3.5 0 1 0 12 8.5a3.5 3.5 0 0 0 0 7zM17.42 2H6.58a2 2 0 0 0-1.99 1.77L4.18 9.5a2 2 0 0 0 1 1.98c1.56.89 2.82 2.01 2.82 2.52s-1.26 1.63-2.82 2.52a2 2 0 0 0-1 1.98l.41 5.73A2 2 0 0 0 6.58 24h10.84a2 2 0 0 0 1.99-1.77l.41-5.73a2 2 0 0 0-1-1.98c-1.56-.89-2.82-2.01-2.82-2.52s1.26-1.63 2.82-2.52a2 2 0 0 0 1-1.98l-.41-5.73A2 2 0 0 0 17.42 2zM12 17a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"/>
            </svg>
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) uploadAvatar(f);
            }}
          />
        </div>

        <div className="form-group">
          <label>用户名</label>
          <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={32} />
        </div>
        <div className="form-group">
          <label>显示名称(可选)</label>
          <input value={globalName} onChange={(e) => setGlobalName(e.target.value)} maxLength={32} />
        </div>
        <div className="form-group">
          <label>个性签名</label>
          <textarea
            className="modal-textarea"
            value={aboutMe}
            onChange={(e) => setAboutMe(e.target.value)}
            maxLength={190}
            placeholder="介绍一下自己..."
            rows={3}
          />
        </div>

        <div className="modal-actions">
          <button type="button" className="btn-cancel" onClick={onClose}>取消</button>
          <button type="submit" className="btn-create" onClick={save} disabled={saving}>
            {saving ? '保存中...' : '保存'}
          </button>
        </div>

        {/* ===== 安全设置 ===== */}
        <div className="profile-security-section">
          <div className="profile-section-title">安全设置</div>

          <form className="security-block" onSubmit={changePassword}>
            <div className="security-block-title">修改密码</div>
            <div className="security-pw-row">
              <input
                type="password"
                className="security-input"
                value={oldPassword}
                onChange={(e) => setOldPassword(e.target.value)}
                placeholder="当前密码"
                required
              />
              <input
                type="password"
                className="security-input"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                placeholder="新密码(至少6位)"
                required
              />
              <input
                type="password"
                className="security-input"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="确认新密码"
                required
              />
              <button type="submit" className="btn-create security-submit" disabled={changingPw}>
                {changingPw ? '修改中...' : '修改密码'}
              </button>
            </div>
          </form>

          <div className="security-block">
            <div className="security-block-title">两步验证(2FA)</div>
            <div className="security-2fa-row">
              <span className="security-status">
                {currentUser.mfaEnabled ? '✅ 已开启' : '⛔ 未开启'}
              </span>
              <button
                type="button"
                className={`btn-create ${currentUser.mfaEnabled ? 'btn-danger' : ''}`}
                onClick={toggle2fa}
                disabled={toggling2fa}
              >
                {toggling2fa ? '处理中...' : currentUser.mfaEnabled ? '关闭两步验证' : '开启两步验证'}
              </button>
            </div>
            <div className="security-hint">验证码会打印到后端日志（模拟环境无真实短信/邮件服务）</div>
          </div>

          {pwMsg && <div className="security-msg ok">{pwMsg}</div>}
          {pwErr && <div className="security-msg err">{pwErr}</div>}

          <div className="security-block security-logout-row">
            <button type="button" className="btn-danger" onClick={logout}>登出</button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ProfileModal;
