use anyhow::Result;
use std::sync::Arc;
use torentchat_core::chat::{Chat, fmt_ts, now_ts};
use torentchat_core::data::conv_id;
use torentchat_core::identity;

#[derive(PartialEq)]
enum Screen { Onboarding, Conversations, Chat, Scan, Profile }

struct App {
    chat: Option<Arc<Chat>>,
    screen: Screen,
    peer_id: String,
    draft: String,
    manual_peer: String,
    manual_key: String,
    selected_peer: String,
    messages: Vec<(String, String, bool)>, // (sender, content, outgoing)
    status_msg: String,
}

impl App {
    fn new() -> Self {
        let id = identity::load_identity().unwrap_or_else(|| identity::create_identity().unwrap());
        let chat = Arc::new(Chat::new(id.clone()));
        let screen = if identity::load_identity().is_some() { Screen::Conversations } else { Screen::Onboarding };
        // Start background poller
        { let chat = chat.clone();
          tokio::spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                let _ = chat.set_presence().await;
                let _ = chat.drain().await;
            }
          });
        }
        App {
            chat: Some(chat), screen, peer_id: id.peer_id.clone(),
            draft: String::new(), manual_peer: String::new(), manual_key: String::new(),
            selected_peer: String::new(), messages: Vec::new(), status_msg: String::new(),
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("🔐 TorentChat — P2P Encrypted Chat");
            ui.label(format!("Peer ID: {}", self.peer_id));
            ui.separator();

            match self.screen {
                Screen::Onboarding => {
                    ui.label("Welcome! Click to create your anonymous identity.");
                    if ui.button("Buat Identitas").clicked() {
                        self.screen = Screen::Conversations;
                    }
                }
                Screen::Conversations => {
                    ui.horizontal(|ui| {
                        if ui.button("🔗 Connect").clicked() { self.screen = Screen::Scan; }
                        if ui.button("👤 Profile").clicked() { self.screen = Screen::Profile; }
                    });
                    ui.separator();
                    let chat = self.chat.as_ref().unwrap();
                    let convs = chat.store.blocking_read().conversations.clone();
                    if convs.is_empty() {
                        ui.label("No conversations. Click Connect to add a peer.");
                    } else {
                        for c in &convs {
                            if ui.button(format!("{}: {}", c.title, c.last_preview.as_deref().unwrap_or("(no messages)"))).clicked() {
                                self.selected_peer = c.peer_id.clone();
                                let cid = conv_id(&self.peer_id, &c.peer_id);
                                let msgs = chat.messages_blocking(&cid);
                                self.messages = msgs.iter().map(|m| (m.sender.clone(), m.content.clone(), m.out)).collect();
                                self.screen = Screen::Chat;
                            }
                        }
                    }
                }
                Screen::Chat => {
                    ui.horizontal(|ui| {
                        if ui.button("← Back").clicked() { self.screen = Screen::Conversations; }
                        ui.label(format!("Chat with: {}", self.selected_peer));
                        ui.label("🔒 E2E");
                    });
                    ui.separator();
                    egui::ScrollArea::vertical().show(ui, |ui| {
                        for (sender, content, out) in &self.messages {
                            let prefix = if *out { "→" } else { "←" };
                            ui.label(format!("{} [{}]: {}", prefix, sender, content));
                        }
                    });
                    ui.separator();
                    ui.horizontal(|ui| {
                        ui.text_edit_singleline(&mut self.draft);
                        if ui.button("Send").clicked() && !self.draft.is_empty() {
                            let chat = self.chat.as_ref().unwrap();
                            let s = chat.store.blocking_read();
                            let pk = s.conversations.iter().find(|c| c.peer_id == self.selected_peer).map(|c| c.public_key.clone());
                            drop(s);
                            if let Some(pk) = pk {
                                let chat = chat.clone();
                                let peer = self.selected_peer.clone();
                                let msg = self.draft.clone();
                                self.messages.push((self.peer_id.clone(), msg.clone(), true));
                                tokio::spawn(async move { let _ = chat.send(&peer, &pk, &msg).await; });
                                self.draft.clear();
                            }
                        }
                    });
                }
                Screen::Scan => {
                    ui.horizontal(|ui| { if ui.button("← Back").clicked() { self.screen = Screen::Conversations; } });
                    ui.label("Connect to a peer:");
                    ui.text_edit_singleline(&mut self.manual_peer);
                    ui.label("Public Key:");
                    ui.text_edit_singleline(&mut self.manual_key);
                    if ui.button("Hubungkan").clicked() && !self.manual_peer.is_empty() && !self.manual_key.is_empty() {
                        if let Some(chat) = &self.chat {
                            chat.connect(&self.manual_peer, &self.manual_key);
                            self.status_msg = format!("Connected to {}", self.manual_peer);
                            self.screen = Screen::Conversations;
                        }
                    }
                    if !self.status_msg.is_empty() { ui.label(&self.status_msg); }
                }
                Screen::Profile => {
                    ui.horizontal(|ui| { if ui.button("← Back").clicked() { self.screen = Screen::Conversations; } });
                    ui.label(format!("Peer ID: {}", self.peer_id));
                    ui.label("TorentChat Desktop v0.1.0 (Rust native)");
                    ui.label("X25519 + AES-256-GCM");
                }
            }
        });
    }
}

// Helper for blocking message fetch
impl Chat {
    fn messages_blocking(&self, cid: &str) -> Vec<torentchat_core::data::Message> {
        let s = self.store.blocking_read();
        s.messages.iter().filter(|m| m.cid == cid).cloned().collect()
    }
}

fn main() -> Result<()> {
    let rt = tokio::runtime::Runtime::new()?;
    let _guard = rt.enter();
    let options = eframe::NativeOptions::default();
    eframe::run_native("TorentChat", options, Box::new(|_cc| Ok(Box::new(App::new()))))?;
    Ok(())
}
