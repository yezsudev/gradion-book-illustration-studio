import { useEffect, useState } from 'react';
import { getHealth } from './api';
import './App.css';

const steps = ['Style', 'Characters', 'Portraits', 'Chapters', 'Illustrations'];

type HealthState = 'checking' | 'connected' | 'unavailable';

export default function App() {
  const [healthState, setHealthState] = useState<HealthState>('checking');

  useEffect(() => {
    let active = true;
    getHealth()
      .then((result) => {
        if (active && result.status === 'ok') setHealthState('connected');
        else if (active) setHealthState('unavailable');
      })
      .catch(() => active && setHealthState('unavailable'));
    return () => {
      active = false;
    };
  }, []);

  const healthMessage = {
    checking: 'Checking backend…',
    connected: 'Backend connected',
    unavailable: 'Backend unavailable',
  }[healthState];

  return (
    <main className="page-shell">
      <nav className="topbar" aria-label="Main navigation">
        <span className="brand">GRADION</span>
        <span className="nav-label">Book Illustration Studio</span>
      </nav>
      <section className="hero-card" aria-labelledby="page-title">
        <p className="eyebrow">Milestone 1 · local workspace</p>
        <h1 id="page-title">Turn a book into a visual world.</h1>
        <p className="lede">
          The application foundation is ready. The five-step illustration flow will be added in the next milestones.
        </p>
        <div className="health-row">
          <span className={`health-dot ${healthState}`} aria-hidden="true" />
          <span role="status" aria-live="polite">{healthMessage}</span>
        </div>
        <ol className="stepper" aria-label="Illustration pipeline preview">
          {steps.map((step, index) => (
            <li key={step} className={index === 0 ? 'current' : 'pending'}>
              <span className="step-number">{index + 1}</span>
              <span>{step}</span>
            </li>
          ))}
        </ol>
      </section>
    </main>
  );
}
