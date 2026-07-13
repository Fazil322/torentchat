/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: '#0F1413',
        surface: '#151B1A',
        surface2: '#1F2725',
        primary: '#00E5C7',
        accent: '#B0CCC8',
      },
    },
  },
  plugins: [],
};
