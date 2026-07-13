import './globals.css';
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'TorentChat — Encrypted P2P Chat',
  description: 'Pesan Anda. Terenkripsi. Tanpa server pusat.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="id">
      <body>{children}</body>
    </html>
  );
}
