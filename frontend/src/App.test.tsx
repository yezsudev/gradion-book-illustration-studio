import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import App from './App';

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url === '/api/session') return Promise.resolve({ ok: false, status: 401, json: async () => ({ message: 'No session' }) });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));
});

test('shows validation instead of submitting an incomplete identity form', async () => {
  render(<App />);

  await screen.findByRole('heading', { name: 'Welcome to Book Illustration Studio' });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

  expect(screen.getByRole('alert')).toHaveTextContent('Enter a name and valid email.');
  expect(fetch).not.toHaveBeenCalledWith('/api/session', expect.objectContaining({ method: 'POST' }));
});

test('shows signing in while identity is being submitted', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/session' && init?.method === 'POST') {
      return new Promise(() => undefined);
    }
    if (url === '/api/session') return Promise.resolve({ ok: false, status: 401, json: async () => ({ message: 'No session' }) });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);
  await screen.findByRole('heading', { name: 'Welcome to Book Illustration Studio' });
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Mira Hassan' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'mira@example.com' } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

  expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
});

test('shows an API error after identity is rejected', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/session' && init?.method === 'POST') {
      return Promise.resolve({ ok: false, status: 400, json: async () => ({ message: 'Could not sign in.' }) });
    }
    if (url === '/api/session') return Promise.resolve({ ok: false, status: 401, json: async () => ({ message: 'No session' }) });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);
  await screen.findByRole('heading', { name: 'Welcome to Book Illustration Studio' });
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Mira Hassan' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'mira@example.com' } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('Could not sign in.');
});

test('enters the empty project list after a successful identity response', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/session' && init?.method === 'POST') {
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ name: 'Mira Hassan', email: 'mira@example.com' }) });
    }
    if (url === '/api/session') return Promise.resolve({ ok: false, status: 401, json: async () => ({ message: 'No session' }) });
    if (url === '/api/projects') return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);
  await screen.findByRole('heading', { name: 'Welcome to Book Illustration Studio' });
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: 'Mira Hassan' } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'mira@example.com' } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

  expect(await screen.findByRole('heading', { name: 'Your projects' })).toBeInTheDocument();
  expect(screen.getByText(/mira@example\.com/)).toBeInTheDocument();
  expect(screen.getByText('No projects yet.')).toBeInTheDocument();
});
