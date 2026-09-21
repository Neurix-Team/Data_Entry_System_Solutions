import { Modal } from '../../../components/Modal';
import { useT } from '../../../i18n';

interface Props {
  open: boolean;
  onClose: () => void;
}

/**
 * The list of keys the submit form answers to. A shortcut nobody can discover is a shortcut
 * nobody uses, so "?" and the button beside the send row both open this.
 */
const SHORTCUTS: Array<{ keys: string[]; id: string }> = [
  { keys: ['Enter'], id: 'nextField' },
  { keys: ['Ctrl', 'Enter'], id: 'submit' },
  { keys: ['Alt', 'N'], id: 'addArticle' },
  { keys: ['Ctrl', 'S'], id: 'saveDraft' },
  { keys: ['Ctrl', 'K'], id: 'search' },
  { keys: ['Tab'], id: 'anyField' },
  { keys: ['?'], id: 'help' },
];

export function ShortcutsHelp({ open, onClose }: Props) {
  const { t } = useT();
  return (
    <Modal open={open} title={t('user.submit.shortcuts.title')} onClose={onClose}>
      <p className="small muted">{t('user.submit.shortcuts.hint')}</p>
      <ul className="shortcut-list">
        {SHORTCUTS.map(({ keys, id }) => (
          <li key={id}>
            <span className="shortcut-keys">
              {keys.map((k) => <kbd key={k}>{k}</kbd>)}
            </span>
            <span className="shortcut-what">{t(`user.submit.shortcuts.${id}`)}</span>
          </li>
        ))}
      </ul>
    </Modal>
  );
}
