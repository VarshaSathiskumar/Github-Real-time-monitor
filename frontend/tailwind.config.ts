import type { Config } from 'tailwindcss'

export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"IBM Plex Sans"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        display: ['"IBM Plex Sans"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      colors: {
        border: 'hsl(var(--border))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        muted: { DEFAULT: 'hsl(var(--muted))', foreground: 'hsl(var(--muted-foreground))' },
        card: { DEFAULT: 'hsl(var(--card))', foreground: 'hsl(var(--foreground))' },
        popover: { DEFAULT: 'hsl(var(--popover))', foreground: 'hsl(var(--foreground))' },
        primary: { DEFAULT: 'hsl(var(--primary))', foreground: 'hsl(var(--primary-foreground))' },
        pulse: {
          blue: '#2563eb',
          cyan: '#0e7490',
          violet: '#7c3aed',
          green: '#059669',
          amber: '#d97706',
          rose: '#e11d48',
        },
      },
      borderRadius: { xl: '1rem', '2xl': '1.25rem' },
      keyframes: {
        shimmer: { '100%': { transform: 'translateX(100%)' } },
        ping2: { '75%, 100%': { transform: 'scale(2.4)', opacity: '0' } },
      },
      animation: {
        shimmer: 'shimmer 1.6s infinite',
        ping2: 'ping2 1.8s cubic-bezier(0,0,0.2,1) infinite',
      },
    },
  },
  plugins: [],
} satisfies Config
