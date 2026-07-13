// TorentChat Web — Chat service (orchestrates crypto + signaling + local data)

import { Identity, Envelope, encrypt, decrypt, hasSession } from './crypto';
import { signaling } from './signaling';

export interface Message {
  id: string;
  conversationId: string;
  senderId: string;
  content: string;
  timestamp: number;
  isOutgoing: boolean;
}

export interface Conversation {
  id: string;
  title: string;
  peerId: string;
  publicKey: string;
  lastPreview?: string;
  lastTs?: number;
}

const STORE_KEY = 'torentchat_data';

interface Store {
  conversations: Conversation[];
  messages: Message[];
}

function loadStore(): Store {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    return raw ? JSON.parse(raw) : { conversations: [], messages: [] };
  } catch {
    return { conversations: [], messages: [] };
  }
}

function saveStore(store: Store) {
  localStorage.setItem(STORE_KEY, JSON.stringify(store));
}

export class ChatService {
  private identity: Identity;
  private store: Store = loadStore();
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private pendingTimer: ReturnType<typeof setInterval> | null = null;
  public onMessage: ((msg: Message) => void) | null = null;

  constructor(identity: Identity) {
    this.identity = identity;
  }

  start() {
    // Poll for pending E2E envelopes every 5 seconds
    this.pendingTimer = setInterval(async () => {
      try {
        const res = await signaling.fetchPending(this.identity.peerId);
        for (const env of res.envelopes) {
          try {
            const envelope: Envelope = JSON.parse(env.envelope);
            // Decrypt — sender's peerId is used as identifier
            // For now, we store the public key in the envelope's senderId
            const content = await this.tryDecrypt(envelope, env.from);
            if (content) {
              const conv = this.getOrCreateConversation(env.from, env.from);
              const msg: Message = {
                id: envelope.messageId,
                conversationId: conv.id,
                senderId: env.from,
                content,
                timestamp: envelope.timestamp,
                isOutgoing: false,
              };
              this.store.messages.push(msg);
              conv.lastPreview = content;
              conv.lastTs = envelope.timestamp;
              saveStore(this.store);
              this.onMessage?.(msg);
            }
          } catch {}
        }
      } catch {}
    }, 5000);

    // Presence heartbeat every 20s
    this.pollTimer = setInterval(async () => {
      try { await signaling.setPresence(this.identity.peerId); } catch {}
    }, 20000);
  }

  private async tryDecrypt(envelope: Envelope, senderId: string): Promise<string | null> {
    try {
      // Find the sender's public key from conversations
      const conv = this.store.conversations.find(c => c.peerId === senderId);
      if (conv) {
        return await decrypt(this.identity, envelope, conv.publicKey);
      }
      return null;
    } catch {
      return null;
    }
  }

  async sendMessage(peerId: string, publicKey: string, content: string): Promise<void> {
    const conv = this.getOrCreateConversation(peerId, publicKey);
    const msg: Message = {
      id: crypto.randomUUID(),
      conversationId: conv.id,
      senderId: this.identity.peerId,
      content,
      timestamp: Date.now(),
      isOutgoing: true,
    };
    this.store.messages.push(msg);
    conv.lastPreview = content;
    conv.lastTs = msg.timestamp;
    saveStore(this.store);

    try {
      const envelope = await encrypt(this.identity, publicKey, content);
      await signaling.storePending(this.identity.peerId, peerId, JSON.stringify(envelope));
    } catch {}
  }

  getOrCreateConversation(peerId: string, publicKey: string): Conversation {
    let conv = this.store.conversations.find(c => c.peerId === peerId);
    if (!conv) {
      const id = `direct-${[this.identity.peerId, peerId].sort().join('-')}`;
      conv = { id, title: peerId, peerId, publicKey };
      this.store.conversations.push(conv);
      saveStore(this.store);
    }
    return conv;
  }

  getConversations(): Conversation[] {
    return this.store.conversations.sort((a, b) => (b.lastTs ?? 0) - (a.lastTs ?? 0));
  }

  getMessages(conversationId: string): Message[] {
    return this.store.messages
      .filter(m => m.conversationId === conversationId)
      .sort((a, b) => a.timestamp - b.timestamp);
  }

  stop() {
    if (this.pollTimer) clearInterval(this.pollTimer);
    if (this.pendingTimer) clearInterval(this.pendingTimer);
  }
}
