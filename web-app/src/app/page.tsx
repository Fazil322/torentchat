'use client';

import { useState, useEffect, useRef, useCallback } from 'react';
import { Identity } from '@/lib/crypto';
import { loadIdentity, generateAndStoreIdentity, updateDisplayName } from '@/lib/identity';
import { ChatService, Conversation, Message } from '@/lib/chat';

type Screen = 'onboarding' | 'conversations' | 'chat' | 'scan' | 'profile';

export default function Home() {
  const [screen, setScreen] = useState<Screen>('onboarding');
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [generating, setGenerating] = useState(false);
  const [chatService, setChatService] = useState<ChatService | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [activeConv, setActiveConv] = useState<Conversation | null>(null);
  const [draft, setDraft] = useState('');
  const [manualPeerId, setManualPeerId] = useState('');
  const [editName, setEditName] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Load identity on mount
  useEffect(() => {
    const id = loadIdentity();
    if (id) {
      setIdentity(id);
      setScreen('conversations');
      const svc = new ChatService(id);
      svc.onMessage = (msg) => {
        if (activeConv && msg.conversationId === activeConv.id) {
          setMessages(prev => [...prev, msg]);
        }
        setConversations(svc.getConversations());
      };
      svc.start();
      setChatService(svc);
      setConversations(svc.getConversations());
    }
    return () => { chatService?.stop(); };
  }, []);

  const handleGenerate = async () => {
    setGenerating(true);
    try {
      const id = await generateAndStoreIdentity();
      setIdentity(id);
      const svc = new ChatService(id);
      svc.start();
      setChatService(svc);
      setScreen('conversations');
    } catch (e) {
      alert('Gagal membuat identitas');
    }
    setGenerating(false);
  };

  const openConversation = (conv: Conversation) => {
    setActiveConv(conv);
    setMessages(chatService?.getMessages(conv.id) ?? []);
    setScreen('chat');
  };

  const handleSend = async () => {
    if (!draft.trim() || !activeConv || !chatService) return;
    const content = draft;
    setDraft('');
    // Optimistic: show immediately
    const optimisticMsg: Message = {
      id: crypto.randomUUID(),
      conversationId: activeConv.id,
      senderId: identity!.peerId,
      content,
      timestamp: Date.now(),
      isOutgoing: true,
    };
    setMessages(prev => [...prev, optimisticMsg]);
    await chatService.sendMessage(activeConv.peerId, activeConv.publicKey, content);
    setConversations(chatService.getConversations());
  };

  const handleConnect = () => {
    if (!manualPeerId.trim() || !chatService) return;
    // For manual connection, we use peerId as temporary public key
    // (in production, this would fetch the peer's pre-key bundle)
    const conv = chatService.getOrCreateConversation(manualPeerId, manualPeerId);
    setConversations(chatService.getConversations());
    setManualPeerId('');
    setScreen('conversations');
  };

  // Auto-scroll messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // ─── ONBOARDING ─────────────────────────────────────────────────
  if (screen === 'onboarding') {
    return (
      <div className="min-h-screen flex items-center justify-center p-6">
        <div className="w-full max-w-md text-center">
          <div className="w-24 h-24 mx-auto mb-4 rounded-2xl bg-surface2 flex items-center justify-center text-5xl">🔐</div>
          <h1 className="text-3xl font-bold mb-2">TorentChat</h1>
          <p className="text-gray-400 mb-8">Pesan Anda. Terenkripsi. Tanpa server pusat.</p>
          <div className="bg-surface2 rounded-xl p-4 mb-8 text-sm text-gray-400">
            🔑 Identitas Anda dibuat secara acak.<br />Tidak ada email atau nomor telepon yang dikumpulkan.
          </div>
          <button
            onClick={handleGenerate}
            disabled={generating}
            className="w-full h-12 rounded-xl bg-primary text-black font-bold hover:opacity-90 disabled:opacity-50"
          >
            {generating ? '⏳ Membuat...' : 'Buat Identitas'}
          </button>
        </div>
      </div>
    );
  }

  // ─── CONVERSATIONS ──────────────────────────────────────────────
  if (screen === 'conversations') {
    return (
      <div className="min-h-screen max-w-2xl mx-auto">
        <header className="flex items-center justify-between p-4 border-b border-surface2">
          <h1 className="text-xl font-bold">TorentChat</h1>
          <div className="flex gap-2">
            <button onClick={() => setScreen('scan')} className="p-2 hover:bg-surface2 rounded-lg text-lg">📷</button>
            <button onClick={() => { setEditName(identity?.displayName ?? ''); setScreen('profile'); }} className="p-2 hover:bg-surface2 rounded-lg text-lg">👤</button>
          </div>
        </header>
        <div className="p-4">
          {conversations.length === 0 ? (
            <div className="text-center text-gray-500 mt-20">
              <div className="text-6xl mb-4">💬</div>
              <p>Belum ada percakapan.<br />Masukkan ID teman untuk mulai chat.</p>
              <button onClick={() => setScreen('scan')} className="mt-4 px-4 py-2 bg-primary text-black rounded-lg font-bold">Hubungkan Teman</button>
            </div>
          ) : (
            <div className="space-y-1">
              {conversations.map(conv => (
                <button
                  key={conv.id}
                  onClick={() => openConversation(conv)}
                  className="w-full flex items-center gap-3 p-3 hover:bg-surface rounded-xl text-left"
                >
                  <div className="w-12 h-12 rounded-full bg-surface2 flex items-center justify-center font-bold text-lg">
                    {conv.title[0]?.toUpperCase()}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-semibold truncate">{conv.title}</div>
                    <div className="text-sm text-gray-500 truncate">{conv.lastPreview || '(no messages)'}</div>
                  </div>
                  {conv.lastTs && <div className="text-xs text-gray-600">{new Date(conv.lastTs).toLocaleTimeString('id', { hour: '2-digit', minute: '2-digit' })}</div>}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }

  // ─── CHAT ───────────────────────────────────────────────────────
  if (screen === 'chat' && activeConv) {
    return (
      <div className="min-h-screen max-w-2xl mx-auto flex flex-col">
        <header className="flex items-center gap-3 p-4 border-b border-surface2">
          <button onClick={() => setScreen('conversations')} className="text-xl">←</button>
          <div className="flex-1 font-semibold">{activeConv.title}</div>
          <div className="text-sm text-primary">🔐 E2E</div>
        </header>
        <div className="flex-1 overflow-y-auto p-4 space-y-3">
          <div className="text-center text-xs text-gray-600 py-2">🔐 Pesan terenkripsi end-to-end</div>
          {messages.map(msg => (
            <div key={msg.id} className={`flex ${msg.isOutgoing ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-[75%] rounded-2xl px-4 py-2 ${msg.isOutgoing ? 'bg-primary text-black' : 'bg-surface2'}`}>
                <div>{msg.content}</div>
                <div className={`text-xs mt-1 ${msg.isOutgoing ? 'text-black/50' : 'text-gray-500'}`}>
                  {new Date(msg.timestamp).toLocaleTimeString('id', { hour: '2-digit', minute: '2-digit' })}
                </div>
              </div>
            </div>
          ))}
          <div ref={messagesEndRef} />
        </div>
        <div className="p-4 border-t border-surface2 flex gap-2">
          <input
            value={draft}
            onChange={e => setDraft(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleSend()}
            placeholder="Pesan..."
            className="flex-1 bg-surface2 rounded-full px-4 py-2 outline-none focus:ring-1 focus:ring-primary"
          />
          <button onClick={handleSend} disabled={!draft.trim()} className="w-10 h-10 rounded-full bg-primary text-black font-bold disabled:opacity-30">→</button>
        </div>
      </div>
    );
  }

  // ─── SCAN / CONNECT ─────────────────────────────────────────────
  if (screen === 'scan') {
    return (
      <div className="min-h-screen max-w-2xl mx-auto">
        <header className="flex items-center gap-3 p-4 border-b border-surface2">
          <button onClick={() => setScreen('conversations')} className="text-xl">←</button>
          <div className="flex-1 font-semibold">Hubungkan Teman</div>
        </header>
        <div className="p-8 text-center">
          <div className="w-48 h-48 mx-auto mb-6 bg-surface2 rounded-2xl flex items-center justify-center text-6xl">📷</div>
          <p className="text-gray-400 mb-6">Masukkan ID teman secara manual:</p>
          <input
            value={manualPeerId}
            onChange={e => setManualPeerId(e.target.value)}
            placeholder="Contoh: K7M3-PQ9X"
            className="w-full bg-surface2 rounded-xl px-4 py-3 text-center outline-none focus:ring-1 focus:ring-primary mb-4"
          />
          <button
            onClick={handleConnect}
            disabled={!manualPeerId.trim()}
            className="w-full h-12 rounded-xl bg-primary text-black font-bold disabled:opacity-30"
          >Hubungkan</button>
        </div>
      </div>
    );
  }

  // ─── PROFILE ────────────────────────────────────────────────────
  if (screen === 'profile') {
    return (
      <div className="min-h-screen max-w-2xl mx-auto">
        <header className="flex items-center gap-3 p-4 border-b border-surface2">
          <button onClick={() => setScreen('conversations')} className="text-xl">←</button>
          <div className="flex-1 font-semibold">Profil</div>
        </header>
        <div className="p-8 text-center space-y-4">
          <div className="w-40 h-40 mx-auto bg-surface2 rounded-2xl flex items-center justify-center text-6xl">🔐</div>
          <div className="text-sm text-gray-500">Peer ID Anda:</div>
          <div className="text-2xl font-mono font-bold">{identity?.peerId ?? 'XXXX-XXXX'}</div>
          <input
            value={editName}
            onChange={e => setEditName(e.target.value)}
            placeholder="Nama tampilan"
            className="w-full bg-surface2 rounded-xl px-4 py-3 outline-none focus:ring-1 focus:ring-primary"
          />
          <button
            onClick={() => { updateDisplayName(editName || null); setScreen('conversations'); }}
            className="w-full h-12 rounded-xl bg-primary text-black font-bold"
          >Simpan</button>
          <div className="pt-8 text-sm text-gray-600">
            <div>TorentChat Web v0.1.0</div>
            <div>Signal Protocol + Cloudflare Workers</div>
          </div>
        </div>
      </div>
    );
  }

  return null;
}
