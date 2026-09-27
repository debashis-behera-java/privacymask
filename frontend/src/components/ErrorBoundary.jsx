import { Component } from 'react';

// Catches rendering failures anywhere below it and shows a controlled,
// secret-free fallback instead of a blank page.
export class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
  }

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      return (
        <div className="card error-card" role="alert">
          <h2>Something went wrong</h2>
          <p>The dashboard hit an unexpected rendering problem. Reload the page to continue.</p>
          <button type="button" className="btn btn-secondary" onClick={() => window.location.reload()}>
            Reload dashboard
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
