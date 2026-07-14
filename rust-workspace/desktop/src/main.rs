use std::sync::Arc;
use torentchat_core::chat::Chat;
use torentchat_core::data::conv_id;
use torentchat_core::identity;

#[derive(PartialEq)]
enum Screen { Onboarding, Conversations, Chat, Scan, Profile }

struct App {
    chat: Arc<Chat>,
    screen: Screen,
    peer_id: String,
    draft: String,
    manual_peer: String,
    manual_key: String,
    selected_peer: String,
    messages: Vec<(String, String, bool)>,
    status_msg: String,
    rt: tokio::runtime::Runtime,
}

impl App {
    fn new() -> Self {
        let rt = tokio::runtime::Runtime::new().unwrap();
        let id = identity::load_identity().unwrap_or_else(|| identity::create_identity().unwrap());
        let chat = Arc::new(Chat::new(id.clone()));

        // Start background poller
        let chat2 = chat.clone();
        rt.spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                let _ = chat2.set_presence(false).await;
                let _ = chat2.drain().await;
            }
        });

        App {
            chat, screen: Screen::Conversations, peer_id: id.peer_id,
            draft: String::new(), manual_peer: String::new(), manual_key: String::new(),
            selected_peer: String::new(), messages: Vec::new(), status_msg: String::new(),
            rt,
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("🔐 TorentChat — P2P Encrypted Chat (Rust)");
            ui.label(format!("Peer ID: {}", self.peer_id));
            ui.separator();

            match self.screen {
                Screen::Onboarding => {
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
                    let convs = self.chat.store.blocking_read().conversations.clone();
                    if convs.is_empty() {
                        ui.label("No conversations. Click Connect.");
                    } else {
                        for c in &convs {
                            let label = format!("{}: {}", c.title, c.last_preview.as_deref().unwrap_or("(no messages)"));
                            if ui.button(&label).clicked() {
                                self.selected_peer = c.peer_id.clone();
                                let cid = conv_id(&self.peer_id, &c.peer_id);
                                let s = self.chat.store.blocking_read();
                                let msgs: Vec<_> = s.messages.iter().filter(|m| m.cid == cid).collect();
                                self.messages = msgs.iter().map(|m| (m.sender.clone(), m.content.clone(), m.out)).collect();
                                self.screen = Screen::Chat;
                            }
                        }
                    }
                }
                Screen::Chat => {
                    ui.horizontal(|ui| {
                        if ui.button("← Back").clicked() { self.screen = Screen::Conversations; }
                        ui.label(format!("Chat: {} 🔒", self.selected_peer));
                    });
                    ui.separator();
                    egui::ScrollArea::vertical().show(ui, |ui| {
                        for (_, content, out) in &self.messages {
                            let prefix = if *out { "→" } else { "←" };
                            ui.label(format!("{} {}", prefix, content));
                        }
                    });
                    ui.separator();
                    ui.horizontal(|ui| {
                        ui.text_edit_singleline(&mut self.draft);
                        if ui.button("Send").clicked() && !self.draft.is_empty() {
                            let s = self.chat.store.blocking_read();
                            let pk = s.conversations.iter()
                                .find(|c| c.peer_id == self.selected_peer)
                                .map(|c| c.public_key.clone());
                            drop(s);
                            if let Some(pk) = pk {
                                let chat = self.chat.clone();
                                let peer = self.selected_peer.clone();
                                let msg = self.draft.clone();
                                self.messages.push((self.peer_id.clone(), msg.clone(), true));
                                self.rt.spawn(async move { let _ = chat.send(&peer, &pk, &msg).await; });
                                self.draft.clear();
                            }
                        }
                    });
                }
                Screen::Scan => {
                    ui.horizontal(|ui| { if ui.button("← Back").clicked() { self.screen = Screen::Conversations; } });
                    ui.label("Connect to peer:");
                    ui.text_edit_singleline(&mut self.manual_peer);
                    ui.label("Public Key:");
                    ui.text_edit_singleline(&mut self.manual_key);
                    if ui.button("Hubungkan").clicked() && !self.manual_peer.is_empty() && !self.manual_key.is_empty() {
                        self.chat.connect(&self.manual_peer, &self.manual_key);
                        self.status_msg = format!("Connected to {}", self.manual_peer);
                        self.screen = Screen::Conversations;
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

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions::default();
    eframe::run_native("TorentChat", options, Box::new(|_cc| Ok(Box::new(App::new()))))
}
