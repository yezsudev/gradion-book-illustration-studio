import { FormEvent, useEffect, useState } from 'react';
import { createProject, createSession, deleteSession, getHealth, getProject, getProjects, getSession, Identity, ProjectDetail, ProjectSummary } from './api';
import './App.css';

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const steps = ['Style', 'Characters', 'Portraits', 'Chapters', 'Illustrations'];

function Stepper() {
  return <ol className="stepper" aria-label="Project progress">
    {steps.map((step, index) => <li key={step} className={index === 0 ? 'current' : 'pending'}><span>{index + 1}</span>{step}</li>)}
  </ol>;
}

function createdDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(value));
}

export default function App() {
  const [user, setUser] = useState<Identity | null | undefined>(undefined);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [identityError, setIdentityError] = useState('');
  const [signingIn, setSigningIn] = useState(false);
  const [projects, setProjects] = useState<ProjectSummary[] | undefined>(undefined);
  const [projectError, setProjectError] = useState('');
  const [view, setView] = useState<'list' | 'create' | 'detail'>('list');
  const [selectedProject, setSelectedProject] = useState<ProjectDetail | undefined>(undefined);
  const [title, setTitle] = useState('');
  const [bookText, setBookText] = useState('');
  const [bookFile, setBookFile] = useState<File | null>(null);
  const [createError, setCreateError] = useState('');
  const [creating, setCreating] = useState(false);

  useEffect(() => { getHealth().catch(() => undefined); }, []);
  useEffect(() => { getSession().then(setUser).catch(() => setUser(null)); }, []);
  useEffect(() => {
    if (!user) return;
    setProjects(undefined);
    setProjectError('');
    getProjects().then(setProjects).catch((cause) => {
      setProjectError(cause instanceof Error ? cause.message : 'Could not load projects.');
      setProjects([]);
    });
  }, [user]);

  async function signIn(event: FormEvent) {
    event.preventDefault();
    const trimmedName = name.trim();
    const normalizedEmail = email.trim().toLowerCase();
    if (!trimmedName || !emailPattern.test(normalizedEmail)) {
      setIdentityError('Enter a name and valid email.');
      return;
    }
    setIdentityError('');
    setSigningIn(true);
    try {
      setUser(await createSession(trimmedName, normalizedEmail));
    } catch (cause) {
      setIdentityError(cause instanceof Error ? cause.message : 'Could not sign in.');
    } finally {
      setSigningIn(false);
    }
  }

  async function signOut() {
    await deleteSession().catch(() => undefined);
    setUser(null);
    setProjects(undefined);
    setSelectedProject(undefined);
    setView('list');
    setName('');
    setEmail('');
    setIdentityError('');
  }

  function showCreate() {
    setView('create');
    setCreateError('');
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    const projectTitle = title.trim();
    const pastedText = bookText.trim();
    if (!projectTitle) {
      setCreateError('Enter a project title.');
      return;
    }
    if (bookFile && (!bookFile.name.toLowerCase().endsWith('.txt') || bookFile.size === 0)) {
      setCreateError('Upload a non-empty .txt file.');
      return;
    }
    if (Boolean(pastedText) === Boolean(bookFile)) {
      setCreateError(pastedText ? 'Provide either pasted book text or a .txt file, not both.' : 'Provide pasted book text or a non-empty .txt file.');
      return;
    }
    setCreateError('');
    setCreating(true);
    try {
      const project = await createProject(projectTitle, bookText, bookFile);
      setSelectedProject(project);
      setProjects((current) => current ? [project, ...current] : [project]);
      setView('detail');
      setTitle('');
      setBookText('');
      setBookFile(null);
    } catch (cause) {
      setCreateError(cause instanceof Error ? cause.message : 'Could not create the project.');
    } finally {
      setCreating(false);
    }
  }

  async function openProject(id: string) {
    setSelectedProject(undefined);
    setView('detail');
    try {
      setSelectedProject(await getProject(id));
    } catch (cause) {
      setProjectError(cause instanceof Error ? cause.message : 'Could not load the project.');
      setView('list');
    }
  }

  if (user === undefined) return <main className="page-shell"><p className="loading">Loading your workspace...</p></main>;
  if (!user) return <main className="page-shell"><nav className="topbar" aria-label="Main navigation"><span className="brand">GRADION</span><span className="nav-label">Book Illustration Studio</span></nav><section className="content-card" aria-labelledby="identity-title">
    <p className="eyebrow">Book Illustration Studio</p><h1 id="identity-title">Welcome to Book Illustration Studio</h1><p className="lede">Enter your details to continue to your projects.</p>
    <form onSubmit={signIn} noValidate><label>Full name<input value={name} onChange={(event) => setName(event.target.value)} autoComplete="name" /></label><label>Email<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="email" /></label>{identityError && <p role="alert" className="error">{identityError}</p>}<button type="submit" disabled={signingIn}>{signingIn ? 'Signing in…' : 'Continue'}</button></form>
  </section></main>;

  return <main className="page-shell"><nav className="topbar" aria-label="Main navigation"><span className="brand">GRADION</span><span className="nav-label">Book Illustration Studio</span></nav>
    {view === 'create' ? <section className="content-card" aria-labelledby="create-title"><button type="button" className="text-button" onClick={() => setView('list')}>Back to projects</button><p className="eyebrow">New project</p><h1 id="create-title">Add your book</h1><p className="lede">Upload one <code>.txt</code> file or paste the complete book text below.</p>
      <form onSubmit={create} noValidate><label>Project title<input value={title} onChange={(event) => setTitle(event.target.value)} /></label><label>Upload .txt file<input type="file" accept=".txt,text/plain" onChange={(event) => setBookFile(event.target.files?.[0] || null)} /></label><p className="source-divider">or paste text</p><label>Paste book text<textarea value={bookText} onChange={(event) => setBookText(event.target.value)} rows={8} /></label>{createError && <p role="alert" className="error">{createError}</p>}<button type="submit" disabled={creating}>{creating ? 'Creating project…' : 'Create project'}</button></form>
    </section> : view === 'detail' ? <section className="content-card" aria-live="polite">{selectedProject ? <><button type="button" className="text-button" onClick={() => setView('list')}>Back to projects</button><p className="eyebrow">{selectedProject.status} · {createdDate(selectedProject.createdAt)}</p><h1>{selectedProject.title}</h1><Stepper /><h2>Original book text</h2><pre className="book-text">{selectedProject.bookText}</pre></> : <p className="loading">Loading project...</p>}</section> : <section className="content-card" aria-labelledby="projects-title"><div className="account"><div><h1 id="projects-title">Your projects</h1><p>{user.name} · {user.email}</p></div><div className="actions"><button type="button" onClick={showCreate}>Create project</button><button type="button" className="secondary" onClick={signOut}>Sign out</button></div></div>
      {projectError && <p role="alert" className="error">{projectError}</p>}{projects === undefined ? <p>Loading projects...</p> : projects.length === 0 ? <p className="empty-state">No projects yet.</p> : <ul className="project-list">{projects.map((project) => <li key={project.id}><button type="button" className="project-card" onClick={() => openProject(project.id)}><strong>{project.title}</strong><span>{createdDate(project.createdAt)} · {project.status} · {project.completedSteps}/{project.totalSteps} steps</span></button></li>)}</ul>}
    </section>}
  </main>;
}
