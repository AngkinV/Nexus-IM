import defaultTheme from 'tailwindcss/defaultTheme'

/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  corePlugins: {
    // Element Plus ships its own reset; keep Tailwind's preflight off so the
    // two resets do not fight over form controls.
    preflight: false
  },
  theme: {
    extend: {
      colors: {
  "on-tertiary-fixed": "#1d1b19",
  "secondary": "#c6c6cf",
  "on-primary-fixed": "#1a1c1c",
  "surface-container-high": "#2a2a2d",
  "on-secondary-fixed": "#1a1b22",
  "error": "#ffb4ab",
  "secondary-fixed-dim": "#c6c6cf",
  "on-tertiary-fixed-variant": "#494643",
  "on-error": "#690005",
  "on-secondary-fixed-variant": "#45464e",
  "error-container": "#93000a",
  "tertiary-container": "#e7e1dd",
  "surface-container-low": "#1b1b1e",
  "surface-container": "#1f1f22",
  "on-secondary": "#2f3037",
  "surface": "#131316",
  "tertiary-fixed": "#e7e1dd",
  "on-secondary-container": "#b4b4bd",
  "background": "#131316",
  "outline": "#8e9192",
  "surface-bright": "#39393c",
  "on-primary-fixed-variant": "#454747",
  "on-primary-container": "#636565",
  "outline-variant": "#444748",
  "on-surface": "#e4e1e6",
  "surface-dim": "#131316",
  "tertiary": "#ffffff",
  "inverse-primary": "#5d5f5f",
  "on-tertiary": "#32302d",
  "on-error-container": "#ffdad6",
  "secondary-container": "#45464e",
  "surface-container-highest": "#353438",
  "inverse-on-surface": "#303033",
  "on-primary": "#2f3131",
  "on-surface-variant": "#c4c7c8",
  "primary-container": "#e2e2e2",
  "primary": "#ffffff",
  "on-background": "#e4e1e6",
  "tertiary-fixed-dim": "#cbc6c1",
  "primary-fixed": "#e2e2e2",
  "on-tertiary-container": "#676460",
  "inverse-surface": "#e4e1e6",
  "surface-variant": "#353438",
  "surface-container-lowest": "#0e0e11",
  "surface-tint": "#c6c6c7",
  "primary-fixed-dim": "#c6c6c7",
  "secondary-fixed": "#e2e1eb"
},
      borderRadius: {
  "DEFAULT": "0.25rem",
  "lg": "0.5rem",
  "xl": "0.75rem",
  "full": "9999px"
},
      spacing: {
  "space-sm": "0.5rem",
  "margin-desktop": "2rem",
  "space-md": "1rem",
  "gutter": "1rem",
  "space-xl": "2.5rem",
  "space-xs": "0.25rem",
  "gutter-desktop": "1.5rem",
  "margin": "1rem",
  "space-lg": "1.5rem"
},
      fontFamily: {
  "headline-lg-mobile": [
    "Geist Sans"
  ],
  "code": [
    "JetBrains Mono",
    "ui-monospace",
    "monospace"
  ],
  "display-lg-mobile": [
    "Geist Sans"
  ],
  "headline-lg": [
    "Geist Sans"
  ],
  "label-md": [
    "Geist Sans"
  ],
  "body-md": [
    "Geist Sans"
  ],
  "body-lg": [
    "Geist Sans"
  ],
  "display-lg": [
    "Geist Sans"
  ],
  "label-sm": [
    "Geist Sans"
  ],
  "headline-md": [
    "Geist Sans"
  ]
},
      fontSize: {
  "headline-lg-mobile": [
    "1.5rem",
    {
      "lineHeight": "2rem",
      "letterSpacing": "-0.015em",
      "fontWeight": "600"
    }
  ],
  "code": [
    "0.8125rem",
    {
      "lineHeight": "1.375rem",
      "letterSpacing": "0em",
      "fontWeight": "400"
    }
  ],
  "display-lg-mobile": [
    "2.25rem",
    {
      "lineHeight": "2.75rem",
      "letterSpacing": "-0.02em",
      "fontWeight": "600"
    }
  ],
  "headline-lg": [
    "2rem",
    {
      "lineHeight": "2.5rem",
      "letterSpacing": "-0.02em",
      "fontWeight": "600"
    }
  ],
  "label-md": [
    "0.8125rem",
    {
      "lineHeight": "1.125rem",
      "letterSpacing": "0.01em",
      "fontWeight": "500"
    }
  ],
  "body-md": [
    "0.875rem",
    {
      "lineHeight": "1.5rem",
      "letterSpacing": "0em",
      "fontWeight": "400"
    }
  ],
  "body-lg": [
    "1rem",
    {
      "lineHeight": "1.625rem",
      "letterSpacing": "-0.005em",
      "fontWeight": "400"
    }
  ],
  "display-lg": [
    "3rem",
    {
      "lineHeight": "3.5rem",
      "letterSpacing": "-0.03em",
      "fontWeight": "600"
    }
  ],
  "label-sm": [
    "0.75rem",
    {
      "lineHeight": "1rem",
      "letterSpacing": "0.02em",
      "fontWeight": "500"
    }
  ],
  "headline-md": [
    "1.25rem",
    {
      "lineHeight": "1.75rem",
      "letterSpacing": "-0.01em",
      "fontWeight": "500"
    }
  ]
}
    }
  },
  plugins: []
}
