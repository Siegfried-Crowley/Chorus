import React from 'react';

/**
 * 轻量 Markdown 渲染器 — 直接返回 React 节点(天然防 XSS,不产生 HTML 字符串)
 * 支持: 代码块 ``` 、行内代码 ` 、加粗 ** 、斜体 * / _ 、链接 [text](url)、
 *       提及 @名字/@everyone 高亮、常用 emoji :xxx:
 */

const EMOJI_MAP: Record<string, string> = {
  smile: '😊', happy: '😀', laugh: '😂', joy: '😂', heart: '❤️',
  thumbsup: '👍', like: '👍', thumbsdown: '👎', cry: '😢', sob: '😭',
  angry: '😡', wink: '😉', ok: '👌', fire: '🔥', check: '✅', x: '❌',
  wave: '👋', clap: '👏', star: '⭐', pray: '🙏', thinking: '🤔',
  confused: '😕', cool: '😎', party: '🎉', tada: '🎉', sleepy: '😴',
  shocked: '😱', sweat: '😅', neutral: '😐', eyes: '👀', hug: '🤗',
};

const INLINE_RE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*\n]+\*)|(_[^_\n]+_)|(\[[^\]]+\]\([^)\s]+\))|(:[a-z0-9_+-]+:)|(@[^\s@,，。！？.!?;；]+)/;

/** 解析一段非代码文本为 React 节点 */
export function renderInline(text: string, keyPrefix = ''): React.ReactNode {
  const nodes: React.ReactNode[] = [];
  let rest = text;
  let key = 0;
  while (rest.length > 0) {
    const m = INLINE_RE.exec(rest);
    if (!m) {
      nodes.push(rest);
      break;
    }
    if (m.index > 0) nodes.push(rest.slice(0, m.index));
    const token = m[0];
    const k = `${keyPrefix}${key++}`;

    if (token.startsWith('`')) {
      nodes.push(<code key={k} className="md-inline-code">{token.slice(1, -1)}</code>);
    } else if (token.startsWith('**')) {
      nodes.push(<strong key={k}>{token.slice(2, -2)}</strong>);
    } else if (token.startsWith('*')) {
      nodes.push(<em key={k}>{token.slice(1, -1)}</em>);
    } else if (token.startsWith('_')) {
      nodes.push(<em key={k}>{token.slice(1, -1)}</em>);
    } else if (token.startsWith('[')) {
      const link = /\[([^\]]+)\]\(([^)\s]+)\)/.exec(token)!;
      nodes.push(
        <a key={k} href={link[2]} target="_blank" rel="noreferrer noopener" className="md-link">
          {renderInline(link[1], `${k}-`)}
        </a>
      );
    } else if (token.startsWith(':')) {
      const emoji = EMOJI_MAP[token.slice(1, -1)];
      nodes.push(emoji ? <span key={k} className="md-emoji">{emoji}</span> : token);
    } else if (token.startsWith('@')) {
      const everyone = token === '@everyone';
      nodes.push(
        <span key={k} className={`md-mention${everyone ? ' everyone' : ''}`}>{token}</span>
      );
    } else {
      nodes.push(token);
    }
    rest = rest.slice(m.index + token.length);
  }
  return nodes;
}

interface MarkdownProps {
  text: string;
}

/** 完整 Markdown:先切出代码块,再对普通段做行内渲染 */
export const Markdown: React.FC<MarkdownProps> = ({ text }) => {
  if (!text || typeof text !== 'string') return <>{''}</>;
  const parts = text.split(/(```[\s\S]*?```)/g);
  return (
    <>
      {parts.map((part, i) => {
        if (part.startsWith('```')) {
          const code = part.slice(3, -3).replace(/^\n+/, '');
          return (
            <pre key={i} className="md-code-block">
              <code>{code}</code>
            </pre>
          );
        }
        return <React.Fragment key={i}>{renderInline(part, `${i}-`)}</React.Fragment>;
      })}
    </>
  );
};
