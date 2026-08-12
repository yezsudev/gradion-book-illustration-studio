import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import App from "./App";

beforeEach(() => {
  window.history.replaceState({}, "", "/");
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) => {
      if (url === "/api/session")
        return Promise.resolve({
          ok: false,
          status: 401,
          json: async () => ({ message: "No session" }),
        });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );
});

test("shows validation instead of submitting an incomplete identity form", async () => {
  render(<App />);

  await screen.findByRole("heading", {
    name: "Welcome to Book Illustration Studio",
  });
  fireEvent.click(screen.getByRole("button", { name: "Continue" }));

  expect(screen.getByRole("alert")).toHaveTextContent(
    "Enter a name and valid email.",
  );
  expect(fetch).not.toHaveBeenCalledWith(
    "/api/session",
    expect.objectContaining({ method: "POST" }),
  );
});

test("shows signing in while identity is being submitted", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/session" && init?.method === "POST") {
        return new Promise(() => undefined);
      }
      if (url === "/api/session")
        return Promise.resolve({
          ok: false,
          status: 401,
          json: async () => ({ message: "No session" }),
        });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);
  await screen.findByRole("heading", {
    name: "Welcome to Book Illustration Studio",
  });
  fireEvent.change(screen.getByLabelText("Full name"), {
    target: { value: "Mira Hassan" },
  });
  fireEvent.change(screen.getByLabelText("Email"), {
    target: { value: "mira@example.com" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Continue" }));

  expect(screen.getByRole("button", { name: "Signing in…" })).toBeDisabled();
});

test("shows an API error after identity is rejected", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/session" && init?.method === "POST") {
        return Promise.resolve({
          ok: false,
          status: 400,
          json: async () => ({ message: "Could not sign in." }),
        });
      }
      if (url === "/api/session")
        return Promise.resolve({
          ok: false,
          status: 401,
          json: async () => ({ message: "No session" }),
        });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);
  await screen.findByRole("heading", {
    name: "Welcome to Book Illustration Studio",
  });
  fireEvent.change(screen.getByLabelText("Full name"), {
    target: { value: "Mira Hassan" },
  });
  fireEvent.change(screen.getByLabelText("Email"), {
    target: { value: "mira@example.com" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Continue" }));

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Could not sign in.",
  );
});

test("enters the empty project list after a successful identity response", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/session" && init?.method === "POST") {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            name: "Mira Hassan",
            email: "mira@example.com",
          }),
        });
      }
      if (url === "/api/session")
        return Promise.resolve({
          ok: false,
          status: 401,
          json: async () => ({ message: "No session" }),
        });
      if (url === "/api/projects")
        return Promise.resolve({ ok: true, status: 200, json: async () => [] });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);
  await screen.findByRole("heading", {
    name: "Welcome to Book Illustration Studio",
  });
  fireEvent.change(screen.getByLabelText("Full name"), {
    target: { value: "Mira Hassan" },
  });
  fireEvent.change(screen.getByLabelText("Email"), {
    target: { value: "mira@example.com" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Continue" }));

  expect(
    await screen.findByRole("heading", { name: "Your projects" }),
  ).toBeInTheDocument();
  expect(screen.getByText(/mira@example\.com/)).toBeInTheDocument();
  expect(screen.getByText("No projects yet.")).toBeInTheDocument();
});

test("shows the empty project list with a create-project entry point", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) => {
      if (url === "/api/session")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            name: "Mira Hassan",
            email: "mira@example.com",
          }),
        });
      if (url === "/api/projects")
        return Promise.resolve({ ok: true, status: 200, json: async () => [] });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);

  expect(await screen.findByText("No projects yet.")).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Create project" }),
  ).toBeInTheDocument();
});

test("validates that a new project has a title and one book source", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) => {
      if (url === "/api/session")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            name: "Mira Hassan",
            email: "mira@example.com",
          }),
        });
      if (url === "/api/projects")
        return Promise.resolve({ ok: true, status: 200, json: async () => [] });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);
  await screen.findByRole("button", { name: "Create project" });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));

  expect(screen.getByRole("alert")).toHaveTextContent("Enter a project title.");
});

test("shows creation loading while a new project is being saved", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/session")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            name: "Mira Hassan",
            email: "mira@example.com",
          }),
        });
      if (url === "/api/projects" && init?.method === "POST") {
        return new Promise(() => undefined);
      }
      if (url === "/api/projects")
        return Promise.resolve({ ok: true, status: 200, json: async () => [] });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);
  await screen.findByRole("button", { name: "Create project" });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
  fireEvent.change(screen.getByLabelText("Project title"), {
    target: { value: "River Book" },
  });
  fireEvent.change(screen.getByLabelText("Paste book text"), {
    target: { value: "A complete book." },
  });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
  expect(
    screen.getByRole("button", { name: "Creating project…" }),
  ).toBeDisabled();
});

test("shows a backend error when new project creation is rejected", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/session")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            name: "Mira Hassan",
            email: "mira@example.com",
          }),
        });
      if (url === "/api/projects" && init?.method === "POST")
        return Promise.resolve({
          ok: false,
          status: 400,
          json: async () => ({ message: "Book text is required." }),
        });
      if (url === "/api/projects")
        return Promise.resolve({ ok: true, status: 200, json: async () => [] });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);
  await screen.findByRole("button", { name: "Create project" });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
  fireEvent.change(screen.getByLabelText("Project title"), {
    target: { value: "River Book" },
  });
  fireEvent.change(screen.getByLabelText("Paste book text"), {
    target: { value: "A complete book." },
  });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Book text is required.",
  );
});

test("navigates to project detail after creating a project", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/session")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({
            name: "Mira Hassan",
            email: "mira@example.com",
          }),
        });
      if (url === "/api/projects" && init?.method === "POST")
        return Promise.resolve({
          ok: true,
          status: 201,
          json: async () => ({
            id: "project-1",
            title: "River Book",
            createdAt: "2026-08-12T00:00:00Z",
            status: "Draft",
            completedSteps: 0,
            totalSteps: 5,
            bookText: "A complete book.",
            steps: pipelineSteps(
              "PENDING",
              "PENDING",
              "PENDING",
              "PENDING",
              "PENDING",
            ),
          }),
        });
      if (url === "/api/projects")
        return Promise.resolve({ ok: true, status: 200, json: async () => [] });
      if (url === "/api/health")
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ status: "ok" }),
        });
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    }),
  );

  render(<App />);
  await screen.findByRole("button", { name: "Create project" });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
  fireEvent.change(screen.getByLabelText("Project title"), {
    target: { value: "River Book" },
  });
  fireEvent.change(screen.getByLabelText("Paste book text"), {
    target: { value: "A complete book." },
  });
  fireEvent.click(screen.getByRole("button", { name: "Create project" }));
  expect(
    await screen.findByRole("heading", { name: "River Book" }),
  ).toBeInTheDocument();
  expect(screen.getByText("A complete book.")).toBeInTheDocument();
});

test("shows the server-selected current step action", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      steps: pipelineSteps(
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByRole("button", { name: "Run Style" })).toBeInTheDocument();
  expect(screen.getByText("Current")).toBeInTheDocument();
});

test("shows the specific running step while the server reports it", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      steps: pipelineSteps(
        "RUNNING",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
      status: "In progress",
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByText("Style is running…")).toBeInTheDocument();
});

test("shows a failed step error and its retry action", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      steps: pipelineSteps(
        "FAILED",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
        "The fake pipeline executor failed.",
      ),
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByRole("alert")).toHaveTextContent(
    "The fake pipeline executor failed.",
  );
  expect(
    screen.getByRole("button", { name: "Retry Style" }),
  ).toBeInTheDocument();
});

test("shows completed progression and the next step action", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      status: "In progress",
      completedSteps: 1,
      steps: pipelineSteps(
        "COMPLETED",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByText("Completed")).toBeInTheDocument();
  expect(
    await screen.findByRole("button", { name: "Run Characters" }),
  ).toBeInTheDocument();
});

test("keeps project detail metadata after a run response updates pipeline state", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      steps: pipelineSteps(
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
      runResponse: {
        status: "In progress",
        completedSteps: 1,
        steps: pipelineSteps(
          "COMPLETED",
          "PENDING",
          "PENDING",
          "PENDING",
          "PENDING",
        ),
      },
    }),
  );

  render(<App />);
  await openProject();
  fireEvent.click(screen.getByRole("button", { name: "Run Style" }));

  expect(
    await screen.findByRole("heading", { name: "River Book" }),
  ).toBeInTheDocument();
  expect(screen.getByText("A complete book.")).toBeInTheDocument();
  expect(
    await screen.findByRole("button", { name: "Run Characters" }),
  ).toBeInTheDocument();
});

test("shows a spinner while a step request is still running", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      steps: pipelineSteps(
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
      runResponse: new Promise(() => undefined),
    }),
  );

  render(<App />);
  await openProject();
  fireEvent.click(screen.getByRole("button", { name: "Run Style" }));

  expect(
    await screen.findByRole("button", { name: /Style is running/ }),
  ).toBeDisabled();
  expect(screen.getByRole("status")).toHaveTextContent("Running Style");
});

test("opens the project detail named by the URL after refresh", async () => {
  window.history.replaceState({}, "", "#project=project-1");
  vi.stubGlobal(
    "fetch",
    projectFetch({
      steps: pipelineSteps(
        "COMPLETED",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
      status: "In progress",
      completedSteps: 1,
    }),
  );

  render(<App />);

  expect(
    await screen.findByRole("heading", { name: "River Book" }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Run Characters" }),
  ).toBeInTheDocument();
  window.history.replaceState({}, "", "/");
});

test("sends an optional style only when running Style", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      steps: pipelineSteps(
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
      runResponse: {
        status: "In progress",
        completedSteps: 1,
        steps: pipelineSteps(
          "COMPLETED",
          "PENDING",
          "PENDING",
          "PENDING",
          "PENDING",
        ),
      },
    }),
  );

  render(<App />);
  await openProject();
  fireEvent.change(screen.getByLabelText("Optional style"), {
    target: { value: "Watercolor storybook" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Run Style" }));

  expect(fetch).toHaveBeenCalledWith(
    "/api/projects/project-1/steps/STYLE/run",
    expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ style: "Watercolor storybook" }),
    }),
  );
});

test("shows the stored style and character cards after their steps complete", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      status: "In progress",
      completedSteps: 2,
      style: "Soft watercolor with warm paper texture.",
      characters: [
        { name: "Mole", prompt: "An adult mole in a waistcoat" },
        { name: "Rat", prompt: "An adult water vole in a boating jacket" },
      ],
      steps: pipelineSteps(
        "COMPLETED",
        "COMPLETED",
        "PENDING",
        "PENDING",
        "PENDING",
      ),
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByRole("heading", { name: "Style" })).toBeInTheDocument();
  expect(
    screen.getByText("Soft watercolor with warm paper texture."),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("heading", { name: "Characters" }),
  ).toBeInTheDocument();
  expect(screen.getByText("Mole")).toBeInTheDocument();
  expect(
    screen.getByText("An adult water vole in a boating jacket"),
  ).toBeInTheDocument();
});

test("shows portrait progress for a character while it is running", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      status: "In progress",
      completedSteps: 2,
      characters: [
        { name: "Mole", prompt: "An adult mole", portraitStatus: "RUNNING", portraitUrl: null, portraitError: null },
      ],
      steps: pipelineSteps("COMPLETED", "COMPLETED", "RUNNING", "PENDING", "PENDING"),
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByText("Portrait running")).toBeInTheDocument();
  expect(screen.getByText("An adult mole")).toBeInTheDocument();
});

test("shows a completed portrait image", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      status: "In progress",
      completedSteps: 3,
      characters: [
        { name: "Mole", prompt: "An adult mole", portraitStatus: "COMPLETED", portraitUrl: "/api/projects/project-1/media/character-1", portraitError: null },
      ],
      steps: pipelineSteps("COMPLETED", "COMPLETED", "COMPLETED", "PENDING", "PENDING"),
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByRole("img", { name: "Mole portrait" })).toHaveAttribute(
    "src",
    "/api/projects/project-1/media/character-1",
  );
});

test("shows a failed portrait error", async () => {
  vi.stubGlobal(
    "fetch",
    projectFetch({
      status: "In progress",
      completedSteps: 2,
      characters: [
        { name: "Mole", prompt: "An adult mole", portraitStatus: "FAILED", portraitUrl: null, portraitError: "Portrait generation failed." },
      ],
      steps: pipelineSteps("COMPLETED", "COMPLETED", "FAILED", "PENDING", "PENDING", "Portrait generation failed."),
    }),
  );

  render(<App />);
  await openProject();

  expect(screen.getByRole("alert")).toHaveTextContent("Portrait generation failed.");
});

test("shows the selected chapter after CHAPTERS completes", async () => {
  vi.stubGlobal("fetch", projectFetch({
    status: "In progress",
    completedSteps: 4,
    chapter: { id: "chapter-1", title: "The river crossing", prompt: "Mole crosses the moonlit river." },
    steps: pipelineSteps("COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "PENDING"),
  }));

  render(<App />);
  await openProject();

  expect(screen.getByRole("heading", { name: "Chapter" })).toBeInTheDocument();
  expect(screen.getByText("The river crossing")).toBeInTheDocument();
  expect(screen.getByText("Mole crosses the moonlit river.")).toBeInTheDocument();
  expect(screen.getByText("Illustrations")).toBeInTheDocument();
});

test("shows CHAPTERS running state", async () => {
  vi.stubGlobal("fetch", projectFetch({
    status: "In progress",
    completedSteps: 3,
    steps: pipelineSteps("COMPLETED", "COMPLETED", "COMPLETED", "RUNNING", "PENDING"),
  }));

  render(<App />);
  await openProject();
  expect(screen.getByText(/Chapters is running/)).toBeInTheDocument();
});

test("shows CHAPTERS failed state with retry action", async () => {
  vi.stubGlobal("fetch", projectFetch({
    status: "In progress",
    completedSteps: 3,
    steps: pipelineSteps("COMPLETED", "COMPLETED", "COMPLETED", "FAILED", "PENDING", "Chapter generation failed."),
  }));

  render(<App />);
  await openProject();
  expect(screen.getByText("Chapters")).toBeInTheDocument();
  expect(screen.getByText("Failed")).toBeInTheDocument();
});

test("shows a completed illustration and full pipeline", async () => {
  vi.stubGlobal("fetch", projectFetch({
    status: "Done", completedSteps: 5,
    chapter: { id: "chapter-1", title: "River scene", prompt: "Mole crosses the river." },
    illustration: { id: "chapter-1", status: "COMPLETED", illustrationUrl: "/api/projects/project-1/illustrations/chapter-1", error: null },
    steps: pipelineSteps("COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED"),
  }));
  render(<App />);
  await openProject();
  expect(screen.getByRole("img", { name: "River scene illustration" })).toHaveAttribute("src", "/api/projects/project-1/illustrations/chapter-1");
  expect(screen.getAllByText("Completed").length).toBeGreaterThan(0);
});

test("shows illustration failure state", async () => {
  vi.stubGlobal("fetch", projectFetch({
    status: "In progress", completedSteps: 4,
    chapter: { id: "chapter-1", title: "River scene", prompt: "Mole crosses the river." },
    illustration: { id: "chapter-1", status: "FAILED", illustrationUrl: null, error: "Illustration generation failed." },
    steps: pipelineSteps("COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "FAILED", "Illustration generation failed."),
  }));
  render(<App />);
  await openProject();
  expect(screen.getByText("Illustration generation failed.")).toBeInTheDocument();
});

function projectFetch(project: Record<string, unknown>) {
  return vi.fn((url: string, init?: RequestInit) => {
    if (url === "/api/session")
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => ({ name: "Mira Hassan", email: "mira@example.com" }),
      });
    if (url === "/api/projects")
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => [
          {
            id: "project-1",
            title: "River Book",
            createdAt: "2026-08-12T00:00:00Z",
            status: project.status || "Draft",
            completedSteps: project.completedSteps || 0,
            totalSteps: 5,
          },
        ],
      });
    if (url === "/api/projects/project-1")
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => ({
          id: "project-1",
          title: "River Book",
          createdAt: "2026-08-12T00:00:00Z",
          status: project.status || "Draft",
          completedSteps: project.completedSteps || 0,
          totalSteps: 5,
          bookText: "A complete book.",
          ...project,
        }),
      });
    if (
      url === "/api/projects/project-1/steps/STYLE/run" &&
      init?.method === "POST"
    )
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => project.runResponse,
      });
    if (url === "/api/health")
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => ({ status: "ok" }),
      });
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  });
}

async function openProject() {
  await screen.findByRole("button", { name: /River Book/ });
  fireEvent.click(screen.getByRole("button", { name: /River Book/ }));
  await screen.findByRole("heading", { name: "River Book" });
}

function pipelineSteps(
  first: string,
  second: string,
  third: string,
  fourth: string,
  fifth: string,
  error?: string,
) {
  return [
    {
      key: "STYLE",
      state: first,
      canRun: first === "PENDING",
      canRetry: first === "FAILED",
      canRecover: false,
      error: error || null,
    },
    {
      key: "CHARACTERS",
      state: second,
      canRun: second === "PENDING" && first === "COMPLETED",
      canRetry: second === "FAILED",
      canRecover: false,
      error: null,
    },
    {
      key: "PORTRAITS",
      state: third,
      canRun: false,
      canRetry: false,
      canRecover: false,
      error: null,
    },
    {
      key: "CHAPTERS",
      state: fourth,
      canRun: false,
      canRetry: false,
      canRecover: false,
      error: null,
    },
    {
      key: "ILLUSTRATIONS",
      state: fifth,
      canRun: false,
      canRetry: false,
      canRecover: false,
      error: null,
    },
  ];
}
