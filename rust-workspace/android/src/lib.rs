// TorentChat Android — Rust native via JNI
// Called from a thin Kotlin/Java shim that loads this .so and calls JNI methods.
// All logic (crypto, signaling, chat) is in Rust — no JVM business logic.

use jni::objects::{JClass, JString, JObject, JValue};
use jni::env::JNIEnv;
use jni::sys::{jstring, jboolean};
use std::sync::Arc;
use tokio::runtime::Runtime;
use torentchat_core::chat::Chat;
use torentchat_core::identity;

static mut RUNTIME: Option<Runtime> = None;
static mut CHAT: Option<Arc<Chat>> = None;

fn rt() -> &'static Runtime {
    unsafe { RUNTIME.get_or_insert_with(|| Runtime::new().unwrap()) }
}

fn chat() -> &'static Arc<Chat> {
    unsafe { CHAT.get_or_insert_with(|| {
        let id = identity::load_identity().unwrap_or_else(|| identity::create_identity().unwrap());
        let chat = Arc::new(Chat::new(id));
        let chat2 = chat.clone();
        rt().spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                let _ = chat2.set_presence().await;
                let _ = chat2.drain().await;
            }
        });
        chat
    })}
}

// ─── JNI Methods (called from Kotlin) ─────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getPeerId(env: JNIEnv, _cls: JClass) -> jstring {
    let pid = chat().identity.peer_id.clone();
    env.new_string(pid).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getPublicKey(env: JNIEnv, _cls: JClass) -> jstring {
    let pk = chat().identity.public_key_b64.clone();
    env.new_string(pk).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_sendMessage(
    env: JNIEnv, _cls: JClass, peer_id: JString, pub_key: JString, content: JString,
) -> jboolean {
    let peer: String = env.get_string(&peer_id).unwrap().into();
    let key: String = env.get_string(&pub_key).unwrap().into();
    let msg: String = env.get_string(&content).unwrap().into();
    let chat = chat().clone();
    rt().spawn(async move {
        let _ = chat.send(&peer, &key, &msg).await;
    });
    1 // true = sent
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_connect(
    env: JNIEnv, _cls: JClass, peer_id: JString, pub_key: JString,
) {
    let peer: String = env.get_string(&peer_id).unwrap().into();
    let key: String = env.get_string(&pub_key).unwrap().into();
    chat().connect(&peer, &key);
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_pollMessages(env: JNIEnv, _cls: JClass) -> jstring {
    let chat = chat().clone();
    let msgs = rt().block_on(async { chat.drain().await.unwrap_or_default() });
    let json = serde_json::to_string(&msgs).unwrap_or_default();
    env.new_string(json).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getConversations(env: JNIEnv, _cls: JClass) -> jstring {
    let convs = chat().store.blocking_read().conversations.clone();
    let json = serde_json::to_string(&convs).unwrap_or_default();
    env.new_string(json).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_torentchat_TorentChatNative_getMessages(
    env: JNIEnv, _cls: JClass, peer_id: JString,
) -> jstring {
    let peer: String = env.get_string(&peer_id).unwrap().into();
    let cid = torentchat_core::data::conv_id(&chat().identity.peer_id, &peer);
    let msgs = chat().store.blocking_read().messages.iter()
        .filter(|m| m.cid == cid)
        .cloned()
        .collect::<Vec<_>>();
    let json = serde_json::to_string(&msgs).unwrap_or_default();
    env.new_string(json).unwrap().into_raw()
}
