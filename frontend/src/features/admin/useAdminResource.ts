import { useCallback, useEffect, useState } from 'react';
import { toErrorMessage } from '../../api/client';

export function useAdminResource<T>(loader: () => Promise<T>, generation: number) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;
    setData(null);
    setLoading(true);
    setError(null);
    void loader()
      .then((value) => { if (active) setData(value); })
      .catch((requestError) => { if (active) setError(toErrorMessage(requestError, '管理数据加载失败')); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [generation, loader, reloadKey]);

  const reload = useCallback(() => setReloadKey((current) => current + 1), []);
  return { data, setData, loading, error, reload };
}
