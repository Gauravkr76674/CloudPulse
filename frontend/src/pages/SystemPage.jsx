import { useState } from 'react';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { useAction, useLoad } from '../components/useLoad';
import { Badge, Empty, ErrorBanner, InfoBanner, Loading, Panel, Stat } from '../components/ui';

/**
 * Configuration as the running backend sees it. Useful when a demo "does nothing": it shows
 * at a glance whether the provider is the mock, whether AI keys are present, and whether
 * execution is switched on.
 */
export function SystemPage() {
  const { isAdmin, atLeast } = useAuth();
  const status = useLoad(() => api.systemStatus(), []);
  const routing = useLoad(() => api.aiRouting(), []);
  const permissions = useLoad(
    () => (isAdmin ? api.iamPermissions() : Promise.resolve(null)),
    [isAdmin],
  );
  const [notice, setNotice] = useState(null);
  const action = useAction(() => {
    status.reload();
    permissions.reload();
  });

  const state = status.data;

  return (
    <>
      <h1>System</h1>
      <ErrorBanner
        message={status.error || routing.error || permissions.error || action.error}
        onDismiss={action.clearError}
      />
      <InfoBanner message={notice} onDismiss={() => setNotice(null)} />

      {status.loading && !state && <Loading />}

      {state && (
        <Panel
          title="Live configuration"
          actions={
            atLeast('OPERATOR') && (
              <>
                <button
                  type="button"
                  disabled={action.busy}
                  onClick={() =>
                    action.run(async () => {
                      const result = await api.syncResources();
                      setNotice(
                        `${result.provider}: ${result.discovered} discovered, ${result.created} new.`,
                      );
                      return result;
                    })
                  }
                >
                  Sync resources
                </button>
                <button
                  type="button"
                  disabled={action.busy}
                  onClick={() =>
                    action.run(async () => {
                      const result = await api.collectAllMetrics();
                      setNotice(
                        `Polled ${result.resourcesPolled} resource(s); ${result.incidentsOpened} incident(s) opened.`,
                      );
                      return result;
                    })
                  }
                >
                  Collect metrics
                </button>
              </>
            )
          }
        >
          <div className="stats">
            <Stat
              label="Cloud provider"
              value={state.cloudProvider}
              hint={state.region || 'no region set'}
              tone={state.cloudReachable ? 'ok' : 'danger'}
            />
            <Stat
              label="Provider reachable"
              value={state.cloudReachable ? 'yes' : 'no'}
              tone={state.cloudReachable ? 'ok' : 'danger'}
              hint={state.callerIdentity || undefined}
            />
            <Stat
              label="AI provider"
              value={state.aiProvider}
              hint={state.aiDefaultModel || 'no model set'}
              tone={state.aiConfigured ? 'ok' : 'warn'}
            />
            <Stat
              label="AI key configured"
              value={state.aiConfigured ? 'yes' : 'no'}
              tone={state.aiConfigured ? 'ok' : 'warn'}
              hint={state.aiConfigured ? undefined : 'falls back to the offline stub'}
            />
            <Stat
              label="Monitoring loop"
              value={state.monitoringEnabled ? 'on' : 'off'}
              hint={`every ${Math.round((state.pollIntervalMs || 0) / 1000)}s`}
            />
            <Stat
              label="Execution enabled"
              value={state.remediationEnabled ? 'yes' : 'no'}
              tone={state.remediationEnabled ? '' : 'warn'}
            />
            <Stat
              label="Force dry run"
              value={state.forceDryRun ? 'yes' : 'no'}
              tone={state.forceDryRun ? 'warn' : ''}
            />
            <Stat
              label="Approval required"
              value={state.approvalRequired ? 'yes' : 'no'}
              tone={state.approvalRequired ? 'ok' : 'danger'}
            />
          </div>
          {state.cloudMessage && <p className="small muted">{state.cloudMessage}</p>}
        </Panel>
      )}

      <Panel
        title="Model routing"
        subtitle="Which model handles each stage of the pipeline"
      >
        {routing.loading && !routing.data && <Loading />}
        {routing.data && (
          <>
            <p className="small muted">
              {routing.data.multiModelConfigured
                ? 'Different models are configured per stage.'
                : 'One model with a specialised prompt per stage. The routing table is the extension point for using several.'}
            </p>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Stage</th>
                    <th>Model</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(routing.data.routing || {}).map(([stage, model]) => (
                    <tr key={stage}>
                      <td>{stage}</td>
                      <td className="mono">{String(model)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Panel>

      {isAdmin && (
        <Panel
          title="IAM pre-flight"
          subtitle="Asks AWS whether each remediation call would be allowed, without making it"
          actions={
            <button type="button" onClick={permissions.reload}>
              Re-check
            </button>
          }
        >
          {permissions.loading && !permissions.data && <Loading />}
          {permissions.data && Object.keys(permissions.data).length === 0 && (
            <Empty>No permission information available.</Empty>
          )}
          {permissions.data && Object.keys(permissions.data).length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Operation</th>
                    <th>Verdict</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(permissions.data).map(([operation, verdict]) => (
                    <tr key={operation}>
                      <td className="mono small">{operation}</td>
                      <td>
                        <Badge
                          value={verdict}
                          tone={
                            /allow|granted|permitted/i.test(String(verdict))
                              ? 'ok'
                              : /deny|denied|not/i.test(String(verdict))
                                ? 'danger'
                                : 'muted-tone'
                          }
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      )}
    </>
  );
}
