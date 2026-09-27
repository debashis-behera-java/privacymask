import { ErrorBoundary } from './components/ErrorBoundary.jsx';
import { PipelineDiagram } from './components/PipelineDiagram.jsx';
import { Playground } from './components/Playground.jsx';
import { StatusCard } from './components/StatusCard.jsx';

export function App() {
  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to playground
      </a>
      <header className="app-header">
        <div>
          <p className="eyebrow">PrivacyMask · PII privacy gateway</p>
          <h1>PrivacyMask Playground</h1>
          <p className="subtitle">
            Demonstrate the full gateway flow: authenticate, rate-limit, detect and mask PII, call an LLM
            provider with safe text only, and re-hydrate the response.
          </p>
        </div>
      </header>
      <main id="main" className="layout">
        <div className="column">
          <StatusCard />
          <PipelineDiagram />
        </div>
        <div className="column">
          <Playground />
        </div>
      </main>
      <footer className="app-footer">
        <p>
          Demo UI for development. The browser is an untrusted client: keys and PII stay in memory only, and all
          detection, encryption, mapping, and provider calls happen in the backend.
        </p>
      </footer>
    </div>
  );
}

export function AppWithBoundary() {
  return (
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  );
}
