import { Navigate, Route, Routes, useLocation } from 'react-router-dom';

import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Loading } from './components/ui';
import { ActionsPage } from './pages/ActionsPage';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { AuditPage } from './pages/AuditPage';
import { DashboardPage } from './pages/DashboardPage';
import { IncidentDetailPage } from './pages/IncidentDetailPage';
import { IncidentsPage } from './pages/IncidentsPage';
import { LoginPage } from './pages/LoginPage';
import { ResourceDetailPage } from './pages/ResourceDetailPage';
import { ResourcesPage } from './pages/ResourcesPage';
import { SystemPage } from './pages/SystemPage';
import { UsersPage } from './pages/UsersPage';

/** Gate for everything behind a token. Admin-only screens pass requireAdmin. */
function RequireAuth({ requireAdmin = false, children }) {
  const { user, loading, isAdmin } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="centered">
        <Loading label="Checking your session…" />
      </div>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (requireAdmin && !isAdmin) {
    return (
      <div className="centered">
        <p>This screen is limited to administrators.</p>
      </div>
    );
  }
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<DashboardPage />} />
        <Route path="/resources" element={<ResourcesPage />} />
        <Route path="/resources/:id" element={<ResourceDetailPage />} />
        <Route path="/incidents" element={<IncidentsPage />} />
        <Route path="/incidents/:id" element={<IncidentDetailPage />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/actions" element={<ActionsPage />} />
        <Route
          path="/audit"
          element={
            <RequireAuth requireAdmin>
              <AuditPage />
            </RequireAuth>
          }
        />
        <Route path="/system" element={<SystemPage />} />
        <Route
          path="/users"
          element={
            <RequireAuth requireAdmin>
              <UsersPage />
            </RequireAuth>
          }
        />
        <Route path="*" element={<p>That page does not exist.</p>} />
      </Route>
    </Routes>
  );
}
