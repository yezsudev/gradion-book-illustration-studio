const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api';

export type HealthResponse = { status: string };
export type Identity = { name: string; email: string };
export type ProjectSummary = {
  id: string;
  title: string;
  createdAt: string;
  status: 'Draft';
  completedSteps: number;
  totalSteps: number;
};
export type ProjectDetail = ProjectSummary & { bookText: string };

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
export function getProjects(): Promise<ProjectSummary[]> { return request<ProjectSummary[]>('/projects'); }
export function getProject(id: string): Promise<ProjectDetail> { return request<ProjectDetail>(`/projects/${id}`); }
export function createProject(title: string, bookText: string, file: File | null): Promise<ProjectDetail> {
  const form = new FormData();
  form.append('title', title);
  if (bookText.trim()) form.append('bookText', bookText);
  if (file) form.append('file', file);
  return request<ProjectDetail>('/projects', { method: 'POST', body: form });
}
