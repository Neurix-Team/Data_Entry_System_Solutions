interface Props {
  cols: number;
  rows?: number;
}

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
