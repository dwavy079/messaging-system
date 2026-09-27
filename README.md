# Pulse Messaging System (Java + C + Web Frontend)

This project is a starter messaging platform with:

- Java backend (`server-java`) for auth, rooms, history, and realtime messaging
- C native module (`native-c`) for encryption/compression logic
- Web frontend (`web-client`) for a modern chat UI

## Quick start

### 1) Generate an encryption key

Messages are compressed with zlib and encrypted with AES-256-GCM before they are stored.
The key comes from an environment variable and is never committed:

```bash
export MESSAGING_AES_KEY=$(openssl rand -base64 32)
```

The server refuses to start if the key is missing or is not 32 bytes.

### 2) Build the native C library (optional)

Needs zlib and OpenSSL headers (macOS: `brew install openssl`; Linux: `sudo apt install libssl-dev zlib1g-dev`).

```bash
cd native-c
make
export MESSAGING_NATIVE_LIB=$(pwd)/libmessagecodec.dylib   # .so on Linux
```

If the library isn't set or can't be loaded (missing, wrong OS, wrong CPU architecture),
the backend uses the pure-Java implementation. Both produce the same format, so either one
can read what the other wrote.

### 3) Backend

```bash
cd server-java
mvn spring-boot:run
```

Run the tests (the native tests run only when `MESSAGING_NATIVE_LIB` is set):

```bash
mvn test
```

If Maven/dependency download is blocked, use the no-dependency backend:

```bash
cd server-lite
./run.sh
```

### 4) Frontend

```bash
cd web-client
npm install
npm run dev
```

Set API URLs in `.env` if needed:

```bash
VITE_API_BASE=http://localhost:8080
VITE_WS_BASE=ws://localhost:8080/ws-chat
```

## Message encryption

Each message goes through: UTF-8 bytes -> zlib compress -> AES-256-GCM encrypt -> Base64.

Stored format: `nonce (12 bytes) || ciphertext || GCM tag (16 bytes)`.

- Compress before encrypting: encrypted data looks random and doesn't compress.
- A fresh random nonce per message: reusing a nonce with the same key breaks GCM.
- The GCM tag detects tampering: a modified message fails to decrypt instead of returning garbage.
- This is server-side encryption of stored messages, not end-to-end encryption.

## Notes

- This is an MVP scaffold intended for extension (persistent DB, authentication).
- Current storage is in-memory for faster local development.
