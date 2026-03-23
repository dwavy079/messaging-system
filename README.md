# Messaging System (Java + C + Web Frontend)

This project is a starter messaging platform with:

- Java backend (`server-java`) for auth, rooms, history, and realtime messaging
- C native module (`native-c`) for encryption/compression logic
- Web frontend (`web-client`) for a modern chat UI

## Quick start

### 1) Backend

```bash
cd server-java
mvn spring-boot:run
```

If you do not have the native C library built yet, the backend uses a safe Java fallback codec.

If Maven/dependency download is blocked, use the no-dependency backend:

```bash
cd server-lite
./run.sh
```

### 2) Frontend

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

### 3) Build native C library (optional first pass)

```bash
cd native-c
make
```

Then set:

```bash
export MESSAGING_NATIVE_LIB=/absolute/path/to/native-c/libmessagecodec.dylib
```

## Notes

- This is an MVP scaffold intended for extension (persistent DB/auth hardening/tests).
- Current storage is in-memory for faster local development.
# messaging-system
# messaging-system
