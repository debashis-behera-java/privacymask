import React from 'react';
import ReactDOM from 'react-dom/client';
import { AppWithBoundary } from './App.jsx';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AppWithBoundary />
  </React.StrictMode>,
);
