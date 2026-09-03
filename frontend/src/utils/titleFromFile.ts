/**
 * Turning an attached file into an entry title.
 *
 * The filename is the primary source — it is what the user named the thing. A PDF's own
 * metadata title is consulted only when the filename is a scanner/camera default
 * ("scan0001.pdf", "IMG_2041.pdf", "document.pdf") and the document carries a real title
 * in its Info dictionary or XMP packet. At most 1 MB of the file is read, never the
 * whole book, and every failure falls back to the filename.
 */

/** Filename → title: extension stripped, separators normalised. */
export function titleFromFilename(name: string): string {
  const withoutExt = name.replace(/\.[^./\\]+$/, '');
  const cleaned = withoutExt.replace(/[_\-.]+/g, ' ').replace(/\s+/g, ' ').trim();
  return cleaned || name;
}

/** Scanner and camera defaults that say nothing about the content. */
const MEANINGLESS = /^(?:scan|scanned|img|image|dsc|dcim|document|doc|file|untitled|new document|pdf|book|photo)?[\s_-]*\d*$/i;

export function isMeaninglessTitle(title: string): boolean {
  const t = title.trim();
  return t.length < 3 || MEANINGLESS.test(t);
}

const HEAD_BYTES = 512 * 1024;
const TAIL_BYTES = 512 * 1024;

export async function extractTitleFromFile(file: File): Promise<string> {
  const fromName = titleFromFilename(file.name);
  const isPdf = /\.pdf$/i.test(file.name) || file.type === 'application/pdf';
  if (!isPdf || !isMeaninglessTitle(fromName)) return fromName;
  try {
    const meta = await readPdfMetadataTitle(file);
    if (meta && !isMeaninglessTitle(meta)) return meta.slice(0, 200);
  } catch {
    // Unreadable slice or an odd encoding — the filename is still a fine title.
  }
  return fromName;
}

/** The Info dictionary usually sits near the end of the file, XMP near the start. */
async function readPdfMetadataTitle(file: File): Promise<string | null> {
  const slices: string[] = [];
  slices.push(latin1(await file.slice(0, Math.min(HEAD_BYTES, file.size)).arrayBuffer()));
  if (file.size > HEAD_BYTES) {
    slices.push(latin1(await file.slice(Math.max(0, file.size - TAIL_BYTES)).arrayBuffer()));
  }
  for (const text of slices) {
    const title = titleFromInfoDict(text) ?? titleFromXmp(text);
    if (title) return title;
  }
  return null;
}

function latin1(buf: ArrayBuffer): string {
  return new TextDecoder('latin1').decode(buf);
}

function titleFromInfoDict(text: string): string | null {
  // /Title (literal) — parentheses may be escaped, bytes may be \ddd octal escapes.
  const literal = /\/Title\s*\(((?:\\.|[^\\)])*)\)/.exec(text);
  if (literal) {
    const decoded = decodePdfLiteral(literal[1]).trim();
    if (decoded) return decoded;
  }
  // /Title <hex> — typically UTF-16BE with a byte-order mark.
  const hex = /\/Title\s*<([0-9A-Fa-f\s]+)>/.exec(text);
  if (hex) {
    const decoded = decodePdfHex(hex[1]).trim();
    if (decoded) return decoded;
  }
  return null;
}

function decodePdfLiteral(s: string): string {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch !== '\\') {
      out.push(s.charCodeAt(i) & 0xff);
      continue;
    }
    const next = s[++i];
    if (next === undefined) break;
    if (/[0-7]/.test(next)) {
      let oct = next;
      while (oct.length < 3 && /[0-7]/.test(s[i + 1] ?? '')) oct += s[++i];
      out.push(parseInt(oct, 8) & 0xff);
    } else if (next === 'n') {
      out.push(10);
    } else if (next === 'r') {
      out.push(13);
    } else if (next === 't') {
      out.push(9);
    } else {
      out.push(next.charCodeAt(0) & 0xff);
    }
  }
  return bytesToText(Uint8Array.from(out));
}

function decodePdfHex(h: string): string {
  const clean = h.replace(/\s+/g, '');
  const bytes = new Uint8Array(Math.floor(clean.length / 2));
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return bytesToText(bytes);
}

/** PDF text strings are UTF-16BE with a BOM, UTF-8 with a BOM, or PDFDocEncoding (≈ latin1). */
function bytesToText(bytes: Uint8Array): string {
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    return new TextDecoder('utf-16be').decode(bytes.subarray(2));
  }
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return new TextDecoder('utf-8').decode(bytes.subarray(3));
  }
  return new TextDecoder('latin1').decode(bytes);
}

function titleFromXmp(text: string): string | null {
  const m = /<dc:title>[\s\S]*?<rdf:li[^>]*>([\s\S]*?)<\/rdf:li>/.exec(text);
  if (!m) return null;
  const raw = m[1]
    .replace(/<[^>]+>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'");
  // XMP is UTF-8 but the slice was decoded as latin1 — re-decode so Arabic titles survive.
  const fixed = utf8FromLatin1(raw).trim();
  return fixed || null;
}

function utf8FromLatin1(s: string): string {
  try {
    const bytes = Uint8Array.from(s, (ch) => ch.charCodeAt(0) & 0xff);
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return s;
  }
}
