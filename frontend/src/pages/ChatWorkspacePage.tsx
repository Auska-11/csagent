import { useState } from 'react';
import { ConversationPanel } from '../features/chat/components/ConversationPanel';
import { InspectorPanel } from '../features/chat/components/InspectorPanel';
import { WorkspaceSidebar } from '../features/chat/components/WorkspaceSidebar';
import { useChatWorkspace } from '../features/chat/hooks/useChatWorkspace';
import { useAuth } from '../features/auth/AuthProvider';

export function ChatWorkspacePage() {
  const { identities, identity, selectIdentity } = useAuth();
  const [inspectorOpen, setInspectorOpen] = useState(false);

  if (!identity) return null;
  const chat = useChatWorkspace(identity);
  const demoRunning = chat.demoProgress?.status === 'running';

  return (
    <div className="workspace-page">
      <div className="workspace-grid">
        <WorkspaceSidebar
          identities={identities}
          identity={identity}
          conversationId={chat.workspace.conversationId}
          demoProgress={chat.demoProgress}
          disabled={chat.workspace.sending || demoRunning}
          onSelectIdentity={selectIdentity}
          onNewConversation={chat.newConversation}
          onRunScenario={(scenario) => void chat.runScenario(scenario)}
          onPrompt={(prompt) => void chat.sendMessage(prompt)}
        />
        <ConversationPanel
          userName={identity.name}
          conversationId={chat.workspace.conversationId}
          messages={chat.workspace.messages}
          trace={chat.workspace.trace}
          sending={chat.workspace.sending}
          error={chat.workspace.error}
          inspectorOpen={inspectorOpen}
          onSend={(message) => void chat.sendMessage(message)}
          onFeedback={(messageId, rating) => void chat.submitFeedback(messageId, rating)}
          onOpenInspector={() => setInspectorOpen(true)}
          onRetry={() => void chat.retryLastMessage()}
        />
        <InspectorPanel
          trace={chat.workspace.trace}
          conversationMemory={chat.workspace.conversationMemory}
          userMemory={chat.workspace.userMemory}
          memoryLoading={chat.workspace.memoryLoading}
          memoryError={chat.workspace.memoryError}
          open={inspectorOpen}
          onClose={() => setInspectorOpen(false)}
          onRefreshMemory={() => void chat.refreshMemory()}
          onClearConversationMemory={() => void chat.clearConversationMemory()}
          onClearUserMemory={() => void chat.clearUserMemory()}
        />
        {inspectorOpen ? <button className="inspector-backdrop" onClick={() => setInspectorOpen(false)} aria-label="关闭详情面板" /> : null}
      </div>
    </div>
  );
}
