import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './components/App';
import './styles/discord.css';

/**
 * 根级错误边界:任何渲染期异常都不再导致整页空白,
 * 而是显示错误卡片 + 重载按钮(便于定位与恢复)。
 */
class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('[ErrorBoundary] render error:', error, info.componentStack);
  }

  private handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.error) {
      return (
        <div style={{
          minHeight: '100vh',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 16,
          background: '#313338',
          color: '#f2f3f5',
          fontFamily: 'system-ui, sans-serif',
          padding: 24,
        }}>
          <div style={{ fontSize: 22, fontWeight: 600 }}>页面遇到了一点问题</div>
          <pre style={{
            maxWidth: '80vw',
            maxHeight: '40vh',
            overflow: 'auto',
            background: '#1e1f22',
            padding: 16,
            borderRadius: 8,
            fontSize: 13,
            color: '#faa61a',
            whiteSpace: 'pre-wrap',
          }}>
            {this.state.error?.message || String(this.state.error)}
          </pre>
          <button
            onClick={this.handleReload}
            style={{
              background: '#5865f2',
              color: '#fff',
              border: 'none',
              borderRadius: 4,
              padding: '10px 24px',
              fontSize: 14,
              cursor: 'pointer',
            }}
          >
            重新加载
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

const root = ReactDOM.createRoot(document.getElementById('root')!);
root.render(
  <ErrorBoundary>
    <App />
  </ErrorBoundary>
);
