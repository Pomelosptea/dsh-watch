#!/usr/bin/env node
/**
 * DSH Watch Bridge
 * ----------------
 * A small LAN/ZeroTier bridge that turns the local DeepSeek Harness web API
 * into a watch-friendly NDJSON endpoint:
 *
 *   POST /api/prompt  {"text":"..."}  ->  accepted / delta / done / error lines
 *   GET  /health
 *   POST /api/cancel
 *   POST /api/session/new
 *
 * It mints the same browser-session cookie that the DSH web UI uses, discovers
 * the local DSH server, creates (or reuses) one agent session, follows its
 * event stream, and forwards the assistant text. No npm dependencies.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(HERE, 'config.json');
const STATE_PATH = path.join(HERE, 'state.json');
const MESSAGES_PATH = path.join(HERE, 'messages.json');

const defaults = {
  bind: '0.0.0.0',
  port: 8787,
  token: 'change-me',
  dshHome: '',
  dshBaseUrl: '',
  workspace: '',
  autoApprove: false,
  maxText: 2000,
  requestTimeoutMs: 10 * 60 * 1000,
};

function readJson(file, fallback) {
  try {
    const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
    return JSON.parse(text);
  } catch {
    return fallback;
  }
}
const config = { ...defaults, ...readJson(CONFIG_PATH, {}) };
const dshHome = config.dshHome || path.join(os.homedir(), '.dsh');
const workspace = config.workspace || path.join(HERE, 'agent');
let state = readJson(STATE_PATH, { sessionId: '', workspaceId: '' });
let cached = { baseUrl: '', cookie: '', at: 0 };
let busy = false;
let activeAbort = null;
let queue = Promise.resolve();

// Durable per-message mailbox. The watch can enqueue while the link is bad,
// then reconnect and replay every update through /api/events.
const messages = new Map();
let messageSeq = 0;
const eventWaiters = new Set();
const activeControllers = new Map();
const loadedMessages = readJson(MESSAGES_PATH, { seq: 0, messages: [] });
messageSeq = Number(loadedMessages.seq) || 0;
for (const message of loadedMessages.messages ?? []) {
  if (message?.id) messages.set(message.id, message);
}

function persistMessages() {
  try {
    const list = [...messages.values()]
      .sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0))
      .slice(-200);
    fs.writeFileSync(MESSAGES_PATH, JSON.stringify({ seq: messageSeq, messages: list }, null, 2));
  } catch (error) {
    log('message store write failed:', error.message);
  }
}

function wakeEventWaiters() {
  for (const waiter of [...eventWaiters]) {
    if (waiter.after < messageSeq) {
      clearTimeout(waiter.timer);
      eventWaiters.delete(waiter);
      waiter.resolve(eventsAfter(waiter.after));
    }
  }
}

function updateMessage(id, patch) {
  const previous = messages.get(id) ?? {
    id,
    text: '',
    status: 'queued',
    reply: '',
    error: null,
    createdAt: Date.now(),
  };
  const next = {
    ...previous,
    ...patch,
    id,
    seq: ++messageSeq,
    updatedAt: Date.now(),
  };
  messages.set(id, next);
  persistMessages();
  wakeEventWaiters();
  return next;
}

function eventsAfter(after) {
  return [...messages.values()]
    .filter((message) => Number(message.seq) > Number(after))
    .sort((a, b) => a.seq - b.seq);
}

function waitForEvents(after, timeoutMs) {
  const immediate = eventsAfter(after);
  if (immediate.length > 0) return Promise.resolve(immediate);
  return new Promise((resolve) => {
    const waiter = {
      after,
      resolve,
      timer: setTimeout(() => {
        eventWaiters.delete(waiter);
        resolve([]);
      }, timeoutMs),
    };
    eventWaiters.add(waiter);
  });
}

const log = (...args) => console.log(new Date().toISOString(), ...args);

function saveState() {
  try {
    fs.mkdirSync(HERE, { recursive: true });
    fs.writeFileSync(STATE_PATH, JSON.stringify(state, null, 2));
  } catch (error) {
    log('state write failed:', error.message);
  }
}

function base64url(value) {
  return Buffer.from(value).toString('base64')
    .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
}

function readBrowserSecret() {
  try {
    const text = fs.readFileSync(path.join(dshHome, '.credentials.yaml'), 'utf8');
    const match = text.match(/client-connection\/browser-session:[\s\S]*?secret:\s*([A-Za-z0-9_-]+)/);
    if (!match) return null;
    const secret = Buffer.from(match[1], 'base64url');
    return secret.length === 32 ? secret : null;
  } catch {
    return null;
  }
}

function mintCookie(baseUrl) {
  const secret = readBrowserSecret();
  if (!secret) throw new Error(`找不到 DSH 会话密钥：${path.join(dshHome, '.credentials.yaml')}。请先启动一次 DSH Web。`);
  const authority = new URL(baseUrl).host;
  const issuedAt = Date.now();
  const expiresAt = issuedAt + 60 * 60 * 1000;
  const body = base64url(JSON.stringify({ version: 1, authority, issuedAt, expiresAt }));
  const signature = base64url(createHmac('sha256', secret).update(body).digest());
  const name = 'dsh-auth-' + base64url(createHash('sha256').update(authority).digest());
  return `${name}=v1.${body}.${signature}`;
}

function endpointOf(method) {
  return method.replaceAll('.', '/');
}

function wrapperOf(method) {
  const endpoint = endpointOf(method);
  if (endpoint === 'session/list') return '_request';
  if (endpoint === 'agentPresets/list') return null;
  return 'request';
}

async function rpc(baseUrl, cookie, method, payload, { timeout = 30_000 } = {}) {
  const endpoint = endpointOf(method);
  const wrapper = wrapperOf(method);
  const rpcId = randomUUID();
  const body = {
    type: 'client-request',
    rpcId,
    method: endpoint,
    payload: { args: wrapper ? { [wrapper]: payload ?? {} } : {} },
  };
  const response = await fetch(baseUrl + '/api/' + endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json', cookie },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeout),
  });
  const text = await response.text();
  let full;
  try { full = JSON.parse(text); } catch {
    throw new Error(`DSH ${method} 返回了非 JSON（HTTP ${response.status}）`);
  }
  if (!full?.result?.ok) {
    const error = full?.result?.error;
    throw new Error(`DSH ${method} 失败：${error?.code ?? 'unknown'} ${error?.message ?? text.slice(0, 160)}`);
  }
  return full.result.value;
}

async function probe(baseUrl) {
  try {
    const cookie = mintCookie(baseUrl);
    const value = await rpc(baseUrl, cookie, 'session/list', {}, { timeout: 900 });
    if (!value || !Array.isArray(value.items)) return null;
    return { baseUrl, cookie };
  } catch {
    return null;
  }
}

function listeningPorts() {
  return new Promise((resolve) => {
    execFile('netstat', ['-ano', '-p', 'tcp'], { windowsHide: true, timeout: 4000 }, (error, stdout) => {
      if (error) return resolve([]);
      const ports = new Set();
      for (const line of stdout.split(/\r?\n/)) {
        const match = line.match(/^\s*TCP\s+(?:127\.0\.0\.1|0\.0\.0\.0|\[::1\]):(\d+)\s+\S+\s+LISTENING\s+\d+/i);
        if (match) {
          const port = Number(match[1]);
          if (port >= 1024 && port !== config.port) ports.add(port);
        }
      }
      resolve([...ports]);
    });
  });
}

async function discoverDsh() {
  if (cached.baseUrl && Date.now() - cached.at < 5 * 60 * 1000) {
    return cached;
  }
  const candidates = [];
  if (config.dshBaseUrl) candidates.push(config.dshBaseUrl.replace(/\/+$/, ''));
  for (const port of [64490, 3080, 3000, 8080]) {
    candidates.push(`http://127.0.0.1:${port}`);
  }
  for (const port of await listeningPorts()) {
    candidates.push(`http://127.0.0.1:${port}`);
  }
  const unique = [...new Set(candidates)];
  for (let i = 0; i < unique.length; i += 12) {
    const batch = unique.slice(i, i + 12);
    const results = await Promise.all(batch.map(probe));
    const hit = results.find(Boolean);
    if (hit) {
      cached = { ...hit, at: Date.now() };
      log('DSH discovered at', hit.baseUrl);
      return cached;
    }
  }
  throw new Error('没有找到正在运行的 DSH Web（本机 64490/3080 等端口均未响应）。请先打开 DSH 桌面端或 dsh web。');
}

async function ensureSession() {
  const dsh = await discoverDsh();
  if (state.sessionId) {
    return { ...dsh, sessionId: state.sessionId };
  }
  fs.mkdirSync(workspace, { recursive: true });
  let workspaceId = state.workspaceId;
  try {
    const created = await rpc(dsh.baseUrl, dsh.cookie, 'workspace.create', { path: workspace });
    workspaceId = created?.workspace?.workspaceId || workspaceId;
    state.workspaceId = workspaceId || '';
    saveState();
  } catch (error) {
    if (!workspaceId) throw new Error(`无法把 DSH 工作区设为 ${workspace}：${error.message}`);
  }
  let value;
  try {
    value = await rpc(dsh.baseUrl, dsh.cookie, 'session/create', {
      workspaceId,
      agentPreset: 'standard',
    });
  } catch {
    value = await rpc(dsh.baseUrl, dsh.cookie, 'session/create', { workspaceId });
  }
  const sessionId = value?.sessionId;
  if (!sessionId) throw new Error('DSH 没有返回新的 sessionId');
  state.sessionId = sessionId;
  saveState();
  log('new DSH session', sessionId);
  return { ...dsh, sessionId };
}

function textFromMessage(message) {
  let text = '';
  for (const block of message?.content ?? []) {
    if (block?.type === 'text' && typeof block.text === 'string') text += block.text;
  }
  return text;
}

async function streamPrompt(text, res, abortSignal) {
  const dsh = await ensureSession();
  const wsUrl = dsh.baseUrl.replace(/^http/, 'ws') + '/api/remote.mux';
  const socket = new WebSocket(wsUrl, { headers: { cookie: dsh.cookie } });
  const streamId = randomUUID();
  let finished = false;
  let finalText = '';
  let deltaText = '';
  let reason = 'completed';
  let timer;
  let markSettled;
  const settled = new Promise((resolve) => { markSettled = resolve; });

  const write = (event) => {
    if (finished) return;
    try {
      res.write(JSON.stringify(event) + '\n');
    } catch {
      // The watch disconnected; the close handler will cancel the turn.
    }
  };

  const cleanup = () => {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    try { socket.close(); } catch {}
    activeAbort = null;
    busy = false;
    markSettled?.();
  };

  const fail = (message) => {
    if (finished) return;
    write({ type: 'error', message });
    cleanup();
  };

  const finish = () => {
    if (finished) return;
    const output = finalText || deltaText;
    write({ type: 'done', text: output, reason });
    cleanup();
  };

  const cancelTurn = async () => {
    try {
      await rpc(dsh.baseUrl, dsh.cookie, 'session/cancel', { sessionId: dsh.sessionId }, { timeout: 5000 });
    } catch {}
  };

  const onAbort = () => {
    if (finished) return;
    write({ type: 'error', message: '已取消' });
    cleanup();
    void cancelTurn();
  };

  abortSignal.addEventListener('abort', onAbort, { once: true });

  try {
    await new Promise((resolve, reject) => {
      const openTimer = setTimeout(() => reject(new Error('连接 DSH 事件流超时')), 5000);
      socket.addEventListener('open', () => { clearTimeout(openTimer); resolve(); }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(openTimer); reject(new Error('连接 DSH 事件流失败')); }, { once: true });
    });

    socket.send(JSON.stringify({
      type: 'open',
      streamId,
      endpoint: 'session/follow',
      payload: { args: { request: { address: { kind: 'session', sessionId: dsh.sessionId } } } },
    }));

    // Give the follow stream a beat to attach before submitting the prompt.
    await new Promise((resolve) => setTimeout(resolve, 250));

    const accepted = await rpc(dsh.baseUrl, dsh.cookie, 'session/prompt', {
      sessionId: dsh.sessionId,
      mode: 'queue',
      content: [{ type: 'text', text }],
      requestId: randomUUID(),
    }, { timeout: 15_000 });
    if (!accepted?.accepted) throw new Error('DSH 没有接受这条指令');
    write({ type: 'accepted', sessionId: dsh.sessionId });
    write({ type: 'status', text: 'DSH 已接收，正在执行…' });

    timer = setTimeout(() => {
      write({ type: 'error', message: '指令执行超时（10 分钟）' });
      cleanup();
      void cancelTurn();
    }, config.requestTimeoutMs);

    socket.addEventListener('message', (event) => {
      if (finished) return;
      let frame;
      try { frame = JSON.parse(event.data); } catch { return; }
      if (frame.type === 'error') {
        fail(frame.error?.message || frame.error?.code || 'DSH 事件流错误');
        return;
      }
      if (frame.type !== 'item' || frame.streamId !== streamId) return;
      const value = frame.value;
      if (value?.type !== 'event') return;
      const ev = value.event;
      if (!ev?.type) return;

      if (ev.type === 'turn/start') {
        finalText = '';
        deltaText = '';
        return;
      }
      if (ev.type === 'step/start') {
        const step = ev.data?.step;
        write({ type: 'status', text: step ? `正在执行第 ${step} 步…` : '正在执行…' });
        return;
      }
      if (ev.type === 'tool/start') {
        const name = ev.data?.toolName || ev.data?.name;
        write({ type: 'status', text: name ? `正在使用 ${name}…` : '正在调用工具…' });
        return;
      }
      if (ev.type === 'assistant/chunk') {
        const data = ev.data ?? {};
        const delta = data.delta ?? data.text ?? data.chunk;
        if (typeof delta === 'string' && delta.length > 0) {
          deltaText += delta;
          write({ type: 'delta', text: delta });
        }
        return;
      }
      if (ev.type === 'assistant/message') {
        const messageText = textFromMessage(ev.data?.message);
        if (messageText) finalText = messageText;
        return;
      }
      if (ev.type === 'turn/end') {
        reason = ev.data?.reason?.kind ?? 'completed';
        finish();
      }
    });

    socket.addEventListener('close', () => {
      if (!finished) fail('DSH 事件流提前结束');
    });

    // If the HTTP client goes away, stop the agent turn.
    res.on('close', () => {
      if (!finished) {
        cleanup();
        void cancelTurn();
      }
    });

    // Keep this request alive until turn/end, an error, or cancellation.
    await settled;
  } catch (error) {
    fail(error?.message ?? String(error));
  }
}

// Execute one durable message. This is the same DSH turn as streamPrompt,
// but its lifecycle is independent of any watch HTTP connection: updates go
// into the mailbox and the watch can reconnect at any time.
async function runMessage(id, text, abortSignal) {
  const dsh = await ensureSession();
  const wsUrl = dsh.baseUrl.replace(/^http/, 'ws') + '/api/remote.mux';
  const socket = new WebSocket(wsUrl, { headers: { cookie: dsh.cookie } });
  const streamId = randomUUID();
  let finished = false;
  let finalText = '';
  let deltaText = '';
  let reason = 'completed';
  let timer;
  let markSettled;
  const settled = new Promise((resolve) => { markSettled = resolve; });

  const emit = (event) => {
    if (finished) return;
    if (event.type === 'accepted') {
      updateMessage(id, { status: 'running', sessionId: event.sessionId, phase: 'DSH 已接收，正在执行…' });
    } else if (event.type === 'status') {
      updateMessage(id, { phase: event.text });
    } else if (event.type === 'done') {
      updateMessage(id, { status: 'done', reply: event.text, reason: event.reason, phase: null });
    } else if (event.type === 'error') {
      const current = messages.get(id);
      if (current?.status !== 'withdrawn') {
        updateMessage(id, { status: 'error', error: event.message, phase: null });
      }
    }
  };

  const cleanup = () => {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    try { socket.close(); } catch {}
    markSettled?.();
  };

  const fail = (message) => {
    if (finished) return;
    emit({ type: 'error', message });
    cleanup();
  };

  const finish = () => {
    if (finished) return;
    emit({ type: 'done', text: finalText || deltaText, reason });
    cleanup();
  };

  const cancelTurn = async () => {
    try {
      await rpc(dsh.baseUrl, dsh.cookie, 'session/cancel', { sessionId: dsh.sessionId }, { timeout: 5000 });
    } catch {}
  };

  const onAbort = () => {
    if (finished) return;
    cleanup();
    void cancelTurn();
  };
  abortSignal.addEventListener('abort', onAbort, { once: true });

  try {
    await new Promise((resolve, reject) => {
      const openTimer = setTimeout(() => reject(new Error('连接 DSH 事件流超时')), 5000);
      socket.addEventListener('open', () => { clearTimeout(openTimer); resolve(); }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(openTimer); reject(new Error('连接 DSH 事件流失败')); }, { once: true });
    });

    socket.send(JSON.stringify({
      type: 'open',
      streamId,
      endpoint: 'session/follow',
      payload: { args: { request: { address: { kind: 'session', sessionId: dsh.sessionId } } } },
    }));
    await new Promise((resolve) => setTimeout(resolve, 250));

    const accepted = await rpc(dsh.baseUrl, dsh.cookie, 'session/prompt', {
      sessionId: dsh.sessionId,
      mode: 'queue',
      content: [{ type: 'text', text }],
      requestId: randomUUID(),
    }, { timeout: 15_000 });
    if (!accepted?.accepted) throw new Error('DSH 没有接受这条指令');
    emit({ type: 'accepted', sessionId: dsh.sessionId });
    emit({ type: 'status', text: 'DSH 已接收，正在执行…' });

    timer = setTimeout(() => {
      fail('指令执行超时（10 分钟）');
      void cancelTurn();
    }, config.requestTimeoutMs);

    socket.addEventListener('message', (event) => {
      if (finished) return;
      let frame;
      try { frame = JSON.parse(event.data); } catch { return; }
      if (frame.type === 'error') {
        fail(frame.error?.message || frame.error?.code || 'DSH 事件流错误');
        return;
      }
      if (frame.type !== 'item' || frame.streamId !== streamId) return;
      const value = frame.value;
      if (value?.type !== 'event') return;
      const ev = value.event;
      if (!ev?.type) return;

      if (ev.type === 'turn/start') {
        finalText = '';
        deltaText = '';
        return;
      }
      if (ev.type === 'step/start') {
        const step = ev.data?.step;
        emit({ type: 'status', text: step ? `正在执行第 ${step} 步…` : '正在执行…' });
        return;
      }
      if (ev.type === 'tool/start') {
        const name = ev.data?.toolName || ev.data?.name;
        emit({ type: 'status', text: name ? `正在使用 ${name}…` : '正在调用工具…' });
        return;
      }
      if (ev.type === 'assistant/chunk') {
        const data = ev.data ?? {};
        const delta = data.delta ?? data.text ?? data.chunk;
        if (typeof delta === 'string' && delta.length > 0) deltaText += delta;
        return;
      }
      if (ev.type === 'assistant/message') {
        const messageText = textFromMessage(ev.data?.message);
        if (messageText) finalText = messageText;
        return;
      }
      if (ev.type === 'turn/end') {
        reason = ev.data?.reason?.kind ?? 'completed';
        finish();
      }
    });

    socket.addEventListener('close', () => {
      if (!finished) fail('DSH 事件流提前结束');
    });

    await settled;
  } catch (error) {
    fail(error?.message ?? String(error));
  }
}

async function processQueuedMessage(id) {
  const message = messages.get(id);
  if (!message || message.status === 'withdrawn' || message.status === 'done') return;
  updateMessage(id, { status: 'running', error: null, phase: '准备发送…' });
  const controller = new AbortController();
  activeControllers.set(id, controller);
  try {
    await runMessage(id, message.text, controller.signal);
  } catch (error) {
    if (messages.get(id)?.status !== 'withdrawn') {
      updateMessage(id, { status: 'error', error: error.message, phase: null });
    }
  } finally {
    activeControllers.delete(id);
  }
}

function sendJson(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    // Lossy ZeroTier relay paths often stall on keep-alive; close per request.
    'connection': 'close',
  });
  res.end(body);
}

function authorized(req) {
  if (!config.token) return true;
  const header = req.headers.authorization || '';
  const bearer = header.startsWith('Bearer ') ? header.slice(7) : '';
  const direct = req.headers['x-dsh-token'];
  return bearer === config.token || direct === config.token;

}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('请求体太大'));
        req.destroy();
      } else {
        chunks.push(chunk);
      }
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://localhost');
  if (req.method === 'GET' && url.pathname === '/health') {
    sendJson(res, 200, {
      ok: true,
      service: 'dsh-watch-bridge',
      version: 1,
      busy,
      dsh: cached.baseUrl || null,
      session: state.sessionId || null,
    });
    return;
  }
  if (req.method === 'GET' && url.pathname === '/api/status') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    sendJson(res, 200, { ok: true, busy, sessionId: state.sessionId, dsh: cached.baseUrl });
    return;
  }
  if (req.method === 'POST' && url.pathname === '/api/cancel') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    if (activeAbort) activeAbort.abort();
    sendJson(res, 200, { ok: true });
    return;
  }
  if (req.method === 'POST' && url.pathname === '/api/session/new') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    state.sessionId = '';
    state.workspaceId = '';
    saveState();
    cached = { baseUrl: '', cookie: '', at: 0 };
    sendJson(res, 200, { ok: true });
    return;
  }
  if (req.method === 'POST' && url.pathname === '/api/enqueue') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body;
    try {
      body = JSON.parse(await readBody(req) || '{}');
    } catch (error) {
      return sendJson(res, 400, { ok: false, error: error.message });
    }
    const id = String(body.id ?? '').trim();
    const text = String(body.text ?? '').trim();
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id)) return sendJson(res, 400, { ok: false, error: 'invalid message id' });
    if (!text) return sendJson(res, 400, { ok: false, error: '指令不能为空' });
    if (text.length > config.maxText) {
      return sendJson(res, 413, { ok: false, error: `指令太长（上限 ${config.maxText} 字）` });
    }

    let message = messages.get(id);
    if (!message) {
      message = updateMessage(id, {
        text,
        status: 'queued',
        reply: '',
        error: null,
        phase: '等待发送…',
        createdAt: Date.now(),
      });
    } else if (message.status === 'error' && body.retry === true) {
      message = updateMessage(id, { text, status: 'queued', error: null, phase: '等待重试…' });
    }

    if (message.status === 'withdrawn') {
      return sendJson(res, 409, { ok: false, id, status: message.status, error: '已撤回' });
    }
    if (message.status === 'queued') {
      const job = () => processQueuedMessage(id);
      queue = queue.then(job, job);
    }
    return sendJson(res, 200, { ok: true, id, status: message.status });
  }
  if (req.method === 'GET' && url.pathname === '/api/events') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const after = Number(url.searchParams.get('after') ?? 0) || 0;
    const timeoutSec = Math.min(30, Math.max(1, Number(url.searchParams.get('timeout') ?? 20) || 20));
    const events = await waitForEvents(after, timeoutSec * 1000);
    return sendJson(res, 200, { ok: true, events, lastSeq: messageSeq });
  }
  if (req.method === 'GET' && url.pathname === '/api/result') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const id = String(url.searchParams.get('id') ?? '');
    const message = messages.get(id);
    if (!message) return sendJson(res, 404, { ok: false, error: 'not found' });
    return sendJson(res, 200, { ok: true, message });
  }
  if (req.method === 'POST' && url.pathname === '/api/withdraw') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let id;
    try {
      const body = JSON.parse(await readBody(req) || '{}');
      id = String(body.id ?? '');
    } catch (error) {
      return sendJson(res, 400, { ok: false, error: error.message });
    }
    const message = messages.get(id);
    if (!message) return sendJson(res, 404, { ok: false, error: 'not found' });
    updateMessage(id, { status: 'withdrawn', phase: null });
    activeControllers.get(id)?.abort();
    return sendJson(res, 200, { ok: true, id, status: 'withdrawn' });
  }
  if (req.method === 'POST' && url.pathname === '/api/prompt') {
    if (!authorized(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let text;
    try {
      const body = JSON.parse(await readBody(req) || '{}');
      text = String(body.text ?? '').trim();
    } catch (error) {
      return sendJson(res, 400, { ok: false, error: error.message });
    }
    if (!text) return sendJson(res, 400, { ok: false, error: '指令不能为空' });
    if (text.length > config.maxText) {
      return sendJson(res, 413, { ok: false, error: `指令太长（上限 ${config.maxText} 字）` });
    }

    res.writeHead(200, {
      'content-type': 'application/x-ndjson; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      'connection': 'close',
      'x-accel-buffering': 'no',
    });
    res.flushHeaders?.();

    const controller = new AbortController();
    activeAbort = controller;
    busy = true;
    const run = async () => {
      try {
        await streamPrompt(text, res, controller.signal);
      } catch (error) {
        if (!res.writableEnded) res.write(JSON.stringify({ type: 'error', message: error.message }) + '\n');
      } finally {
        busy = false;
        activeAbort = null;
        if (!res.writableEnded) res.end();
      }
    };
    queue = queue.then(run, run);
    return;
  }
  sendJson(res, 404, { ok: false, error: 'not found' });
});

server.listen(config.port, config.bind, () => {
  log(`DSH Watch Bridge listening on http://${config.bind}:${config.port}`);
  log(`DSH home: ${dshHome}`);
  log(`Workspace: ${workspace}`);
  log(`Auth token: ${config.token ? config.token : '(disabled)'}`);
});
