# REPLY.ME

> AI-powered smart replies for WhatsApp — directly from your notifications.

REPLY.ME is an Android app that intercepts WhatsApp notifications and generates contextual, personalised quick reply suggestions using Google's Gemini AI. Replies are delivered back to WhatsApp via the notification's pending intent — no manual typing needed.

---

## How It Works

```
WhatsApp message arrives
        ↓
Notification intercepted by NotificationListenerService
        ↓
App searches your imported chat history using embeddings (RAG)
  ├── Match found → sends ±10 surrounding messages + 50 recent messages
  └── No match   → sends last 100 messages as context
        ↓
Prompt sent to Gemini API with context + contact profile
        ↓
AI generates 2 reply suggestions
        ↓
Notification actions appear → tap to send via WhatsApp's pending intent
```

---

## Features

- **Smart Replies from Notification** — 2 AI-generated quick replies appear as notification action buttons
- **RAG-based Context** — embeds your chat history for semantically relevant replies
- **Contact Profiles** — per-contact tone, relation, language, reply length, and custom notes
- **Per-contact Toggle** — enable/disable smart replies for individual contacts
- **Multi-Key API Pool** — add up to 5 Gemini API keys; auto-rotates when one hits rate limits
- **Chat History Viewer** — browse imported messages inside the app
- **Built-in Guide** — tap ⓘ anywhere in the app for step-by-step instructions

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| AI | Google Gemini API (gemini-2.0-flash) |
| Storage | Room (chat history) + SharedPreferences (settings/profiles) |
| Notification | NotificationListenerService + RemoteInput pending intent |
| Networking | OkHttp |

---

## Setup & Installation

### Prerequisites
- Android 8.0+ (API 26+)
- A Google account to generate a free Gemini API key

### Step 1 — Get a Gemini API Key
1. Go to [aistudio.google.com](https://aistudio.google.com)
2. Sign in with your Google account
3. Click **Get API Key → Create API key in new project**
4. Copy the key (starts with `AIza...`)

> The free tier provides 15 requests/min and 1M tokens/day — more than enough for personal use.
> You can add up to **5 keys** from different Google accounts for uninterrupted use.

### Step 2 — Install the APK
1. Download `app-release.apk` (or build from source)
2. If prompted by Play Protect → tap **More details → Install anyway**
3. Open the app → paste your API key → tap **Add**

### Step 3 — Allow Notification Access
> ⚠️ On Android 13+, sideloaded apps need an extra step before notification access can be granted.

1. Go to **Settings → Apps → Reply.Me → ⋮ menu → Allow restricted settings**
2. Then go to **Settings → Notification Access → Reply.Me → Allow**

### Step 4 — Export & Import WhatsApp Chats
1. In WhatsApp → open any chat → **⋮ → More → Export chat → Without Media**
2. Save the `.txt` file to your phone's Downloads folder
3. Open REPLY.ME → tap **Import New Chat(s)** → select the file(s)
4. Wait for indexing to complete (progress bar at the bottom)

---

## How To Use

### Importing a Chat
- Tap **Import New Chat(s)** on the home screen
- You can select multiple `.txt` files at once to import several contacts in one go
- The progress bar shows `Indexing chunk X of Y` — do not close the app while this runs
- Once done, the contact appears in the list

### Configuring a Contact Profile
Each contact row has two controls:

| Control | Function |
|---|---|
| **Toggle (right)** | Enable / disable smart replies for this contact |
| **✏ Edit (pencil icon)** | Open the profile sheet |

In the profile sheet you can set:
- **Tone** — Casual / Formal / Natural / ✏ Custom (describe in your own words)
- **Relation** — Friend / Family / Work / Partner / Acquaintance
- **Language Style** — Standard / Hinglish / Slang OK / Emoji Heavy
- **Reply Length** — Short / Medium / Detailed
- **Context Note** — up to 40 words describing the relationship (e.g. *"close friend, we joke around and sometimes cuss"*)

These settings are injected into the AI prompt in a compact format like:
```
Tone:casual Relation:friend Lang:hinglish Len:short Note:close friend, we joke around
```

### Managing API Keys
- Tap **⚙** (top right) to open the API Keys screen
- Add multiple keys from different Google accounts
- Tap **Set as Active** to manually switch the active key
- Keys marked **Rate Limited** auto-recover after 24 hours
- Keys marked **Invalid** should be deleted and replaced

### Receiving Smart Replies
Once everything is set up:
1. Someone sends you a WhatsApp message
2. The notification appears with **2 action buttons** (your AI-generated replies)
3. Tap one → it gets sent to that person via WhatsApp automatically

---

## Building from Source

```bash
git clone https://github.com/CapUmeshKD69/reply.me
cd reply.me
# Open in Android Studio
# Sync Gradle → Run on device
```

Or build a signed APK:
> **Build → Generate Signed Bundle / APK → APK** → select your keystore → release

---

## Architecture

```
SmartReplyNotificationListener
  └── reads incoming WhatsApp notification
  └── checks ContactProfileRepository (isEnabled?)
  └── calls AIEngine.generateSmartReplies(messages, profileLine)
        └── searches MessageRepository (Room) for relevant context
        └── builds prompt → calls Gemini API via OkHttp
        └── rotates to next key if rate limited (ApiKeyRepository)
  └── posts notification with 2 action buttons (RemoteInput pending intents)

MainActivity
  └── MainAppScreen  — contact list with toggle + edit button
  └── ApiKeysScreen  — key management
  └── ChatHistoryScreen — browse imported messages
  └── ContactProfileBottomSheet — per-contact AI settings
  └── GuideBottomSheet — in-app help
```

---

## Known Limitations

- Only works with WhatsApp (uses WhatsApp's specific notification package and pending intent structure)
- Chat history must be manually exported and re-imported if you want to update it
- Notification replies only work if WhatsApp is running in the background
- Large chat files (100k+ messages) may take several minutes to index on the first import

---

## Proof of Concept

Photos and demo videos: [Google Drive](https://drive.google.com/drive/folders/1YD9qIaI-b9-C68_rjdjeP04rN5lFGQMm?usp=sharing)

---

## Future Work

- Support for other messaging apps (Telegram, Instagram DMs)
- Multiple AI model support (fallback to Claude / GPT if Gemini is down)
- Auto re-import from WhatsApp backup
- Notification reply feedback loop (learn from which suggestion you picked)
- Widget for quick contact toggle on home screen
