import { useEffect, useMemo, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client/dist/sockjs";

const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
const WS_BASE = import.meta.env.VITE_WS_BASE || "http://localhost:8080/ws-chat";

const defaultRooms = [
  { id: "general", name: "General" },
  { id: "engineering", name: "Engineering" },
  { id: "design", name: "Design" },
];

const STORAGE_KEY = "pulsechat-local-messages-v1";
const STATUS = {
  SENT: "sent",
  PENDING: "pending",
  FAILED: "failed",
};
const EMOJIS = ["😀", "😂", "😍", "🔥", "🎉", "👍", "🙏", "💡", "✅", "🚀", "💬", "❤️"];

const formatDateLabel = (timestamp) => {
  const date = new Date(timestamp);
  const today = new Date();
  const isToday = date.toDateString() === today.toDateString();
  if (isToday) return "Today";
  return date.toLocaleDateString(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
  });
};

function App() {
  const [rooms, setRooms] = useState(defaultRooms);
  const [activeRoom, setActiveRoom] = useState("general");
  const [showHome, setShowHome] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [sender, setSender] = useState("guest");
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : [];
    } catch {
      return [];
    }
  });
  const [connected, setConnected] = useState(false);
  const [wsSupported, setWsSupported] = useState(true);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState("");
  const [showEmojiTray, setShowEmojiTray] = useState(false);
  const [emojiBurst, setEmojiBurst] = useState(false);

  const roomMessages = useMemo(
    () =>
      messages
        .filter((m) => m.roomId === activeRoom)
        .slice()
        .sort((a, b) => a.timestamp - b.timestamp),
    [messages, activeRoom]
  );

  const mergeRoomMessages = (roomId, incoming) => {
    setMessages((prev) => {
      const existing = prev.filter((m) => m.roomId === roomId);
      const others = prev.filter((m) => m.roomId !== roomId);
      const byId = new Map(existing.map((m) => [m.id, m]));
      incoming.forEach((m) => byId.set(m.id, { ...m, status: STATUS.SENT }));
      return [...others, ...Array.from(byId.values())];
    });
  };

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(messages));
  }, [messages]);

  useEffect(() => {
    fetch(`${API_BASE}/api/messages/${activeRoom}`)
      .then((r) => r.json())
      .then((data) => {
        mergeRoomMessages(activeRoom, data);
        setNotice("");
      })
      .catch(() => {
        setNotice("Server unreachable. You are in local fallback mode.");
      });
  }, [activeRoom]);

  useEffect(() => {
    const poll = setInterval(() => {
      fetch(`${API_BASE}/api/messages/${activeRoom}`)
        .then((r) => r.json())
        .then((data) => {
          mergeRoomMessages(activeRoom, data);
        })
        .catch(() => undefined);
    }, 1500);
    return () => clearInterval(poll);
  }, [activeRoom]);

  const retryPendingForRoom = async (roomId) => {
    const pending = messages.filter(
      (m) => m.roomId === roomId && (m.status === STATUS.PENDING || m.status === STATUS.FAILED)
    );
    if (!pending.length) return;
    setSending(true);
    for (const item of pending) {
      try {
        const response = await fetch(`${API_BASE}/api/messages`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            roomId: item.roomId,
            sender: item.sender,
            content: item.content,
          }),
        });
        if (!response.ok) throw new Error("Failed to sync");
        const saved = await response.json();
        setMessages((prev) =>
          prev.map((m) =>
            m.id === item.id ? { ...saved, status: STATUS.SENT } : m
          )
        );
      } catch {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === item.id ? { ...m, status: STATUS.FAILED } : m
          )
        );
      }
    }
    setSending(false);
  };

  useEffect(() => {
    if (!wsSupported) return;
    let hadConnection = false;
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_BASE),
      reconnectDelay: 4000,
      onConnect: () => {
        hadConnection = true;
        setConnected(true);
        setWsSupported(true);
        setNotice("Connected.");
        rooms.forEach((room) => {
          client.subscribe(`/topic/rooms/${room.id}`, (frame) => {
            const payload = JSON.parse(frame.body);
            setMessages((prev) => {
              if (prev.some((m) => m.id === payload.id)) return prev;
              return [...prev, payload];
            });
          });
        });
      },
      onStompError: () => {
        setConnected(false);
        if (!hadConnection) {
          setWsSupported(false);
          setNotice("");
          return;
        }
        setNotice("Realtime disconnected. Using polling fallback.");
      },
      onWebSocketClose: () => {
        setConnected(false);
        if (!hadConnection) {
          setWsSupported(false);
          setNotice("");
          return;
        }
        setNotice("Realtime disconnected. Using polling fallback.");
      },
    });
    client.activate();
    return () => client.deactivate();
  }, [wsSupported]);

  useEffect(() => {
    if (!connected) return;
    setNotice("Connected. Syncing pending messages...");
    retryPendingForRoom(activeRoom).finally(() => setNotice(""));
  }, [connected, activeRoom]);

  const send = async () => {
    const content = input.trim();
    if (!content) return;
    const user = sender.trim() || "guest";
    const optimisticMessage = {
      id: `local-${Date.now()}-${Math.random().toString(36).slice(2)}`,
      roomId: activeRoom,
      sender: user,
      content,
      timestamp: Date.now(),
      status: STATUS.PENDING,
    };

    setSending(true);
    setMessages((prev) => [...prev, optimisticMessage]);
    setInput("");

    try {
      const response = await fetch(`${API_BASE}/api/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ roomId: activeRoom, sender: user, content }),
      });

      if (!response.ok) throw new Error("Failed to send");
      const saved = await response.json();
      setMessages((prev) =>
        prev.map((m) =>
          m.id === optimisticMessage.id ? { ...saved, status: STATUS.SENT } : m
        )
      );
      setNotice("");
    } catch {
      setMessages((prev) =>
        prev.map((m) =>
          m.id === optimisticMessage.id ? { ...m, status: STATUS.FAILED } : m
        )
      );
      setNotice("Message saved locally only. Start backend to sync live.");
    } finally {
      setSending(false);
    }
  };

  const pendingCount = roomMessages.filter(
    (m) => m.status === STATUS.PENDING || m.status === STATUS.FAILED
  ).length;
  const queuedMessages = roomMessages.filter(
    (m) => m.status === STATUS.PENDING || m.status === STATUS.FAILED
  );

  const retryOne = async (messageId) => {
    const item = messages.find((m) => m.id === messageId);
    if (!item) return;
    setMessages((prev) =>
      prev.map((m) => (m.id === item.id ? { ...m, status: STATUS.PENDING } : m))
    );
    try {
      const response = await fetch(`${API_BASE}/api/messages`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          roomId: item.roomId,
          sender: item.sender,
          content: item.content,
        }),
      });
      if (!response.ok) throw new Error("Retry failed");
      const saved = await response.json();
      setMessages((prev) =>
        prev.map((m) => (m.id === item.id ? { ...saved, status: STATUS.SENT } : m))
      );
      setNotice("");
    } catch {
      setMessages((prev) =>
        prev.map((m) => (m.id === item.id ? { ...m, status: STATUS.FAILED } : m))
      );
      setNotice("Retry failed. Message kept in offline queue.");
    }
  };

  const clearFailed = () => {
    setMessages((prev) =>
      prev.filter((m) => !(m.roomId === activeRoom && m.status === STATUS.FAILED))
    );
  };

  const messageItems = useMemo(() => {
    const user = sender.trim() || "guest";
    const items = [];
    let lastDate = "";
    roomMessages.forEach((m, index) => {
      const currentDate = new Date(m.timestamp).toDateString();
      if (currentDate !== lastDate) {
        items.push({
          type: "date",
          key: `date-${currentDate}-${index}`,
          label: formatDateLabel(m.timestamp),
        });
        lastDate = currentDate;
      }
      const previous = roomMessages[index - 1];
      const grouped =
        previous &&
        previous.sender === m.sender &&
        m.timestamp - previous.timestamp < 3 * 60 * 1000;
      const mine = m.sender === user;
      items.push({
        type: "message",
        key: m.id,
        message: m,
        grouped,
        mine,
      });
    });
    return items;
  }, [roomMessages, sender]);

  const roomSummaries = useMemo(
    () =>
      rooms.map((room) => {
        const msgs = messages
          .filter((m) => m.roomId === room.id)
          .slice()
          .sort((a, b) => b.timestamp - a.timestamp);
        const latest = msgs[0];
        const failed = msgs.filter((m) => m.status === STATUS.FAILED).length;
        return { room, latest, failed };
      }),
    [messages]
  );

  const visibleRoomSummaries = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return roomSummaries;
    return roomSummaries.filter(({ room, latest }) => {
      const haystack = `${room.name} ${latest?.sender ?? ""} ${latest?.content ?? ""}`.toLowerCase();
      return haystack.includes(q);
    });
  }, [roomSummaries, searchQuery]);

  const totalFailed = messages.filter((m) => m.status === STATUS.FAILED).length;
  const totalPending = messages.filter((m) => m.status === STATUS.PENDING).length;

  const createConversation = () => {
    const raw = window.prompt("New conversation name:");
    if (!raw) return;
    const name = raw.trim();
    if (!name) return;
    const roomId = name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "");
    if (!roomId) return;
    const exists = rooms.some((r) => r.id === roomId);
    if (exists) {
      setActiveRoom(roomId);
      setShowHome(false);
      return;
    }
    const next = { id: roomId, name };
    setRooms((prev) => [...prev, next]);
    setActiveRoom(roomId);
    setShowHome(false);
    setSearchQuery("");
  };

  const insertEmoji = (emoji) => {
    setInput((prev) => `${prev}${emoji}`);
    setEmojiBurst(true);
    setTimeout(() => setEmojiBurst(false), 260);
  };

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="sidebarHeader">
          <h1
            className="logo clickable"
            onClick={() => setShowHome(true)}
            title="Go to home"
          >
            pulse
          </h1>
          <button className="iconBtn" onClick={createConversation} title="New conversation">
            ✏️
          </button>
        </div>
        <div className="searchWrap">
          <span className="searchIcon">🔍</span>
          <input
            className="searchInput"
            placeholder="Search conversations..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
        <label className="nameField compactField">
          Display name
          <input value={sender} onChange={(e) => setSender(e.target.value)} />
        </label>
        <div className="status">
          <span className={connected ? "dot online" : "dot offline"} />
          {connected ? "Live" : wsSupported ? "Connecting" : "Polling mode"}
        </div>
        <div className="sectionLabel">Messages</div>
        <nav className="chatList">
          {visibleRoomSummaries.map(({ room, latest, failed }) => (
            <button
              key={room.id}
              className={activeRoom === room.id ? "chatItem active" : "chatItem"}
              onClick={() => {
                setActiveRoom(room.id);
                setShowHome(false);
              }}
            >
              <div className="chatAvatar">{room.name.slice(0, 1)}</div>
              <div className="chatText">
                <div className="chatName">#{room.name}</div>
                <div className="chatPreview">
                  {latest ? `${latest.sender}: ${latest.content}` : "No messages yet"}
                </div>
              </div>
              <div className="chatMeta">
                {latest ? (
                  <span className="chatTime">
                    {new Date(latest.timestamp).toLocaleTimeString([], {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </span>
                ) : null}
                {failed > 0 ? <span className="badge">{failed}</span> : null}
              </div>
            </button>
          ))}
          {!visibleRoomSummaries.length ? (
            <div className="noResults">No conversations found.</div>
          ) : null}
        </nav>
      </aside>
      <main className="chat">
        {showHome ? (
          <section className="homeScreen">
            <h2>Welcome to Pulse</h2>
            <p>Select a room or use a quick action to start chatting.</p>
            <div className="homeActions">
              <button onClick={() => { setActiveRoom("general"); setShowHome(false); }}>
                Open #General
              </button>
              <button onClick={() => { setActiveRoom("engineering"); setShowHome(false); }}>
                Open #Engineering
              </button>
              <button onClick={() => { setActiveRoom("design"); setShowHome(false); }}>
                Open #Design
              </button>
              <button onClick={() => retryPendingForRoom(activeRoom)} disabled={sending || !totalPending}>
                Retry pending ({totalPending})
              </button>
              <button onClick={clearFailed} disabled={!totalFailed}>
                Clear failed ({totalFailed})
              </button>
            </div>
          </section>
        ) : (
          <>
            <header className="chatHeader">
              <div className="chatHeaderAvatar">
                {rooms.find((r) => r.id === activeRoom)?.name.slice(0, 1)}
              </div>
              <div className="chatHeaderInfo">
                <h2>{rooms.find((r) => r.id === activeRoom)?.name}</h2>
                <small>{connected ? "Active now" : wsSupported ? "Connecting..." : "Polling fallback"}</small>
              </div>
              <div className="headerActions">
                {pendingCount > 0 ? (
                  <button
                    className="retryBtn"
                    onClick={() => retryPendingForRoom(activeRoom)}
                    disabled={sending}
                  >
                    Retry {pendingCount}
                  </button>
                ) : null}
                <button className="iconBtn" onClick={() => setShowHome(true)}>⌂</button>
                <button className="iconBtn">⋯</button>
              </div>
            </header>
            <div className="chatBody">
              {notice ? <div className="notice compact">{notice}</div> : null}
              {queuedMessages.length > 0 ? (
                <section className="offlineQueue">
                  <div className="offlineQueueHeader">
                    <strong>Offline queue ({queuedMessages.length})</strong>
                    <button className="clearBtn" onClick={clearFailed}>
                      Clear failed
                    </button>
                  </div>
                  <div className="offlineQueueList">
                    {queuedMessages.map((m) => (
                      <div key={`queue-${m.id}`} className="queueItem">
                        <p>{m.content}</p>
                        <div>
                          <span className={m.status === STATUS.FAILED ? "pill failed" : "pill pending"}>
                            {m.status}
                          </span>
                          <button onClick={() => retryOne(m.id)} disabled={sending}>
                            Retry
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>
              ) : null}
              <section className="messages">
                {!messageItems.length ? (
                  <div className="emptyState">
                    <div className="emptyIcon">💬</div>
                    <h3>No messages yet</h3>
                    <p>Start the conversation in #{rooms.find((r) => r.id === activeRoom)?.name}.</p>
                  </div>
                ) : null}
                {messageItems.map((item) => {
                  if (item.type === "date") {
                    return (
                      <div key={item.key} className="dateSeparator">
                        <span>{item.label}</span>
                      </div>
                    );
                  }
                  const m = item.message;
                  return (
                    <article
                      key={item.key}
                      className={[
                        "bubble",
                        item.mine ? "mine" : "",
                        item.grouped ? "grouped" : "",
                      ]
                        .filter(Boolean)
                        .join(" ")}
                    >
                      {!item.grouped ? <div className="senderName">{m.sender}</div> : null}
                      <p>{m.content}</p>
                      <div className="meta">
                        <time>{new Date(m.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</time>
                        {item.mine ? (
                          <span className="ticks">
                            {m.status === STATUS.SENT ? "✓✓" : m.status === STATUS.FAILED ? "!" : "✓"}
                          </span>
                        ) : null}
                      </div>
                    </article>
                  );
                })}
              </section>
            </div>
            <footer className="composer">
              {showEmojiTray ? (
                <div className="emojiTray">
                  {EMOJIS.map((emoji) => (
                    <button
                      key={emoji}
                      className="emojiChip"
                      onClick={() => insertEmoji(emoji)}
                      title={`Insert ${emoji}`}
                    >
                      {emoji}
                    </button>
                  ))}
                </div>
              ) : null}
              <div className="composerShell">
                <button
                  className={emojiBurst ? "inputBtn emojiPop" : "inputBtn"}
                  onClick={() => setShowEmojiTray((prev) => !prev)}
                  title="Open emoji tray"
                >
                  😊
                </button>
                <input
                  placeholder="Message..."
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && send()}
                />
                <button onClick={send} disabled={sending}>
                  {sending ? "..." : "➤"}
                </button>
              </div>
            </footer>
          </>
        )}
      </main>
      <aside className="rightPanel">
        <div className="sectionLabel panel">Contact Info</div>
        <div className="profileBlock">
          <div className="profileAvatar">{(sender.trim() || "G").slice(0, 1).toUpperCase()}</div>
          <div className="profileName">{sender.trim() || "guest"}</div>
          <div className="profileHandle">@pulsechat</div>
        </div>
        <div className="panelCards">
          <div className="panelCard">
            <strong>Offline Queue</strong>
            <span>{queuedMessages.length} pending in this room</span>
          </div>
          <div className="panelCard">
            <strong>Connection</strong>
            <span>{connected ? "Realtime active" : "Polling fallback"}</span>
          </div>
        </div>
      </aside>
    </div>
  );
}

export default App;
