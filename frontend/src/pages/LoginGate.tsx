import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { LoginPage } from './LoginPage';
import { UploadGuidelinesPage } from './UploadGuidelinesPage';

const ACK_KEY = 'dems.uploadGuidelinesAck.v1';

function hasAcknowledged(): boolean {
  try {
    return localStorage.getItem(ACK_KEY) === '1';
  } catch {
    return false;
  }
}

/** Shows the upload guidelines once per browser before an unauthenticated visitor reaches the login form. */
export function LoginGate() {
  const { user, loading } = useAuth();
  const [showGuidelines, setShowGuidelines] = useState(() => !hasAcknowledged());

  if (loading) {
    return (
      <div className="route-loading" role="status" aria-label="Loading">
        <img src="/neurix-mark.png" alt="" width={42} height={42} aria-hidden="true" />
        <span className="spinner dark" aria-hidden="true" />
      </div>
    );
  }

  if (!user && showGuidelines) {
    return (
      <UploadGuidelinesPage
        onContinue={() => {
          try {
            localStorage.setItem(ACK_KEY, '1');
          } catch {
            // Private browsing or storage disabled — the gate just reappears next visit.
          }
          setShowGuidelines(false);
        }}
      />
    );
  }

  return <LoginPage onShowGuidelines={() => setShowGuidelines(true)} />;
}
