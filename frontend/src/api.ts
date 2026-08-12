const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api';

export type HealthResponse = { status: string };

export async function getHealth(): Promise<HealthResponse> {
  const response = await fetch(`${apiBaseUrl}/health`);
  if (!response.ok) throw new Error(`Health check failed: ${response.status}`);
  return response.json() as Promise<HealthResponse>;
}
