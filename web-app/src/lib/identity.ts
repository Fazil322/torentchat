// TorentChat Web — Identity manager (localStorage persistence)

import { Identity, createIdentity } from './crypto';

const STORAGE_KEY = 'torentchat_identity';

export function loadIdentity(): Identity | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export async function generateAndStoreIdentity(displayName?: string): Promise<Identity> {
  const id = await createIdentity(displayName);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(id));
  return id;
}

export function updateDisplayName(name: string | null): void {
  const id = loadIdentity();
  if (id) {
    id.displayName = name;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(id));
  }
}

export function clearIdentity(): void {
  localStorage.removeItem(STORAGE_KEY);
}
