import { useState } from 'react';
import { useAuth } from './AuthProvider';
import { Button } from '../../components/ui/Button';

export function TokenLogin({ onSuccess }: { onSuccess?: () => void }) {
  const { loginWithToken } = useAuth();
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  return <form className="token-login" onSubmit={async (event) => {
    event.preventDefault(); setBusy(true);
    try {
      if (await loginWithToken(token)) { setToken(''); onSuccess?.(); }
    } finally { setBusy(false); }
  }}>
    <label>登录凭据<input type="password" autoComplete="off" value={token} onChange={(event) => setToken(event.target.value)} required /></label>
    <p>使用身份系统签发的登录凭据，当前浏览器会话结束后需重新登录。</p>
    <Button type="submit" disabled={busy || !token.trim()}>{busy ? '验证中…' : '登录'}</Button>
  </form>;
}
