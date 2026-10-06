import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { RecommendationCard } from '../components/RecommendationCard';
import { useLoad } from '../components/useLoad';
import { Empty, ErrorBanner, Loading, Panel } from '../components/ui';

/**
 * The human-in-the-loop screen. Two lists, deliberately side by side: what is waiting for a
 * decision, and what the safety layer already refused. The second list is the evidence that
 * the model is not allowed to invent AWS operations.
 */
export function ApprovalsPage() {
  const { isAdmin } = useAuth();
  const pending = useLoad(() => api.pendingRecommendations(), []);
  const blocked = useLoad(() => api.blockedRecommendations(), []);

  const reloadBoth = () => {
    pending.reload();
    blocked.reload();
  };

  return (
    <>
      <h1>Approval queue</h1>
      <ErrorBanner message={pending.error || blocked.error} />

      {!isAdmin && (
        <p className="muted small">
          You can review everything here, but approving an action is limited to administrators.
        </p>
      )}

      <Panel
        title="Waiting for a decision"
        subtitle="Nothing on this list has touched AWS yet"
        actions={
          <button type="button" onClick={reloadBoth}>
            Refresh
          </button>
        }
      >
        {pending.loading && !pending.data && <Loading />}
        {pending.data && pending.data.length === 0 && (
          <Empty>Nothing is waiting for approval.</Empty>
        )}
        {pending.data &&
          pending.data.map((rec) => (
            <RecommendationCard
              key={rec.id}
              recommendation={rec}
              onDone={reloadBoth}
              showIncident
            />
          ))}
      </Panel>

      <Panel
        title="Refused by the safety layer"
        subtitle="Suggestions that did not map to a predefined safe action, kept as evidence"
      >
        {blocked.loading && !blocked.data && <Loading />}
        {blocked.data && blocked.data.length === 0 && (
          <Empty>The models have not proposed anything outside the catalog.</Empty>
        )}
        {blocked.data &&
          blocked.data.map((rec) => (
            <RecommendationCard key={rec.id} recommendation={rec} showIncident />
          ))}
      </Panel>
    </>
  );
}
