import { useCallback, useEffect, useState } from 'react';

/**
 * Load-once-then-reload hook.
 *
 * Every page needs the same four things — data, a loading flag, an error message and a
 * way to refetch after an action — so they live here instead of in ten copies.
 *
 * @param loader zero-argument function returning a promise
 * @param deps   values that should trigger a reload when they change
 */
export function useLoad(loader, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((value) => value + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    loader()
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setError(null);
        }
      })
      .catch((cause) => {
        if (!cancelled) {
          // a 401 is already being handled by AuthProvider; don't shout about it twice
          setError(cause.status === 401 ? null : cause.message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { data, loading, error, reload, setData };
}

/**
 * Wraps a mutating call so a page can show "working…" and surface the failure
 * without repeating try/catch in every button handler.
 */
export function useAction(onDone) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const run = useCallback(
    async (operation) => {
      setBusy(true);
      setError(null);
      try {
        const result = await operation();
        if (onDone) {
          onDone(result);
        }
        return result;
      } catch (cause) {
        setError(cause.message);
        return null;
      } finally {
        setBusy(false);
      }
    },
    [onDone],
  );

  return { run, busy, error, clearError: () => setError(null) };
}
