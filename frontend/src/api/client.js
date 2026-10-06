/**
 * The single place where HTTP happens.
 *
 * Responsibilities: attach the bearer token, turn the backend's ApiError body into an
 * Error with a usable message, and broadcast a 401 so the app can log out instead of
 * leaving the user staring at a broken screen.
 */

const BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');
const TOKEN_KEY = 'cloudops.token';
export const UNAUTHORIZED_EVENT = 'cloudops:unauthorized';

export function getToken() {
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token) {
  try {
    if (token) {
      window.localStorage.setItem(TOKEN_KEY, token);
    } else {
      window.localStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    // private browsing mode: the token simply lives in memory for this page load
  }
}

/** Error carrying the HTTP status so callers can distinguish 403 from 400. */
export class ApiError extends Error {
  constructor(message, status, fieldErrors) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors || null;
  }
}

function buildUrl(path, params) {
  const url = `${BASE_URL}${path}`;
  if (!params) {
    return url;
  }
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.append(key, String(value));
    }
  });
  const queryString = query.toString();
  return queryString ? `${url}?${queryString}` : url;
}

async function readError(response) {
  let body = null;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  if (body && body.fieldErrors && Object.keys(body.fieldErrors).length > 0) {
    const details = Object.entries(body.fieldErrors)
      .map(([field, problem]) => `${field}: ${problem}`)
      .join('; ');
    return new ApiError(details, response.status, body.fieldErrors);
  }
  const message =
    (body && (body.message || body.error)) ||
    `${response.status} ${response.statusText || 'Request failed'}`;
  return new ApiError(message, response.status, null);
}

export async function request(path, options = {}) {
  const { method = 'GET', body, params, signal } = options;
  const headers = {};
  const token = getToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  let response;
  try {
    response = await fetch(buildUrl(path, params), {
      method,
      headers,
      signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    if (cause && cause.name === 'AbortError') {
      throw cause;
    }
    throw new ApiError('Cannot reach the backend. Is it running on port 8080?', 0, null);
  }

  if (response.status === 401) {
    setToken(null);
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
    throw await readError(response);
  }
  if (!response.ok) {
    throw await readError(response);
  }
  if (response.status === 204) {
    return null;
  }

  const contentType = response.headers.get('Content-Type') || '';
  if (!contentType.includes('application/json')) {
    return await response.text();
  }
  return await response.json();
}

export const get = (path, params, signal) => request(path, { params, signal });
export const post = (path, body, params) => request(path, { method: 'POST', body, params });
export const patch = (path, params, body) => request(path, { method: 'PATCH', params, body });
export const put = (path, body) => request(path, { method: 'PUT', body });
export const del = (path) => request(path, { method: 'DELETE' });
