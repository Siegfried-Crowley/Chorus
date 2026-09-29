import React, { useState } from 'react';
import { useStore } from '../../store';
import { authApi } from '../../utils/api';

const TEST_ACCOUNTS = [
  { name: 'Alice', email: 'alice@test.com', desc: '已创建服务器，有好友' },
  { name: 'Bob', email: 'bob@test.com', desc: '普通用户，可加好友' },
  { name: 'Charlie', email: 'charlie@test.com', desc: '普通用户' },
];

// step: form=登录/注册表单, verify-email=邮箱验证码, verify-2fa=两步验证码
const LoginPage: React.FC = () => {
  const [isRegister, setIsRegister] = useState(false);
  const [step, setStep] = useState<'form' | 'verify-email' | 'verify-2fa'>('form');
  const [email, setEmail] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [mfaToken, setMfaToken] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { setToken, setCurrentUser } = useStore();

  const completeAuth = (result: any) => {
    setToken(result.token);
    setCurrentUser(result.user);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      if (isRegister) {
        const result = await authApi.register({ username, email, password });
        if (result.requiresVerification) {
          // 验证码已打印到后端日志
          setStep('verify-email');
        } else {
          completeAuth(result);
        }
      } else {
        const result = await authApi.login({ email, password });
        if (result.requires2fa) {
          setMfaToken(result.mfaToken);
          setStep('verify-2fa');
        } else {
          completeAuth(result);
        }
      }
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || err.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  const handleVerify = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      if (step === 'verify-email') {
        const result = await authApi.verifyEmail({ email, code });
        completeAuth(result);
      } else {
        const result = await authApi.verify2fa({ mfaToken, code });
        completeAuth(result);
      }
    } catch (err: any) {
      setError(err.response?.data?.error || err.response?.data?.message || err.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  const backToForm = () => {
    setStep('form');
    setCode('');
    setError('');
  };

  const fillTestAccount = (acc: typeof TEST_ACCOUNTS[0]) => {
    setEmail(acc.email);
    setPassword('test123');
    setError('');
  };

  // ===== 验证码步骤 =====
  if (step !== 'form') {
    const isEmailStep = step === 'verify-email';
    return (
      <div className="auth-page">
        <div className="auth-container">
          <div className="auth-header">
            <div className="auth-logo">
              <svg width="40" height="40" viewBox="0 0 40 40">
                <rect width="40" height="40" rx="8" fill="#5865F2"/>
                <text x="20" y="28" textAnchor="middle" fill="white" fontSize="20" fontWeight="bold">D</text>
              </svg>
            </div>
            <h1>{isEmailStep ? '验证邮箱' : '两步验证'}</h1>
            <p className="auth-subtitle auth-code-hint">
              {isEmailStep
                ? <>验证码已发送到 <strong>{email}</strong>（当前模拟环境打印在后端日志，请查看控制台）</>
                : <>已开启两步验证，请输入验证码（当前模拟环境打印在后端日志）</>}
            </p>
          </div>

          <form onSubmit={handleVerify} className="auth-form">
            {error && <div className="auth-error">{error}</div>}
            <div className="form-group">
              <label>验证码</label>
              <input
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="6 位数字验证码"
                required
                maxLength={6}
                pattern="[0-9]{6}"
              />
            </div>
            <button type="submit" className="auth-btn" disabled={loading}>
              {loading ? '验证中...' : '验证并进入'}
            </button>
            <button type="button" className="auth-btn auth-btn-ghost" onClick={backToForm}>
              返回上一步
            </button>
          </form>
        </div>
      </div>
    );
  }

  // ===== 登录/注册表单 =====
  return (
    <div className="auth-page">
      <div className="auth-container">
        <div className="auth-header">
          <div className="auth-logo">
            <svg width="40" height="40" viewBox="0 0 40 40">
              <rect width="40" height="40" rx="8" fill="#5865F2"/>
              <text x="20" y="28" textAnchor="middle" fill="white" fontSize="20" fontWeight="bold">D</text>
            </svg>
          </div>
          <h1>{isRegister ? '创建账号' : '欢迎回来'}</h1>
          <p className="auth-subtitle">
            {isRegister ? '创建一个新的 Chorus 账号' : '输入邮箱和密码登录，或使用测试账号'}
          </p>
        </div>

        {/* 测试账号快速入口 */}
        {!isRegister && (
          <div className="test-accounts">
            <div className="test-accounts-label">快速体验 ↓</div>
            <div className="test-accounts-list">
              {TEST_ACCOUNTS.map((acc) => (
                <div
                  key={acc.email}
                  className="test-account-item"
                  onClick={() => fillTestAccount(acc)}
                >
                  <div className="test-account-avatar">{acc.name.charAt(0)}</div>
                  <div className="test-account-info">
                    <span className="test-account-name">{acc.name}</span>
                    <span className="test-account-desc">{acc.desc}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        <form onSubmit={handleSubmit} className="auth-form">
          {error && <div className="auth-error">{error}</div>}

          {isRegister && (
            <div className="form-group">
              <label>用户名</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="输入用户名"
                required
                minLength={2}
                maxLength={32}
              />
            </div>
          )}

          <div className="form-group">
            <label>邮箱</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="输入邮箱地址"
              required
            />
          </div>

          <div className="form-group">
            <label>密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="输入密码"
              required
              minLength={6}
            />
          </div>

          <button type="submit" className="auth-btn" disabled={loading}>
            {loading ? '处理中...' : isRegister ? '注册' : '登录'}
          </button>
        </form>

        <div className="auth-footer">
          <a href="#" onClick={(e) => { e.preventDefault(); setIsRegister(!isRegister); }}>
            {isRegister ? '已有账号？点击登录' : '没有账号？点击注册'}
          </a>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
