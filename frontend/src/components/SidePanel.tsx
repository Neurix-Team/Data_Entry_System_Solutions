import { useEffect } from 'react';
import { IconClose } from './Icons';

interface Props {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
}

export function SidePanel({ open, title, onClose, children, footer }: Props) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <>
      <div className="side-panel-backdrop" onClick={onClose} />
      <aside className="side-panel" onClick={(e) => e.stopPropagation()}>
        <div className="side-panel-header">
          <h3>{title}</h3>
          <button className="icon-btn" onClick={onClose}><IconClose size={20} /></button>
        </div>
        <div className="side-panel-body">{children}</div>
        {footer && <div className="side-panel-footer">{footer}</div>}
      </aside>
    </>
  );
}
