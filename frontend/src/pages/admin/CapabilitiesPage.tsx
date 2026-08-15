import { Check, Copy, Search } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';
import { adminApi } from '../../api/admin';
import type { CapabilityDefinition } from '../../api/adminTypes';
import { useAuth } from '../../features/auth/AuthProvider';
import { AdminError, AdminLoading, AdminPageHeader, RefreshButton, StatusBadge } from '../../features/admin/AdminPrimitives';
import { useAdminResource } from '../../features/admin/useAdminResource';

export function requiredParameters(tool: CapabilityDefinition): string[] {
  const required = tool.inputSchema.required;
  return Array.isArray(required) ? required.filter((item): item is string => typeof item === 'string') : [];
}

export function CapabilitiesPage() {
  const { identity, generation, requestSignal } = useAuth();
  const [query, setQuery] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const load = useCallback(() => adminApi.getTools(identity!.token, requestSignal), [identity, requestSignal]);
  const resource = useAdminResource(load, generation);
  const tools = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return (resource.data?.tools ?? []).filter((tool) => !normalized || `${tool.id} ${tool.description}`.toLowerCase().includes(normalized));
  }, [query, resource.data]);

  const copy = async (tool: CapabilityDefinition) => {
    await navigator.clipboard.writeText(JSON.stringify(tool, null, 2));
    setCopiedId(tool.id);
    window.setTimeout(() => setCopiedId(null), 1200);
  };

  return (
    <main className="admin-page" id="main-content">
      <AdminPageHeader eyebrow="Capability catalog" title="能力目录" description="浏览 Gateway 暴露的稳定 Capability 契约。显示名严格使用后端字段 id，必填参数从 inputSchema.required 推导。" actions={<RefreshButton loading={resource.loading} onClick={resource.reload} />} />
      <div className="admin-filterbar"><label className="search-field"><Search size={17} /><span className="sr-only">搜索能力</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索 ID 或描述" /></label><span>{tools.length} / {resource.data?.count ?? 0} 个能力</span></div>
      {resource.loading ? <AdminLoading /> : null}
      {resource.error ? <AdminError message={resource.error} onRetry={resource.reload} /> : null}
      {resource.data ? <div className="capability-list">
        {tools.map((tool) => {
          const required = requiredParameters(tool);
          return <article key={tool.id} className="capability-row"><div className="capability-summary"><div><code>{tool.id}</code><span>v{tool.version}</span></div><p>{tool.description}</p><div className="badge-row"><StatusBadge tone={tool.sideEffect ? 'warning' : 'neutral'}>{tool.sideEffect ? '副作用' : '只读'}</StatusBadge><StatusBadge tone={tool.requiresAuthentication ? 'info' : 'neutral'}>{tool.requiresAuthentication ? '需认证' : '无需认证'}</StatusBadge><StatusBadge tone={tool.riskLevel === 'HIGH' ? 'danger' : tool.riskLevel === 'MEDIUM' ? 'warning' : 'neutral'}>{tool.riskLevel}</StatusBadge></div></div><div className="capability-contract"><div><span>Required</span>{required.length ? required.map((item) => <code key={item}>{item}</code>) : <small>无</small>}</div><div><span>Timeout</span><strong>{tool.defaultTimeoutMs}ms</strong></div><button onClick={() => void copy(tool)}>{copiedId === tool.id ? <Check size={16} /> : <Copy size={16} />}{copiedId === tool.id ? '已复制' : '复制契约'}</button></div><details><summary>Input / Output Schema</summary><div className="schema-grid"><pre tabIndex={0}>{JSON.stringify(tool.inputSchema, null, 2)}</pre><pre tabIndex={0}>{JSON.stringify(tool.outputSchema, null, 2)}</pre></div></details></article>;
        })}
        {!tools.length ? <div className="positive-empty">没有匹配的 Capability</div> : null}
      </div> : null}
    </main>
  );
}
