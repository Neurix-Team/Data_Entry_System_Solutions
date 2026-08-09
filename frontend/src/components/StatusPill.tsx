import type { TicketStatus } from '../api/types';
import { useT } from '../i18n';

interface Props {
  status: TicketStatus;
}

export function StatusPill({ status }: Props) {
  const { t } = useT();
  const cls =
    status === 'IN_PROGRESS' ? 'status-in-progress' :
    status === 'REVIEW' ? 'status-review' :
    'status-completed';
  return <span className={`status-pill ${cls}`}>{t(`status.${status}`)}</span>;
}
