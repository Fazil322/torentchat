// TorentChat Android — Rust native via JNI
// All logic (crypto, signaling, chat) is in Rust — no JVM business logic.

use jni::objects::{JClass, JString};
use jni::JNIEnv;
use jni::sys::{jstring, jboolean};
use std::sync::OnceLock;
use tokio::runtime::Runtime;
use torentchat_core::chat::Chat;
use torentchat_core::identity;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static CHAT: OnceLock<std::sync::Arc<Chat>> = OnceLock::new();

fn rt() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().expect("Failed to create runtime"))
}

fn chat() -> &'static std::sync::Arc<Chat> {
    CHAT.get_or_init(|| {
        let id = identity::load_identity().unwrap_or_else(|| identity::create_identity().expect("Failed to create identity"));
        let chat = std::sync::Arc::new(Chat::new(id));
        let chat2 = chat.clone();
        rt().spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                let _ = chat2.set_presence().await;
                let _ = chat2.drain().await;
            }
        });
        chat
    })
}

fn jstring_from(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(jstr) => jstr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getPeerId(mut env: JNIEnv, _cls: JClass) -> jstring {
    jstring_from(&mut env, chat().identity.peer_id.clone())
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getPublicKey(mut env: JNIEnv, _cls: JClass) -> jstring {
    jstring_from(&mut env, chat().identity.public_key_b64.clone())
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_sendMessage(
    mut env: JNIEnv, _cls: JClass, peer_id: JString, pub_key: JString, content: JString,
) -> jboolean {
    let peer = env.get_string(&peer_id).map(|s| s.to_string()).unwrap_or_default();
    let key = env.get_string(&pub_key).map(|s| s.to_string()).unwrap_or_default();
    let msg = env.get_string(&content).map(|s| s.to_string()).unwrap_or_default();
    if peer.is_empty() || key.is_empty() || msg.is_empty() { return 0; }
    let chat = chat().clone();
    rt().spawn(async move { let _ = chat.send(&peer, &key, &msg).await; });
    1
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_connect(
    mut env: JNIEnv, _cls: JClass, peer_id: JString, pub_key: JString,
) {
    let peer = env.get_string(&peer_id).map(|s| s.to_string()).unwrap_or_default();
    let key = env.get_string(&pub_key).map(|s| s.to_string()).unwrap_or_default();
    if !peer.is_empty() && !key.is_empty() {
        let chat = chat().clone();
        rt().spawn(async move { chat.connect_async(&peer, &key).await; });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_pollMessages(mut env: JNIEnv, _cls: JClass) -> jstring {
    let chat = chat().clone();
    let msgs = rt().block_on(async { chat.drain().await.unwrap_or_default() });
    let json = serde_json::to_string(&msgs).unwrap_or_else(|_| "[]".to_string());
    jstring_from(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getConversations(mut env: JNIEnv, _cls: JClass) -> jstring {
    let convs = chat().store.blocking_read().conversations.clone();
    let json = serde_json::to_string(&convs).unwrap_or_else(|_| "[]".to_string());
    jstring_from(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getMessages(
    mut env: JNIEnv, _cls: JClass, peer_id: JString,
) -> jstring {
    let peer = env.get_string(&peer_id).map(|s| s.to_string()).unwrap_or_default();
    let cid = torentchat_core::data::conv_id(&chat().identity.peer_id, &peer);
    let msgs: Vec<_> = chat().store.blocking_read().messages.iter()
        .filter(|m| m.cid == cid).cloned().collect();
    let json = serde_json::to_string(&msgs).unwrap_or_else(|_| "[]".to_string());
    jstring_from(&mut env, json)
}
