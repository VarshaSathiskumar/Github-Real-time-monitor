import type { Config } from 'tailwindcss'

export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        display: ['"Space Grotesk"', 'Inter', 'ui-sans-serif', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
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
          blue: '#4f8cff',
          cyan: '#22d3ee',
          violet: '#8b5cf6',
          green: '#34d399',
          amber: '#fbbf24',
          rose: '#fb7185',
        },
      },
      borderRadius: { xl: '1rem', '2xl': '1.25rem' },
      keyframes: {
        shimmer: { '100%': { transform: 'translateX(100%)' } },
        ping2: { '75%, 100%': { transform: 'scale(2.4)', opacity: '0' } },
        drift: {
          '0%, 100%': { transform: 'translate3d(0,0,0) scale(1)' },
          '50%': { transform: 'translate3d(2%, -3%, 0) scale(1.08)' },
        },
      },
      animation: {
        shimmer: 'shimmer 1.6s infinite',
        ping2: 'ping2 1.8s cubic-bezier(0,0,0.2,1) infinite',
        drift: 'drift 22s ease-in-out infinite',
      },
    },
  },
  plugins: [],
} satisfies Config
