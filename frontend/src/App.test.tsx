import { render, screen } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import App from './App';

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ status: 'ok' }),
  }));
});

test('shows the backend connected state after the health check', async () => {
  render(<App />);

  expect(screen.getByRole('status')).toHaveTextContent('Checking backend');
  expect(await screen.findByRole('status')).toHaveTextContent('Backend connected');
  expect(fetch).toHaveBeenCalledWith('/api/health');
});
