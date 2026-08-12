import { FormEvent, useEffect, useRef, useState } from "react";
import {
  createProject,
  createSession,
  deleteSession,
  getHealth,
  getProject,
  getProjects,
  getSession,
  Identity,
  ProjectDetail,
  ProjectStep,
  ProjectSummary,
  recoverStep,
  runStep,
} from "./api";
import "./App.css";

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const STEP_LOADING_MS = 1000;
function stepName(key: ProjectStep["key"]) {
  return key.charAt(0) + key.slice(1).toLowerCase();
}

function Stepper({
  steps,
  runningStep,
}: {
  steps: ProjectStep[];
  runningStep: ProjectStep["key"] | null;
}) {
  return (
    <ol className="stepper" aria-label="Project progress">
      {steps.map((step, index) => {
        const state = runningStep === step.key ? "RUNNING" : step.state;
        const label =
          state === "COMPLETED"
            ? "Completed"
            : state === "RUNNING"
              ? "Running"
              : state === "FAILED"
                ? "Failed"
                : step.canRun
                  ? "Current"
                  : "Pending";
        return (
          <li key={step.key} className={label.toLowerCase()}>
            <span>{index + 1}</span>
            <div>
              {stepName(step.key)}
              <small>{label}</small>
            </div>
          </li>
        );
      })}
    </ol>
  );
}

function createdDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(
    new Date(value),
  );
}

function readFileText(file: File): Promise<string> {
  if (typeof file.text === "function") return file.text();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(reader.error || new Error("Could not read file."));
    reader.readAsText(file);
  });
}

export default function App() {
  const [user, setUser] = useState<Identity | null | undefined>(undefined);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [identityError, setIdentityError] = useState("");
  const [signingIn, setSigningIn] = useState(false);
  const [projects, setProjects] = useState<ProjectSummary[] | undefined>(
    undefined,
  );
  const [projectError, setProjectError] = useState("");
  const [view, setView] = useState<"list" | "create" | "detail">("list");
  const [selectedProject, setSelectedProject] = useState<
    ProjectDetail | undefined
  >(undefined);
  const [title, setTitle] = useState("");
  const [bookText, setBookText] = useState("");
  const [bookFile, setBookFile] = useState<File | null>(null);
  const [importedBookText, setImportedBookText] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [createError, setCreateError] = useState("");
  const [creating, setCreating] = useState(false);
  const [stepAction, setStepAction] = useState<ProjectStep["key"] | null>(null);
  const [stepError, setStepError] = useState("");
  const [styleInput, setStyleInput] = useState("");

  useEffect(() => {
    getHealth().catch(() => undefined);
  }, []);
  useEffect(() => {
    getSession()
      .then(setUser)
      .catch(() => setUser(null));
  }, []);
  useEffect(() => {
    if (!user) return;
    setProjects(undefined);
    setProjectError("");
    getProjects()
      .then((loadedProjects) => {
        setProjects(loadedProjects);
        const projectId = new URLSearchParams(
          window.location.hash.slice(1),
        ).get("project");
        if (projectId) openProject(projectId, false);
      })
      .catch((cause) => {
        setProjectError(
          cause instanceof Error ? cause.message : "Could not load projects.",
        );
        setProjects([]);
      });
  }, [user]);
  useEffect(() => {
    function handleHistory() {
      const projectId = new URLSearchParams(window.location.hash.slice(1)).get(
        "project",
      );
      if (projectId) {
        openProject(projectId, false);
      } else {
        setSelectedProject(undefined);
        setView("list");
      }
    }
    window.addEventListener("popstate", handleHistory);
    return () => window.removeEventListener("popstate", handleHistory);
  }, []);
  useEffect(() => {
    const projectId = selectedProject?.id;
    if (
      !projectId ||
      !selectedProject.steps.some((step) => step.state === "RUNNING")
    )
      return;
    const timer = window.setInterval(
      () =>
        getProject(projectId)
          .then(setSelectedProject)
          .catch(() => undefined),
      2000,
    );
    return () => window.clearInterval(timer);
  }, [selectedProject]);

  async function signIn(event: FormEvent) {
    event.preventDefault();
    const trimmedName = name.trim();
    const normalizedEmail = email.trim().toLowerCase();
    if (!trimmedName || !emailPattern.test(normalizedEmail)) {
      setIdentityError("Enter a name and valid email.");
      return;
    }
    setIdentityError("");
    setSigningIn(true);
    try {
      setUser(await createSession(trimmedName, normalizedEmail));
    } catch (cause) {
      setIdentityError(
        cause instanceof Error ? cause.message : "Could not sign in.",
      );
    } finally {
      setSigningIn(false);
    }
  }

  async function signOut() {
    await deleteSession().catch(() => undefined);
    setUser(null);
    setProjects(undefined);
    setSelectedProject(undefined);
    setView("list");
    setName("");
    setEmail("");
    setIdentityError("");
    window.history.replaceState({}, "", window.location.pathname);
  }

  function showCreate() {
    setView("create");
    setCreateError("");
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    const projectTitle = title.trim();
    const pastedText = bookFile ? "" : bookText.trim();
    if (!projectTitle) {
      setCreateError("Enter a project title.");
      return;
    }
    if (
      bookFile &&
      (!bookFile.name.toLowerCase().endsWith(".txt") || bookFile.size === 0)
    ) {
      setCreateError("Upload a non-empty .txt file.");
      return;
    }
    if (Boolean(pastedText) === Boolean(bookFile)) {
      setCreateError(
        pastedText
          ? "Provide either pasted book text or a .txt file, not both."
          : "Provide pasted book text or a non-empty .txt file.",
      );
      return;
    }
    setCreateError("");
    setCreating(true);
    try {
      const project = await createProject(projectTitle, pastedText, bookFile);
      setSelectedProject(project);
      setProjects((current) => (current ? [project, ...current] : [project]));
      setView("detail");
      setTitle("");
      setBookText("");
      setBookFile(null);
      setImportedBookText("");
      setStepAction(null);
      window.history.replaceState({}, "", `#project=${project.id}`);
    } catch (cause) {
      setCreateError(
        cause instanceof Error
          ? cause.message
          : "Could not create the project.",
      );
    } finally {
      setCreating(false);
    }
  }

  async function openProject(id: string, updateHistory = true) {
    setSelectedProject(undefined);
    setStepAction(null);
    setView("detail");
    if (updateHistory) window.history.pushState({}, "", `#project=${id}`);
    try {
      setSelectedProject(await getProject(id));
    } catch (cause) {
      setProjectError(
        cause instanceof Error ? cause.message : "Could not load the project.",
      );
      setView("list");
    }
  }

  async function executeStep(step: ProjectStep, recover = false) {
    if (!selectedProject) return;
    setStepAction(step.key);
    setStepError("");
    try {
      const pipelineRequest = recover
        ? recoverStep(selectedProject.id, step.key)
        : runStep(
            selectedProject.id,
            step.key,
            step.key === "STYLE" ? styleInput : undefined,
          );
      const [pipeline] = await Promise.all([
        pipelineRequest,
        new Promise((resolve) => window.setTimeout(resolve, STEP_LOADING_MS)),
      ]);
      setSelectedProject((current) =>
        current ? { ...current, ...pipeline } : current,
      );
    } catch (cause) {
      setStepError(
        cause instanceof Error ? cause.message : "Could not run this step.",
      );
    } finally {
      setStepAction(null);
    }
  }

  if (user === undefined)
    return (
      <main className="page-shell">
        <p className="loading">Loading your workspace...</p>
      </main>
    );
  if (!user)
    return (
      <main className="page-shell">
        <nav className="topbar" aria-label="Main navigation">
          <span className="brand">GRADION</span>
          <span className="nav-label">Book Illustration Studio</span>
        </nav>
        <section className="content-card" aria-labelledby="identity-title">
          <p className="eyebrow">Book Illustration Studio</p>
          <h1 id="identity-title">Welcome to Book Illustration Studio</h1>
          <p className="lede">
            Enter your details to continue to your projects.
          </p>
          <form onSubmit={signIn} noValidate>
            <label>
              Full name
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                autoComplete="name"
              />
            </label>
            <label>
              Email
              <input
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
              />
            </label>
            {identityError && (
              <p role="alert" className="error">
                {identityError}
              </p>
            )}
            <button type="submit" disabled={signingIn}>
              {signingIn ? "Signing in…" : "Continue"}
            </button>
          </form>
        </section>
      </main>
    );

  return (
    <main className="page-shell">
      <nav className="topbar" aria-label="Main navigation">
        <div className="topbar-left">
          <span className="brand">GRADION</span>
          <span className="nav-label">Projects</span>
        </div>
        <div className="topbar-account">
          <span className="avatar" aria-hidden="true">{user.name.charAt(0).toUpperCase()}</span>
          <span>{user.name}</span>
          <button type="button" className="topbar-signout" onClick={signOut}>Sign out</button>
        </div>
      </nav>
      {view === "create" ? (
        <section className="content-card" aria-labelledby="create-title">
          <button
            type="button"
            className="text-button"
            onClick={() => {
              setView("list");
              window.history.replaceState({}, "", window.location.pathname);
            }}
          >
            Back to projects
          </button>
          <p className="eyebrow">New project</p>
          <h1 id="create-title">Add your book</h1>
          <p className="lede">
            Upload one <code>.txt</code> file or paste the complete book text
            below.
          </p>
          <form onSubmit={create} noValidate>
            <label>
              Project title
              <input
                value={title}
                onChange={(event) => setTitle(event.target.value)}
              />
            </label>
            <label>
              Upload .txt file
              <input
                ref={fileInputRef}
                type="file"
                accept=".txt,text/plain"
                onChange={async (event) => {
                  const file = event.target.files?.[0] || null;
                  if (!file) return;
                  setBookFile(file);
                  const text = await readFileText(file);
                  setImportedBookText(text);
                  setBookText(text);
                }}
              />
            </label>
            {bookFile && (
              <div className="file-selection">
                <span>{bookFile.name}</span>
                <button
                  type="button"
                  className="secondary remove-file"
                  onClick={() => {
                    setBookFile(null);
                    if (bookText === importedBookText) setBookText("");
                    setImportedBookText("");
                    if (fileInputRef.current) fileInputRef.current.value = "";
                  }}
                >
                  Remove file
                </button>
              </div>
            )}
            <p className="source-divider">or paste text</p>
            <label>
              Paste book text
              <textarea
                value={bookText}
                onChange={(event) => setBookText(event.target.value)}
                rows={8}
              />
            </label>
            {createError && (
              <p role="alert" className="error">
                {createError}
              </p>
            )}
            <button type="submit" disabled={creating}>
              {creating ? "Creating project…" : "Create project"}
            </button>
          </form>
        </section>
      ) : view === "detail" ? (
        <section className="content-card project-detail" aria-live="polite">
          {selectedProject ? (
            <>
              <button
                type="button"
                className="text-button"
                onClick={() => {
                  setView("list");
                  window.history.replaceState({}, "", window.location.pathname);
                }}
              >
                Back to projects
              </button>
              <p className="eyebrow">
                {selectedProject.status} ·{" "}
                {createdDate(selectedProject.createdAt)}
              </p>
              <p className="detail-meta">
                Created {createdDate(selectedProject.createdAt)} by {user.name}
              </p>
              <h1>{selectedProject.title}</h1>
              <Stepper steps={selectedProject.steps} runningStep={stepAction} />
              <section className="step-action" aria-label="Pipeline action">
                {selectedProject.steps
                  .filter(
                    (step) =>
                      step.state === "RUNNING" || stepAction === step.key,
                  )
                  .map((step) => (
                    <div key={step.key}>
                      <p className="running" aria-hidden="true">
                        <span className="spinner" aria-hidden="true" />
                        {stepName(step.key)} is running…
                      </p>
                      <p role="status" className="running">
                        <span className="spinner" aria-hidden="true" />
                        {step.key === "PORTRAITS"
                          ? "Hugging Face is creating portraits..."
                          : step.key === "ILLUSTRATIONS"
                            ? "Hugging Face is creating the illustration..."
                            : `${stepName(step.key)} is running...`}
                      </p>
                    </div>
                  ))}
                {selectedProject.steps
                  .filter((step) => step.state === "FAILED" && step.error)
                  .map((step) => (
                    <p key={step.key} role="alert" className="error">
                      {step.error}
                    </p>
                  ))}
                {stepError && (
                  <p role="alert" className="error">
                    {stepError}
                  </p>
                )}
                {selectedProject.steps[0]?.canRun ||
                selectedProject.steps[0]?.canRetry ? (
                  <label>
                    Optional style
                    <textarea
                      value={styleInput}
                      onChange={(event) => setStyleInput(event.target.value)}
                      rows={3}
                      placeholder="Leave blank to generate a style from the book."
                    />
                  </label>
                ) : null}
                {selectedProject.steps
                  .filter(
                    (step) => step.canRun || step.canRetry || step.canRecover,
                  )
                  .map((step) => (
                    <button
                      key={step.key}
                      type="button"
                      disabled={stepAction !== null}
                      onClick={() => executeStep(step, step.canRecover)}
                    >
                      {stepAction === step.key ? (
                        <>
                          <span className="spinner" aria-hidden="true" />{" "}
                          {stepName(step.key)} is running…
                        </>
                      ) : step.canRecover ? (
                        `Recover ${stepName(step.key)}`
                      ) : step.canRetry ? (
                        `Retry ${stepName(step.key)}`
                      ) : (
                        `Run ${stepName(step.key)}`
                      )}
                    </button>
                  ))}
              </section>
              <details className="book-details">
                <summary>Original book text</summary>
                <pre className="book-text">{selectedProject.bookText}</pre>
              </details>
            </>
          ) : (
            <p className="loading">Loading project...</p>
          )}
        </section>
      ) : (
        <section className="content-card" aria-labelledby="projects-title">
          <div className="account">
            <div>
              <h1 id="projects-title">Your projects</h1>
              <p>
                {user.name} · {user.email}
              </p>
            </div>
            <div className="actions">
              <button type="button" onClick={showCreate}>
                Create project
              </button>
            </div>
          </div>
          {projectError && (
            <p role="alert" className="error">
              {projectError}
            </p>
          )}
          {projects === undefined ? (
            <p>Loading projects...</p>
          ) : projects.length === 0 ? (
            <p className="empty-state">No projects yet.</p>
          ) : (
            <ul className="project-list">
              {projects.map((project) => (
                <li key={project.id}>
                  <button
                    type="button"
                    className="project-card"
                    onClick={() => openProject(project.id)}
                  >
                    <strong>{project.title}</strong>
                    <span>
                      {createdDate(project.createdAt)} · {project.status} ·{" "}
                      {project.completedSteps}/{project.totalSteps} steps
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
      {view === "detail" && selectedProject && (
        <section className="generated-results">
          {selectedProject.style && (
            <section className="style-summary">
              <h2>Style</h2>
              <p>{selectedProject.style}</p>
            </section>
          )}
          {selectedProject.characters?.length > 0 && (
            <section>
              <h2>Characters</h2>
              <div className="character-cards">
                {selectedProject.characters.map((character) => (
                  <article key={character.name}>
                    <h3>{character.name}</h3>
                    <p>{character.prompt}</p>
                    {character.portraitStatus === "COMPLETED" && character.portraitUrl ? (
                      <img src={character.portraitUrl} alt={`${character.name} portrait`} />
                    ) : character.portraitStatus === "FAILED" ? (
                      <p role="alert" className="error">{character.portraitError || "Portrait generation failed."}</p>
                    ) : (
                      <p className="running">Portrait {(character.portraitStatus || "PENDING").toLowerCase()}</p>
                    )}
                  </article>
                ))}
              </div>
            </section>
          )}
          {selectedProject.chapter && (
            <section>
              <h2>Chapter</h2>
              <article>
                <h3>{selectedProject.chapter.title}</h3>
                <p>{selectedProject.chapter.prompt}</p>
              </article>
              {selectedProject.illustration?.status === "COMPLETED" && selectedProject.illustration.illustrationUrl && (
                <img src={selectedProject.illustration.illustrationUrl} alt={`${selectedProject.chapter.title} illustration`} />
              )}
              {selectedProject.illustration?.status === "FAILED" && <p role="alert" className="error">{selectedProject.illustration.error || "Illustration generation failed."}</p>}
            </section>
          )}
        </section>
      )}
    </main>
  );
}
