interface AvatarProps {
  name?: string | null;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  hue?: number;
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

export function Avatar({ name, size = 'md', hue }: AvatarProps) {
  const label = name?.trim() || '?';
  const h = hue ?? hashHue(label);
  return (
    <span
      className={`avatar avatar-${size}`}
      data-hue={h}
      title={label}
      aria-label={label}
    >
      {initials(label)}
    </span>
  );
}
