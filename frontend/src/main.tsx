import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './context/AuthContext';
import { ThemeProvider } from './context/ThemeContext';
import { LocaleProvider } from './i18n';
import { ToastProvider } from './components/toast/ToastContext';
import { ConfirmProvider } from './components/ConfirmDialog';
import { ErrorBoundary } from './components/ErrorBoundary';
import './styles/global.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <ThemeProvider>
        <LocaleProvider>
          <ToastProvider>
            <ConfirmProvider>
              <BrowserRouter>
                <AuthProvider>
                  <App />
                </AuthProvider>
              </BrowserRouter>
            </ConfirmProvider>
          </ToastProvider>
        </LocaleProvider>
      </ThemeProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
