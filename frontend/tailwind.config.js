export default {
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
  ],
  corePlugins: {
    preflight: false,
  },
  theme: {
    extend: {
      colors: {
        brand: {
          DEFAULT: '#0f5fd1',
          dark:    '#0a3f9c',
          soft:    '#e3efff',
          border:  '#b6d0f5',
        },
        cyan: {
          brand:   '#22c3d9',
          soft:    '#e0f7fb',
        },
        navy: {
          DEFAULT: '#0d1a33',
          hover:   '#1b2a4a',
        },
        neurixSuccess: '#0e9f7c',
        neurixWarning: '#f0a020',
        neurixDanger:  '#e04562',
      },
      backgroundImage: {
        'brand-gradient': 'linear-gradient(135deg, #0a3f9c 0%, #0f5fd1 45%, #22c3d9 100%)',
        'brand-gradient-soft': 'linear-gradient(135deg, #e3efff 0%, #e0f7fb 100%)',
      },
      boxShadow: {
        'super-card': '0 1px 2px rgba(15,23,42,0.05), 0 8px 24px -12px rgba(13,26,51,0.12)',
        'super-glow': '0 0 0 3px rgba(34,195,217,0.22)',
      },
      borderRadius: {
        xl2: '14px',
      },
    },
  },
  plugins: [],
};
