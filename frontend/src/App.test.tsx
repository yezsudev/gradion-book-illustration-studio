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

test('shows the empty project list with a create-project entry point', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url === '/api/session') return Promise.resolve({ ok: true, status: 200, json: async () => ({ name: 'Mira Hassan', email: 'mira@example.com' }) });
    if (url === '/api/projects') return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);

  expect(await screen.findByText('No projects yet.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Create project' })).toBeInTheDocument();
});

test('validates that a new project has a title and one book source', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url === '/api/session') return Promise.resolve({ ok: true, status: 200, json: async () => ({ name: 'Mira Hassan', email: 'mira@example.com' }) });
    if (url === '/api/projects') return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);
  await screen.findByRole('button', { name: 'Create project' });
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));

  expect(screen.getByRole('alert')).toHaveTextContent('Enter a project title.');
});

test('shows creation loading while a new project is being saved', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/session') return Promise.resolve({ ok: true, status: 200, json: async () => ({ name: 'Mira Hassan', email: 'mira@example.com' }) });
    if (url === '/api/projects' && init?.method === 'POST') {
      return new Promise(() => undefined);
    }
    if (url === '/api/projects') return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);
  await screen.findByRole('button', { name: 'Create project' });
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
  fireEvent.change(screen.getByLabelText('Project title'), { target: { value: 'River Book' } });
  fireEvent.change(screen.getByLabelText('Paste book text'), { target: { value: 'A complete book.' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
  expect(screen.getByRole('button', { name: 'Creating project…' })).toBeDisabled();
});

test('shows a backend error when new project creation is rejected', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/session') return Promise.resolve({ ok: true, status: 200, json: async () => ({ name: 'Mira Hassan', email: 'mira@example.com' }) });
    if (url === '/api/projects' && init?.method === 'POST') return Promise.resolve({ ok: false, status: 400, json: async () => ({ message: 'Book text is required.' }) });
    if (url === '/api/projects') return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);
  await screen.findByRole('button', { name: 'Create project' });
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
  fireEvent.change(screen.getByLabelText('Project title'), { target: { value: 'River Book' } });
  fireEvent.change(screen.getByLabelText('Paste book text'), { target: { value: 'A complete book.' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Book text is required.');
});

test('navigates to project detail after creating a project', async () => {
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url === '/api/session') return Promise.resolve({ ok: true, status: 200, json: async () => ({ name: 'Mira Hassan', email: 'mira@example.com' }) });
    if (url === '/api/projects' && init?.method === 'POST') return Promise.resolve({ ok: true, status: 201, json: async () => ({ id: 'project-1', title: 'River Book', createdAt: '2026-08-12T00:00:00Z', status: 'Draft', completedSteps: 0, totalSteps: 5, bookText: 'A complete book.' }) });
    if (url === '/api/projects') return Promise.resolve({ ok: true, status: 200, json: async () => [] });
    if (url === '/api/health') return Promise.resolve({ ok: true, status: 200, json: async () => ({ status: 'ok' }) });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  }));

  render(<App />);
  await screen.findByRole('button', { name: 'Create project' });
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
  fireEvent.change(screen.getByLabelText('Project title'), { target: { value: 'River Book' } });
  fireEvent.change(screen.getByLabelText('Paste book text'), { target: { value: 'A complete book.' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
  expect(await screen.findByRole('heading', { name: 'River Book' })).toBeInTheDocument();
  expect(screen.getByText('A complete book.')).toBeInTheDocument();
});
