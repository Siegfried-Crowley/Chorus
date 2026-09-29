import React, { useState } from 'react';
import { useStore } from '../../store';
import { messageApi } from '../../utils/api';
import { toMessage, displayName } from '../../utils/message';
import { Markdown } from '../../utils/markdown';

interface Props {
  guildId: string;
  onClose: () => void;
  onOpenMessage: (channelId: string, messageId: string) => void;
}

const SearchModal: React.FC<Props> = ({ guildId, onClose, onOpenMessage }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [searched, setSearched] = useState(false);
  const [loading, setLoading] = useState(false);
  const channels = useStore((s) => s.channels);

  const channelName = (id: string) => {
    const ch = channels[id];
    return ch ? `#${ch.name}` : '';
  };

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    const q = query.trim();
    if (!q) return;
    setLoading(true);
    try {
      const data = await messageApi.search(guildId, q);
      setResults(Array.isArray(data) ? data : []);
      setSearched(true);
    } catch (err: any) {
      alert(err.response?.data?.error || '搜索失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>🔍 搜索消息</h3>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>
        <form onSubmit={handleSearch} className="search-form">
          <input
            className="search-input"
            placeholder="搜索这个服务器的消息..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoFocus
          />
          <button type="submit" className="btn-primary" disabled={loading}>
            {loading ? '搜索中...' : '搜索'}
          </button>
        </form>
        <div className="search-results">
          {searched && results.length === 0 && !loading && (
            <div className="pins-empty">没有找到匹配的消息</div>
          )}
          {results.map((raw) => {
            const m = toMessage(raw);
            return (
              <div
                key={m.id}
                className="search-result"
                onClick={() => onOpenMessage(m.channelId, m.id)}
              >
                <div className="search-result-meta">
                  <span className="search-result-channel">{channelName(m.channelId)}</span>
                  <span className="search-result-author">{displayName(m.authorId)}</span>
                  <span className="search-result-time">
                    {new Date(m.timestamp).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                  </span>
                </div>
                <div className="search-result-content"><Markdown text={m.content} /></div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default SearchModal;
