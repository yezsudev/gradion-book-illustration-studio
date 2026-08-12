const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api';

export type HealthResponse = { status: string };
export type Identity = { name: string; email: string };

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, { credentials: 'include', ...init });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || `Request failed: ${response.status}`);
  return body as T;
}

export async function getHealth(): Promise<HealthResponse> {
  return request<HealthResponse>('/health');
}

export function getSession(): Promise<Identity> { return request<Identity>('/session'); }
export function createSession(name: string, email: string): Promise<Identity> {
  return request<Identity>('/session', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, email }),
  });
}
export function deleteSession(): Promise<void> { return request<void>('/session', { method: 'DELETE' }); }
export function getProjects(): Promise<unknown[]> { return request<unknown[]>('/projects'); }
