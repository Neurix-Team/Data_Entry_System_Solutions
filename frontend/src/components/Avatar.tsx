interface AvatarProps {
  name?: string | null;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  hue?: number;
  /** If set, an <img> is rendered; the initials show through only if the image errors out. */
  src?: string | null;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function hashHue(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) | 0;
  return (Math.abs(h) % 6) + 1;
}

export function Avatar({ name, size = 'md', hue, src }: AvatarProps) {
  const label = name?.trim() || '?';
  const h = hue ?? hashHue(label);
  return (
    <span
      className={`avatar avatar-${size}${src ? ' avatar-has-image' : ''}`}
      data-hue={h}
      title={label}
      aria-label={label}
    >
      {src ? (
        // The image sits on top of the colored plate; if it fails to load we hide it and
        // the initials underneath become visible again.
        <img
          className="avatar-img"
          src={src}
          alt=""
          aria-hidden="true"
          onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
        />
      ) : null}
      <span className="avatar-initials">{initials(label)}</span>
    </span>
  );
}
