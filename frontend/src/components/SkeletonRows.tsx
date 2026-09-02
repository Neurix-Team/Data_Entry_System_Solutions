interface Props {
  /** Number of columns the table renders — matches the old loading row's colSpan. */
  cols: number;
  rows?: number;
}

/**
 * Table loading skeleton: shimmer bands shaped like the rows that are coming, in
 * place of the old one-line "Loading…" cell. Band widths are a deterministic
 * pseudo-random spread so the block reads as content, not stripes — and stays
 * stable across re-renders (no layout dance while polling).
 *
 * Renders bare `<tr>`s: drop it inside any `table.data` tbody. Shimmer and theme
 * come from `.skel-band` in global.css; the row entrance every `table.data` row
 * gets applies here too, so skeleton and content arrive with the same motion.
 */
export function SkeletonRows({ cols, rows = 6 }: Props) {
  return (
    <>
      {Array.from({ length: rows }, (_, r) => (
        <tr key={r} aria-hidden="true">
          {Array.from({ length: cols }, (_, c) => (
            <td key={c}>
              <span className="skel-band" style={{ width: `${52 + ((r * 7 + c * 13) % 38)}%` }} />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
