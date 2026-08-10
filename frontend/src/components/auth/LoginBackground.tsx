import { useEffect, useRef } from 'react';

/**
 * Particle-network background for the login brand panel.
 *
 * Draws a lattice of drifting cyan nodes; whenever two nodes come within
 * MAX_LINK_DISTANCE of each other, a translucent line is drawn between them —
 * the closer they are, the more solid the connection. The effect reads as
 * "linked data" the same way d3-force graphs or classic particles.js scenes do.
 *
 * All motion honors prefers-reduced-motion; when reduced, the canvas is empty.
 */

type Particle = {
  x: number;
  y: number;
  vx: number;
  vy: number;
  radius: number;
};

const NUM_PARTICLES = 70;
const MAX_LINK_DISTANCE = 140;
const NODE_COLOR = 'rgba(126, 224, 238, 1)';
const NODE_GLOW = 'rgba(78, 214, 230, 0.55)';
const LINK_BASE = 'rgba(34, 195, 217';

export function LoginBackground() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let width = 0;
    let height = 0;
    let dpr = window.devicePixelRatio || 1;
    let particles: Particle[] = [];

    function seedParticles() {
      particles = [];
      for (let i = 0; i < NUM_PARTICLES; i++) {
        particles.push({
          x: Math.random() * width,
          y: Math.random() * height,
          vx: (Math.random() - 0.5) * 0.35,
          vy: (Math.random() - 0.5) * 0.35,
          radius: 1.6 + Math.random() * 1.6,
        });
      }
    }

    function resize() {
      if (!canvas) return;
      const rect = canvas.getBoundingClientRect();
      const changed = width !== rect.width || height !== rect.height;
      width = rect.width;
      height = rect.height;
      dpr = window.devicePixelRatio || 1;
      canvas.width = Math.max(1, Math.floor(width * dpr));
      canvas.height = Math.max(1, Math.floor(height * dpr));
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0);
      if (changed || particles.length === 0) seedParticles();
    }

    let raf = 0;
    let lastTime = performance.now();

    function step(now: number) {
      const dt = Math.min(0.05, (now - lastTime) / 1000);
      lastTime = now;

      // Advance particles + bounce off edges
      for (const p of particles) {
        p.x += p.vx * dt * 60;
        p.y += p.vy * dt * 60;
        if (p.x <= 0) { p.x = 0; p.vx = -p.vx; }
        if (p.x >= width) { p.x = width; p.vx = -p.vx; }
        if (p.y <= 0) { p.y = 0; p.vy = -p.vy; }
        if (p.y >= height) { p.y = height; p.vy = -p.vy; }
      }

      ctx!.clearRect(0, 0, width, height);

      // Draw links (n^2 but n=70 is fine — ~2400 pair checks/frame)
      ctx!.lineWidth = 1;
      for (let i = 0; i < particles.length; i++) {
        const a = particles[i];
        for (let j = i + 1; j < particles.length; j++) {
          const b = particles[j];
          const dx = b.x - a.x;
          const dy = b.y - a.y;
          const distSq = dx * dx + dy * dy;
          if (distSq > MAX_LINK_DISTANCE * MAX_LINK_DISTANCE) continue;
          const dist = Math.sqrt(distSq);
          const strength = 1 - dist / MAX_LINK_DISTANCE;
          // Fade out very short + very long links slightly for a more organic look
          const alpha = strength * 0.55;
          ctx!.strokeStyle = `${LINK_BASE}, ${alpha.toFixed(3)})`;
          ctx!.beginPath();
          ctx!.moveTo(a.x, a.y);
          ctx!.lineTo(b.x, b.y);
          ctx!.stroke();
        }
      }

      // Draw nodes with a soft radial glow so they read as "energised"
      for (const p of particles) {
        const glowR = p.radius * 4;
        const grad = ctx!.createRadialGradient(p.x, p.y, 0, p.x, p.y, glowR);
        grad.addColorStop(0, NODE_GLOW);
        grad.addColorStop(1, 'rgba(78, 214, 230, 0)');
        ctx!.fillStyle = grad;
        ctx!.beginPath();
        ctx!.arc(p.x, p.y, glowR, 0, Math.PI * 2);
        ctx!.fill();

        ctx!.fillStyle = NODE_COLOR;
        ctx!.beginPath();
        ctx!.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx!.fill();
      }

      raf = requestAnimationFrame(step);
    }

    resize();
    raf = requestAnimationFrame(step);

    const ro = new ResizeObserver(resize);
    ro.observe(canvas);

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
  }, []);

  return <canvas ref={canvasRef} className="auth-brand-canvas" aria-hidden="true" />;
}
