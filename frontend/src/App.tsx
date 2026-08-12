import { FormEvent, useEffect, useState } from 'react';
import { createSession, deleteSession, getHealth, getProjects, getSession, Identity } from './api';
import './App.css';

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function App() {
  const [user, setUser] = useState<Identity | null | undefined>(undefined);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [projects, setProjects] = useState<unknown[] | undefined>(undefined);

  useEffect(() => { getHealth().catch(() => undefined); }, []);
  useEffect(() => {
    getSession().then(setUser).catch(() => setUser(null));
  }, []);
  useEffect(() => {
    if (user) getProjects().then(setProjects).catch(() => setProjects([]));
  }, [user]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmedName = name.trim();
    const normalizedEmail = email.trim().toLowerCase();
    if (!trimmedName || !emailPattern.test(normalizedEmail)) {
      setError('Enter a name and valid email.');
      return;
    }
    setError('');
    setSubmitting(true);
    try {
      setUser(await createSession(trimmedName, normalizedEmail));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not sign in.');
    } finally {
      setSubmitting(false);
    }
  }

  async function signOut() {
    await deleteSession().catch(() => undefined);
    setUser(null);
    setProjects(undefined);
    setName('');
    setEmail('');
    setError('');
  }

  if (user === undefined) return <main className="page-shell"><p className="loading">Loading your workspace...</p></main>;

  return <main className="page-shell">
    <nav className="topbar" aria-label="Main navigation"><span className="brand">GRADION</span><span className="nav-label">Book Illustration Studio</span></nav>
    {user ? <section className="content-card" aria-labelledby="projects-title">
      <div className="account"><div><h1 id="projects-title">Your projects</h1><p>{user.name} · {user.email}</p></div><button type="button" className="secondary" onClick={signOut}>Sign out</button></div>
      {projects === undefined ? <p>Loading projects...</p> : <p className="empty-state">No projects yet.</p>}
    </section> : <section className="content-card" aria-labelledby="identity-title">
      <p className="eyebrow">Book Illustration Studio</p><h1 id="identity-title">Welcome to Book Illustration Studio</h1>
      <p className="lede">Enter your details to continue to your projects.</p>
      <form onSubmit={submit} noValidate>
        <label>Full name<input value={name} onChange={(event) => setName(event.target.value)} autoComplete="name" /></label>
        <label>Email<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="email" /></label>
        {error && <p role="alert" className="error">{error}</p>}
        <button type="submit" disabled={submitting}>{submitting ? 'Signing in…' : 'Continue'}</button>
      </form>
    </section>}
  </main>;
}
