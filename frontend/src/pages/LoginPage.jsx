import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthContext';
import { ErrorBanner, Loading } from '../components/ui';

export function LoginPage() {
  const { user, loading, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (loading) {
    return (
      <div className="centered">
        <Loading label="Checking your session…" />
      </div>
    );
  }
  if (user) {
    return <Navigate to="/" replace />;
  }

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(username.trim(), password);
      const target = (location.state && location.state.from) || '/';
      navigate(target, { replace: true });
    } catch (cause) {
      setError(
        cause.status === 401 ? 'That username and password do not match an account.' : cause.message,
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <form className="login-card" onSubmit={submit}>
        <h1>CloudOps AI Agent</h1>
        <p className="muted small">
          Sign in to monitor cloud resources, review AI diagnoses and approve remediation.
        </p>

        <ErrorBanner message={error} onDismiss={() => setError(null)} />

        <div className="field">
          <label htmlFor="username">Username</label>
          <input
            id="username"
            name="username"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            required
          />
        </div>

        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </div>

        <button type="submit" className="primary" disabled={busy || !username || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>

        <p className="small muted" style={{ marginTop: 14 }}>
          The first account is created from the BOOTSTRAP_ADMIN_USERNAME and
          BOOTSTRAP_ADMIN_PASSWORD environment variables the backend was started with.
        </p>
      </form>
    </div>
  );
}
