/**
 * One function per backend endpoint, named after what it does rather than the URL.
 * Every page imports from here, so a route change on the server is a one-line change.
 */
import { get, post, patch, put, del, setToken } from './client';

// ----- auth -----
export async function login(username, password) {
  const response = await post('/api/auth/login', { username, password });
  setToken(response.token);
  return response;
}
export const currentUser = () => get('/api/auth/me');
export const changePassword = (currentPassword, newPassword) =>
  post('/api/auth/change-password', { currentPassword, newPassword });

// ----- resources -----
export const listResources = (search) => get('/api/resources', { search });
export const getResource = (id) => get(`/api/resources/${id}`);
export const syncResources = () => post('/api/resources/sync');
export const refreshResource = (id) => post(`/api/resources/${id}/refresh`);
export const setResourceMonitored = (id, monitored) =>
  patch(`/api/resources/${id}/monitored`, { monitored });
export const updateResource = (id, body) => put(`/api/resources/${id}`, body);
export const deleteResource = (id) => del(`/api/resources/${id}`);

// ----- metrics -----
export const recentMetrics = (resourceId, windowMinutes) =>
  get(`/api/metrics/resource/${resourceId}`, { windowMinutes });
export const latestMetrics = (resourceId) => get(`/api/metrics/resource/${resourceId}/latest`);
export const metricSeries = (resourceId, metricName, windowMinutes) =>
  get(`/api/metrics/resource/${resourceId}/series`, { metricName, windowMinutes });
export const collectAllMetrics = () => post('/api/metrics/collect');
export const collectResourceMetrics = (resourceId) => post(`/api/metrics/collect/${resourceId}`);

// ----- incidents -----
export const searchIncidents = (params) => get('/api/incidents', params);
export const incidentDetail = (id) => get(`/api/incidents/${id}`);
export const acknowledgeIncident = (id) => post(`/api/incidents/${id}/acknowledge`);
export const resolveIncident = (id, note) => post(`/api/incidents/${id}/resolve`, { note });
export const closeIncident = (id, note) => post(`/api/incidents/${id}/close`, { note });
export const analyzeIncident = (id) => post(`/api/incidents/${id}/analyze`);
export const verifyIncident = (id) => post(`/api/incidents/${id}/verify`);
export const incidentAnalyses = (id) => get(`/api/incidents/${id}/analyses`);
export const incidentRecommendations = (id) => get(`/api/incidents/${id}/recommendations`);
export const incidentActions = (id) => get(`/api/incidents/${id}/actions`);

// ----- recommendations and the approval gate -----
export const pendingRecommendations = () => get('/api/recommendations/pending');
export const blockedRecommendations = () => get('/api/recommendations/blocked');
export const approveRecommendation = (id, dryRun, note) =>
  post(`/api/recommendations/${id}/approve`, { dryRun, note });
export const rejectRecommendation = (id, note) =>
  post(`/api/recommendations/${id}/reject`, { note });

// ----- actions -----
export const actionHistory = (params) => get('/api/actions', params);
export const actionCatalog = () => get('/api/actions/catalog');
export const getAction = (id) => get(`/api/actions/${id}`);

// ----- dashboard, audit, system -----
export const dashboardSummary = () => get('/api/dashboard');
export const incidentsBySeverity = () => get('/api/dashboard/severity');
export const auditLog = (params) => get('/api/audit', params);
export const systemStatus = () => get('/api/system/status');
export const aiRouting = () => get('/api/system/ai-routing');
export const iamPermissions = () => get('/api/system/permissions');

// ----- notifications -----
export const listNotifications = (unreadOnly, limit) =>
  get('/api/notifications', { unreadOnly, limit });
export const unreadNotificationCount = () => get('/api/notifications/unread-count');
export const markNotificationRead = (id) => post(`/api/notifications/${id}/read`);
export const markAllNotificationsRead = () => post('/api/notifications/read-all');

// ----- users (ADMIN) -----
export const listUsers = () => get('/api/users');
export const createUser = (body) => post('/api/users', body);
export const changeUserRole = (id, role) => patch(`/api/users/${id}/role`, { role });
export const setUserEnabled = (id, enabled) => patch(`/api/users/${id}/enabled`, { enabled });
export const resetUserPassword = (id, newPassword) =>
  post(`/api/users/${id}/reset-password`, { newPassword });
