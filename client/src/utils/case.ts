// 后端 REST/Gateway 载荷为 后端的 snake_case 命名，前端接口统一 camelCase。
// 在数据边界（axios 拦截器 / Gateway 分发）做一次深度转换，其余代码保持 camelCase。
const toCamel = (key: string) => key.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase());

export function deepCamel<T>(value: T): T {
  if (Array.isArray(value)) return value.map((v) => deepCamel(v)) as unknown as T;
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[toCamel(k)] = deepCamel(v);
    }
    return out as T;
  }
  return value;
}
