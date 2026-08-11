import { useEffect, useRef } from 'react';

/**
 * Fires a short particle burst that reads as celebration. Colored streamers spawn from the
 * origin (center-top of the parent), get an upward + sideways kick, fall under gravity,
 * spin, and fade out. Whole thing wraps up under a second.
 *
 * Placed absolutely inside a positioned parent (see .toast in global.css). The canvas is
 * pointer-events: none so it never blocks anything underneath.
 */

const COLORS = ['#22c3d9', '#0f5fd1', '#4ed6e6', '#7fe6f0', '#f0a020', '#e04562'];
const NUM = 42;
const LIFE_MS = 1200;

type Piece = {
  x: number;
  y: number;
  vx: number;
  vy: number;
  rot: number;
  vrot: number;
  w: number;
  h: number;
  color: string;
};

export function ConfettiBurst() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const rect = canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.floor(rect.width * dpr);
    canvas.height = Math.floor(rect.height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const width = rect.width;
    const height = rect.height;
    // Burst origin — top edge, horizontally centered
    const ox = width / 2;
    const oy = 6;

    const particles: Piece[] = [];
    for (let i = 0; i < NUM; i++) {
      const angle = -Math.PI / 2 + (Math.random() - 0.5) * 1.6; // mostly up, spread ±46°
      const speed = 90 + Math.random() * 150;
      particles.push({
        x: ox,
        y: oy,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        rot: Math.random() * Math.PI * 2,
        vrot: (Math.random() - 0.5) * 8,
        w: 5 + Math.random() * 4,
        h: 8 + Math.random() * 6,
        color: COLORS[Math.floor(Math.random() * COLORS.length)],
      });
    }

    const gravity = 320; // px / s^2
    const drag = 0.995;
    const start = performance.now();
    let raf = 0;
    let last = start;

    function frame(now: number) {
      const dt = Math.min(0.05, (now - last) / 1000);
      last = now;
      const elapsed = now - start;
      const life = Math.min(1, elapsed / LIFE_MS);

      ctx!.clearRect(0, 0, width, height);

      for (const p of particles) {
        p.vx *= drag;
        p.vy = p.vy * drag + gravity * dt;
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.rot += p.vrot * dt;

        // Fade out over the last 30% of life
        const alpha = life > 0.7 ? Math.max(0, 1 - (life - 0.7) / 0.3) : 1;

        ctx!.save();
        ctx!.globalAlpha = alpha;
        ctx!.translate(p.x, p.y);
        ctx!.rotate(p.rot);
        ctx!.fillStyle = p.color;
        ctx!.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
        ctx!.restore();
      }

      if (elapsed < LIFE_MS) {
        raf = requestAnimationFrame(frame);
      } else {
        ctx!.clearRect(0, 0, width, height);
      }
    }

    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, []);

  return <canvas ref={canvasRef} className="toast-confetti" aria-hidden="true" />;
}
