import { CheckCircle2, CircleDashed, MessageSquarePlus, Play } from 'lucide-react';
import type { DemoIdentity } from '../../../api/types';
import { Button } from '../../../components/ui/Button';
import { demoScenarios, quickPrompts, type DemoScenario } from '../data/scenarios';

interface WorkspaceSidebarProps {
  identities: DemoIdentity[];
  identity: DemoIdentity;
  conversationId: string;
  demoProgress: {
    scenarioName: string;
    current: number;
    total: number;
    status: 'running' | 'done' | 'failed';
  } | null;
  disabled: boolean;
  onSelectIdentity: (userId: string) => void;
  onNewConversation: () => void;
  onRunScenario: (scenario: DemoScenario) => void;
  onPrompt: (prompt: string) => void;
}

export function WorkspaceSidebar({
  identities,
  identity,
  conversationId,
  demoProgress,
  disabled,
  onSelectIdentity,
  onNewConversation,
  onRunScenario,
  onPrompt,
}: WorkspaceSidebarProps) {
  return (
    <aside className="workspace-sidebar">
      <div className="sidebar-heading">
        <div><span className="eyebrow">当前访客</span><h2>会话工作区</h2></div>
        <Button variant="ghost" icon={<MessageSquarePlus size={17} />} onClick={onNewConversation} aria-label="新建会话" />
      </div>

      <div className="identity-list">
        {identities.map((item) => (
          <button
            key={item.userId}
            className={`identity-card ${item.userId === identity.userId ? 'active' : ''}`}
            onClick={() => onSelectIdentity(item.userId)}
          >
            <span className="avatar">{item.name.slice(0, 1).toUpperCase()}</span>
            <span><strong>{item.name}</strong><small>{item.userId}</small></span>
            <span className={`presence ${item.userId === identity.userId ? 'active' : ''}`} />
          </button>
        ))}
      </div>

      <div className="conversation-pill" title={conversationId}>
        <CircleDashed size={14} />
        <span>{conversationId}</span>
      </div>

      <section className="sidebar-section">
        <div className="section-label"><span>演示场景</span><small>真实 API</small></div>
        <div className="scenario-list">
          {demoScenarios.map((scenario) => {
            const Icon = scenario.icon;
            return (
              <button
                key={scenario.id}
                className="scenario-card"
                disabled={disabled}
                onClick={() => onRunScenario(scenario)}
              >
                <span className={`scenario-icon accent-${scenario.accent}`}><Icon size={17} /></span>
                <span><strong>{scenario.name}</strong><small>{scenario.description}</small></span>
                <Play size={14} className="scenario-play" />
              </button>
            );
          })}
        </div>
        {demoProgress ? (
          <div className={`demo-progress demo-progress--${demoProgress.status}`}>
            {demoProgress.status === 'done' ? <CheckCircle2 size={15} /> : <span className="spinner" />}
            <span>{demoProgress.scenarioName}</span>
            <strong>{demoProgress.current}/{demoProgress.total}</strong>
          </div>
        ) : null}
      </section>

      <section className="sidebar-section sidebar-section--prompts">
        <div className="section-label"><span>快捷提问</span></div>
        <div className="prompt-chips">
          {quickPrompts.map((prompt) => (
            <button key={prompt} disabled={disabled} onClick={() => onPrompt(prompt)}>{prompt}</button>
          ))}
        </div>
      </section>
    </aside>
  );
}
