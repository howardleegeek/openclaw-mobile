import http from 'node:http';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFileSync, existsSync, mkdirSync, writeFileSync, appendFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

import { latLngToCell, kRing } from 'h3-js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Local dev storage (no DB yet): JSON files + JSONL event log.
const DATA_DIR = join(__dirname, '..', 'data');
const NODES_PATH = join(DATA_DIR, 'nodes.json');
const COMMUNITIES_PATH = join(DATA_DIR, 'communities.json');
const EVENTS_PATH = join(DATA_DIR, 'events.jsonl');
const BLOBS_DIR = join(DATA_DIR, 'blobs');
const PUSH_TOKENS_PATH = join(DATA_DIR, 'push-tokens.json');
const PUSH_QUEUE_PATH = join(DATA_DIR, 'push-queue.json');

mkdirSync(DATA_DIR, { recursive: true });
mkdirSync(BLOBS_DIR, { recursive: true });

// Default CORS to '*' for local-dev (node-web is often opened as file:// or hosted on a different port).
// Set CORS_ALLOW_ORIGIN='' explicitly to disable.
const CORS_ALLOW_ORIGIN = process.env.CORS_ALLOW_ORIGIN ?? '*';
const REGISTER_SECRET = process.env.REGISTER_SECRET || '';
const X402_ENABLED = String(process.env.X402_ENABLED || '').toLowerCase() === 'true';
const X402_CURRENCY = process.env.X402_CURRENCY || 'USDC';
const X402_CHAIN = process.env.X402_CHAIN || 'skale';
const X402_DESCRIPTION = process.env.X402_DESCRIPTION || 'Real-Time World Index query';
const X402_PAYMENT_URL = process.env.X402_PAYMENT_URL || 'https://clawvision.org/api';
const X402_DEMO_TOKEN = process.env.X402_DEMO_TOKEN || 'demo-token';
const X402_DEMO_SIGNING_SECRET = process.env.X402_DEMO_SIGNING_SECRET || '';
const X402_ROUTE_PRICES = {
  '/v1/world/cells': 100,
  '/v1/world/events': 200
};
const VISION_EVENT_TYPES = new Set(['motion', 'person', 'vehicle', 'package', 'animal']);
const ONLINE_WINDOW_MS = 10 * 60 * 1000;

// In-memory runtime state (MVP): node heartbeats/health.
const nodeHeartbeats = new Map(); // node_id -> { ...status }

// WebSocket clients: community_id -> Set<WebSocket>
const wsCommunityRooms = new Map();
// WebSocket to community_id mapping for cleanup
const wsToCommunityId = new WeakMap();

function broadcastToCommunity(community_id, message) {
  const clients = wsCommunityRooms.get(community_id);
  if (!clients) return;
  const payload = JSON.stringify(message);
  for (const ws of clients) {
    if (ws.readyState === 1) { // OPEN
      ws.send(payload);
    }
  }
}

function applyCors(res) {
  if (!CORS_ALLOW_ORIGIN) return;
  res.setHeader('access-control-allow-origin', CORS_ALLOW_ORIGIN);
  res.setHeader('access-control-allow-methods', 'GET,POST,OPTIONS');
  res.setHeader('access-control-allow-headers', 'content-type,authorization,x-register-secret,x-payment,payment,x-payment-signature,payment-signature');
}

function loadNodes() {
  if (!existsSync(NODES_PATH)) return { nodes: {} };
  return JSON.parse(readFileSync(NODES_PATH, 'utf8'));
}

function saveNodes(nodes) {
  writeFileSync(NODES_PATH, JSON.stringify(nodes, null, 2) + '\n');
}

function loadCommunities() {
  if (!existsSync(COMMUNITIES_PATH)) return { communities: {} };
  return JSON.parse(readFileSync(COMMUNITIES_PATH, 'utf8'));
}

function saveCommunities(communities) {
  writeFileSync(COMMUNITIES_PATH, JSON.stringify(communities, null, 2) + '\n');
}

function json(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body)
  });
  res.end(body);
}

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return { __parse_error: true, raw };
  }
}

function getAuthToken(req) {
  const auth = req.headers.authorization || '';
  const m = auth.match(/^Bearer\s+(.+)$/i);
  return m ? m[1] : null;
}

function getSingleHeaderValue(value) {
  if (Array.isArray(value)) return String(value[0] || '').trim();
  if (typeof value === 'string') return value.trim();
  return '';
}

function getHeaderValue(req, headerName) {
  const headers = req?.headers || {};
  const direct = headers[headerName];
  if (direct != null) return getSingleHeaderValue(direct);
  const key = Object.keys(headers).find((k) => k.toLowerCase() === headerName);
  return key ? getSingleHeaderValue(headers[key]) : '';
}

function getX402PaymentHeader(req) {
  return getHeaderValue(req, 'x-payment') || getHeaderValue(req, 'payment');
}

function safeEqualHex(hexA, hexB) {
  const a = Buffer.from(hexA, 'hex');
  const b = Buffer.from(hexB, 'hex');
  if (a.length === 0 || b.length === 0 || a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}

function verifyX402Payment(req) {
  const payment = getX402PaymentHeader(req);
  if (!payment) return false;
  if (payment === X402_DEMO_TOKEN) return true;

  // Hackathon placeholder verification:
  // If caller sends a signature header, verify HMAC(payment) with shared env secret.
  const signature = getHeaderValue(req, 'x-payment-signature') || getHeaderValue(req, 'payment-signature');
  if (!X402_DEMO_SIGNING_SECRET || !signature) return false;

  const digest = createHmac('sha256', X402_DEMO_SIGNING_SECRET).update(payment).digest('hex');
  return safeEqualHex(signature, digest);
}

function sendX402PaymentRequired(res, price) {
  return json(res, 402, {
    error: 'payment_required',
    price,
    currency: X402_CURRENCY,
    chain: X402_CHAIN,
    description: X402_DESCRIPTION,
    payment_url: X402_PAYMENT_URL
  });
}

function nowIso() {
  return new Date().toISOString();
}

function newId(prefix) {
  return `${prefix}_${randomBytes(10).toString('hex')}`;
}

function clampNumber(n, min, max) {
  if (!Number.isFinite(n)) return null;
  if (n < min || n > max) return null;
  return n;
}

function normalizeIsoTs(ts) {
  if (typeof ts === 'string' && Number.isFinite(Date.parse(ts))) return ts;
  return nowIso();
}

function parseOptionalNonNegativeNumber(value) {
  if (value == null) return { ok: true, value: null };
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) return { ok: false };
  return { ok: true, value: n };
}

function upsertNodeHeartbeat(node_id, patch) {
  const prev = nodeHeartbeats.get(node_id) || { node_id };
  const next = { ...prev, ...patch, node_id };
  nodeHeartbeats.set(node_id, next);
  return next;
}

function normalizeEvent(body) {
  // Minimal required fields for a spatial "nation".
  const node_id = typeof body?.node_id === 'string' ? body.node_id : null;
  const ts = typeof body?.ts === 'string' ? body.ts : nowIso();
  const lat = clampNumber(body?.lat, -90, 90);
  const lon = clampNumber(body?.lon, -180, 180);
  const heading = body?.heading == null ? null : clampNumber(body.heading, -360, 360);
  const jpeg_base64 = typeof body?.jpeg_base64 === 'string' ? body.jpeg_base64 : null;
  const transcript = typeof body?.transcript === 'string' ? body.transcript : null;

  if (!node_id || lat == null || lon == null || !jpeg_base64) {
    return { ok: false, error: 'missing required fields: node_id, lat, lon, jpeg_base64' };
  }

  // Spatial partition: default H3 resolution 9 (~0.1-0.2km^2). Tune later.
  const h3_res = Number.isInteger(body?.h3_res) ? body.h3_res : 9;
  const cell = latLngToCell(lat, lon, h3_res);

  // Store JPEG bytes as a blob on disk (MVP). This avoids returning huge base64 in queries.
  let jpegBytes;
  try {
    jpegBytes = Buffer.from(jpeg_base64, 'base64');
  } catch {
    return { ok: false, error: 'invalid jpeg_base64 (not base64)' };
  }
  if (!jpegBytes || jpegBytes.length === 0) {
    return { ok: false, error: 'invalid jpeg_base64 (empty)' };
  }

  return {
    ok: true,
    event: {
      id: newId('evt'),
      type: 'frame',
      ts,
      node_id,
      lat,
      lon,
      heading,
      cell,
      h3_res,
      transcript,
      jpeg_size_bytes: jpegBytes.length,
      // Relative path under data/; client can use preview_url to fetch.
      jpeg_blob: null
    },
    jpegBytes
  };
}

function loadPushTokens() {
  if (!existsSync(PUSH_TOKENS_PATH)) return { tokens: {} };
  try {
    return JSON.parse(readFileSync(PUSH_TOKENS_PATH, 'utf8'));
  } catch {
    return { tokens: {} };
  }
}

function savePushTokens(data) {
  try {
    writeFileSync(PUSH_TOKENS_PATH, JSON.stringify(data, null, 2) + '\n');
  } catch {
    // Silent fail for MVP
  }
}

function loadPushQueue() {
  if (!existsSync(PUSH_QUEUE_PATH)) return { queue: [] };
  try {
    return JSON.parse(readFileSync(PUSH_QUEUE_PATH, 'utf8'));
  } catch {
    return { queue: [] };
  }
}

function savePushQueue(data) {
  try {
    writeFileSync(PUSH_QUEUE_PATH, JSON.stringify(data, null, 2) + '\n');
  } catch {
    // Silent fail for MVP
  }
}

function addToPushQueue(item) {
  const data = loadPushQueue();
  data.queue.push({
    ...item,
    id: newId('push'),
    created_at: nowIso()
  });
  savePushQueue(data);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');

  applyCors(res);
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }

  // Health
  if (req.method === 'GET' && url.pathname === '/health') {
    return json(res, 200, { ok: true, ts: nowIso() });
  }

  // x402 protection (hackathon mode): enabled only when X402_ENABLED=true.
  const routePrice = req.method === 'GET' ? X402_ROUTE_PRICES[url.pathname] : undefined;
  if (X402_ENABLED && routePrice != null && !verifyX402Payment(req)) {
    return sendX402PaymentRequired(res, routePrice);
  }

  // Register node
  // POST /v1/nodes/register { name?, capabilities?, lat?, lon? }
  if (req.method === 'POST' && url.pathname === '/v1/nodes/register') {
    if (REGISTER_SECRET) {
      const provided = String(req.headers['x-register-secret'] || '');
      if (provided !== REGISTER_SECRET) {
        return json(res, 403, { ok: false, error: 'invalid register secret' });
      }
    }
    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const nodesDb = loadNodes();

    const node_id = newId('node');
    const token = newId('tok');
    const name = typeof body?.name === 'string' ? body.name : null;
    const capabilities = Array.isArray(body?.capabilities) ? body.capabilities : [];
    const lat = clampNumber(Number(body?.lat), -90, 90);
    const lon = clampNumber(Number(body?.lon), -180, 180);

    nodesDb.nodes[node_id] = {
      node_id,
      token,
      name,
      capabilities,
      lat,
      lon,
      created_at: nowIso()
    };
    saveNodes(nodesDb);

    return json(res, 200, {
      ok: true,
      node_id,
      token,
      ingest_url: '/v1/events/frame'
    });
  }

  // Node heartbeat
  // POST /v1/nodes/heartbeat
  // {
  //   node_id, ts?, battery_pct?, wifi?, frames_sent?, events_detected?, lat?, lon?
  // }
  if (req.method === 'POST' && url.pathname === '/v1/nodes/heartbeat') {
    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const node_id = typeof body?.node_id === 'string' ? body.node_id.trim() : '';
    if (!node_id) return json(res, 400, { ok: false, error: 'missing node_id' });

    const ts = normalizeIsoTs(body?.ts);

    const batteryRaw = body?.battery_pct;
    let battery = null;
    if (batteryRaw != null) {
      battery = Number(batteryRaw);
      if (!Number.isFinite(battery) || battery < 0 || battery > 100) {
        return json(res, 400, { ok: false, error: 'invalid battery_pct (expected 0..100)' });
      }
    }

    const framesSent = parseOptionalNonNegativeNumber(body?.frames_sent);
    if (!framesSent.ok) {
      return json(res, 400, { ok: false, error: 'invalid frames_sent (expected >= 0)' });
    }
    const eventsDetected = parseOptionalNonNegativeNumber(body?.events_detected);
    if (!eventsDetected.ok) {
      return json(res, 400, { ok: false, error: 'invalid events_detected (expected >= 0)' });
    }

    const latProvided = body?.lat != null;
    const lonProvided = body?.lon != null;
    if (latProvided !== lonProvided) {
      return json(res, 400, { ok: false, error: 'lat/lon must be provided together' });
    }
    let location = null;
    if (latProvided && lonProvided) {
      const lat = clampNumber(Number(body.lat), -90, 90);
      const lon = clampNumber(Number(body.lon), -180, 180);
      if (lat == null || lon == null) {
        return json(res, 400, { ok: false, error: 'invalid lat/lon' });
      }
      location = { lat, lon };
    }

    const nodesDb = loadNodes();
    const reg = nodesDb?.nodes?.[node_id];
    const prev = nodeHeartbeats.get(node_id);
    const resolvedLocation = location
      || prev?.location
      || ((Number.isFinite(reg?.lat) && Number.isFinite(reg?.lon)) ? { lat: reg.lat, lon: reg.lon } : null);

    upsertNodeHeartbeat(node_id, {
      last_heartbeat: ts,
      battery: battery ?? prev?.battery ?? null,
      wifi: body?.wifi ?? prev?.wifi ?? null,
      frames_sent: framesSent.value ?? prev?.frames_sent ?? null,
      events_detected: eventsDetected.value ?? prev?.events_detected ?? null,
      location: resolvedLocation
    });

    return json(res, 200, { ok: true, server_ts: nowIso() });
  }

  // Ingest frame event
  // POST /v1/events/frame Authorization: Bearer <token>
  if (req.method === 'POST' && url.pathname === '/v1/events/frame') {
    const token = getAuthToken(req);
    if (!token) return json(res, 401, { ok: false, error: 'missing bearer token' });

    const nodesDb = loadNodes();
    const node = Object.values(nodesDb.nodes).find((n) => n.token === token);
    if (!node) return json(res, 403, { ok: false, error: 'invalid token' });

    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    // Force node_id from auth
    const norm = normalizeEvent({ ...body, node_id: node.node_id });
    if (!norm.ok) return json(res, 400, { ok: false, error: norm.error });

    const evt = norm.event;
    const blobName = `${evt.id}.jpg`;
    const blobPath = join(BLOBS_DIR, blobName);
    evt.jpeg_blob = `blobs/${blobName}`;

    try {
      writeFileSync(blobPath, norm.jpegBytes);
    } catch {
      return json(res, 500, { ok: false, error: 'failed to persist jpeg blob' });
    }

    appendFileSync(EVENTS_PATH, JSON.stringify(evt) + '\n');
    return json(res, 200, {
      ok: true,
      id: evt.id,
      cell: evt.cell,
      preview_url: `/v1/blobs/${blobName}`
    });
  }

  // Ingest ClawVision event
  // POST /v1/vision/events
  // { node_id, ts?, lat, lon, event_type, confidence, jpeg_base64?, metadata? }
  if (req.method === 'POST' && url.pathname === '/v1/vision/events') {
    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const node_id = typeof body?.node_id === 'string' ? body.node_id.trim() : '';
    if (!node_id) return json(res, 400, { ok: false, error: 'missing node_id' });

    const lat = clampNumber(Number(body?.lat), -90, 90);
    const lon = clampNumber(Number(body?.lon), -180, 180);
    if (lat == null || lon == null) {
      return json(res, 400, { ok: false, error: 'missing/invalid lat, lon' });
    }

    const event_type = typeof body?.event_type === 'string' ? body.event_type.toLowerCase() : '';
    if (!VISION_EVENT_TYPES.has(event_type)) {
      return json(res, 400, {
        ok: false,
        error: `invalid event_type (expected one of: ${Array.from(VISION_EVENT_TYPES).join(', ')})`
      });
    }

    const confidence = Number(body?.confidence);
    if (!Number.isFinite(confidence) || confidence < 0 || confidence > 1) {
      return json(res, 400, { ok: false, error: 'invalid confidence (expected 0..1)' });
    }

    if (body?.metadata != null && typeof body.metadata !== 'object') {
      return json(res, 400, { ok: false, error: 'invalid metadata (expected object/array)' });
    }

    const ts = normalizeIsoTs(body?.ts);
    const h3_res = Number.isInteger(body?.h3_res) ? body.h3_res : 9;
    if (!Number.isInteger(h3_res) || h3_res < 0 || h3_res > 15) {
      return json(res, 400, { ok: false, error: 'invalid h3_res (expected integer 0..15)' });
    }

    const cell = latLngToCell(lat, lon, h3_res);
    const event_id = newId('evt');

    let jpeg_blob = null;
    let jpeg_size_bytes = 0;
    if (body?.jpeg_base64 != null) {
      if (typeof body.jpeg_base64 !== 'string') {
        return json(res, 400, { ok: false, error: 'invalid jpeg_base64 (expected base64 string)' });
      }
      let jpegBytes;
      try {
        jpegBytes = Buffer.from(body.jpeg_base64, 'base64');
      } catch {
        return json(res, 400, { ok: false, error: 'invalid jpeg_base64 (not base64)' });
      }
      if (!jpegBytes || jpegBytes.length === 0) {
        return json(res, 400, { ok: false, error: 'invalid jpeg_base64 (empty)' });
      }
      const blobName = `${event_id}.jpg`;
      const blobPath = join(BLOBS_DIR, blobName);
      try {
        writeFileSync(blobPath, jpegBytes);
      } catch {
        return json(res, 500, { ok: false, error: 'failed to persist jpeg blob' });
      }
      jpeg_blob = `blobs/${blobName}`;
      jpeg_size_bytes = jpegBytes.length;
    }

    const evt = {
      id: event_id,
      type: 'vision',
      ts,
      node_id,
      lat,
      lon,
      cell,
      h3_res,
      event_type,
      confidence,
      metadata: body?.metadata ?? null,
      jpeg_size_bytes,
      jpeg_blob
    };
    appendFileSync(EVENTS_PATH, JSON.stringify(evt) + '\n');

    const prev = nodeHeartbeats.get(node_id);
    upsertNodeHeartbeat(node_id, {
      location: { lat, lon },
      events_detected: typeof prev?.events_detected === 'number' ? prev.events_detected + 1 : prev?.events_detected ?? 1
    });

    // Broadcast to WebSocket clients in matching communities
    const matchingCommunities = findCommunitiesForCell(cell);
    for (const community_id of matchingCommunities) {
      broadcastToCommunity(community_id, {
        type: 'vision_event',
        community_id,
        event: {
          id: evt.id,
          ts: evt.ts,
          node_id: evt.node_id,
          lat: evt.lat,
          lon: evt.lon,
          cell: evt.cell,
          event_type: evt.event_type,
          confidence: evt.confidence,
          jpeg_blob: evt.jpeg_blob
        }
      });
    }

    return json(res, 200, { ok: true, event_id, cell });
  }

  // Serve blobs (MVP local only)
  // GET /v1/blobs/<evt_id>.jpg
  if (req.method === 'GET' && url.pathname.startsWith('/v1/blobs/')) {
    const name = url.pathname.slice('/v1/blobs/'.length);
    if (!name || name.includes('..') || name.includes('/')) {
      return json(res, 400, { ok: false, error: 'invalid blob name' });
    }
    const blobPath = join(BLOBS_DIR, name);
    if (!existsSync(blobPath)) return json(res, 404, { ok: false, error: 'blob not found' });
    const bytes = readFileSync(blobPath);
    res.writeHead(200, { 'content-type': 'image/jpeg', 'content-length': bytes.length });
    return res.end(bytes);
  }

  // Online nodes based on heartbeat recency
  // GET /v1/nodes/online
  if (req.method === 'GET' && url.pathname === '/v1/nodes/online') {
    const cutoff = Date.now() - ONLINE_WINDOW_MS;
    const nodesDb = loadNodes();
    const nodes = [];

    for (const [node_id, status] of nodeHeartbeats.entries()) {
      const tsMs = Date.parse(status?.last_heartbeat || '');
      if (!Number.isFinite(tsMs) || tsMs < cutoff) continue;

      const reg = nodesDb?.nodes?.[node_id];
      const location = status?.location
        || ((Number.isFinite(reg?.lat) && Number.isFinite(reg?.lon)) ? { lat: reg.lat, lon: reg.lon } : null);

      nodes.push({
        node_id,
        last_heartbeat: status.last_heartbeat,
        battery: status?.battery ?? null,
        location
      });
    }

    nodes.sort((a, b) => Date.parse(b.last_heartbeat) - Date.parse(a.last_heartbeat));
    return json(res, 200, { ok: true, window_minutes: 10, nodes });
  }

  // Aggregate vision-only coverage by H3 cell
  // GET /v1/vision/coverage?hours=24&res=9&limit=5000
  if (req.method === 'GET' && url.pathname === '/v1/vision/coverage') {
    const resRaw = url.searchParams.get('res');
    const h3Res = resRaw == null ? 9 : Number(resRaw);
    if (!Number.isInteger(h3Res) || h3Res < 0 || h3Res > 15) {
      return json(res, 400, { ok: false, error: 'invalid res (expected integer 0..15)' });
    }

    const hoursRaw = url.searchParams.get('hours');
    const hours = hoursRaw == null || hoursRaw === '' ? 24 : Number(hoursRaw);
    if (!Number.isFinite(hours) || hours <= 0) {
      return json(res, 400, { ok: false, error: 'invalid hours (expected > 0)' });
    }
    const sinceMs = Date.now() - hours * 3600 * 1000;

    const limitRaw = url.searchParams.get('limit');
    const limit = Math.min(50000, Math.max(1, Number(limitRaw ?? '5000')));
    if (!Number.isFinite(limit)) {
      return json(res, 400, { ok: false, error: 'invalid limit' });
    }

    if (!existsSync(EVENTS_PATH)) {
      return json(res, 200, {
        ok: true,
        res: h3Res,
        since: new Date(sinceMs).toISOString(),
        total_events: 0,
        unique_cells: 0,
        truncated: false,
        cells: []
      });
    }

    const counts = new Map(); // cell -> count
    let totalEvents = 0;
    let skipped = 0;

    const raw = readFileSync(EVENTS_PATH, 'utf8');
    const lines = raw.split('\n');
    for (const line of lines) {
      if (!line) continue;
      let evt;
      try {
        evt = JSON.parse(line);
      } catch {
        skipped++;
        continue;
      }

      if (evt?.type !== 'vision') continue;

      const tsMs = Date.parse(evt?.ts || '');
      if (Number.isFinite(tsMs) && tsMs < sinceMs) continue;

      const lat = evt?.lat;
      const lon = evt?.lon;
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
        skipped++;
        continue;
      }

      let cell;
      try {
        cell = latLngToCell(lat, lon, h3Res);
      } catch {
        skipped++;
        continue;
      }

      totalEvents++;
      counts.set(cell, (counts.get(cell) || 0) + 1);
    }

    const cellsAll = Array.from(counts.entries()).map(([cell, count]) => ({ cell, count }));
    cellsAll.sort((a, b) => b.count - a.count);
    const truncated = cellsAll.length > limit;
    const cells = truncated ? cellsAll.slice(0, limit) : cellsAll;

    return json(res, 200, {
      ok: true,
      res: h3Res,
      since: new Date(sinceMs).toISOString(),
      total_events: totalEvents,
      unique_cells: counts.size,
      skipped_lines: skipped,
      truncated,
      cells
    });
  }

  // Aggregate coverage by H3 cell (MVP)
  // GET /v1/world/cells?res=9&limit=5000&hours=24
  // - res: target H3 resolution to aggregate into (default 9)
  // - limit: max number of cells returned (default 5000)
  // - hours: optional lookback window; if provided only counts events with ts >= now-hours
  if (req.method === 'GET' && url.pathname === '/v1/world/cells') {
    const resRaw = url.searchParams.get('res');
    const h3Res = resRaw == null ? 9 : Number(resRaw);
    if (!Number.isInteger(h3Res) || h3Res < 0 || h3Res > 15) {
      return json(res, 400, { ok: false, error: 'invalid res (expected integer 0..15)' });
    }

    const limitRaw = url.searchParams.get('limit');
    const limit = Math.min(50000, Math.max(1, Number(limitRaw ?? '5000')));
    if (!Number.isFinite(limit)) {
      return json(res, 400, { ok: false, error: 'invalid limit' });
    }

    const hoursRaw = url.searchParams.get('hours');
    let sinceMs = null;
    if (hoursRaw != null && hoursRaw !== '') {
      const hours = Number(hoursRaw);
      if (!Number.isFinite(hours) || hours <= 0) {
        return json(res, 400, { ok: false, error: 'invalid hours (expected > 0)' });
      }
      sinceMs = Date.now() - hours * 3600 * 1000;
    }

    if (!existsSync(EVENTS_PATH)) {
      return json(res, 200, {
        ok: true,
        res: h3Res,
        since: sinceMs ? new Date(sinceMs).toISOString() : null,
        total_events: 0,
        unique_cells: 0,
        truncated: false,
        cells: []
      });
    }

    const counts = new Map(); // cell -> count
    let totalEvents = 0;
    let skipped = 0;
    let minCount = Infinity;
    let maxCount = 0;

    const raw = readFileSync(EVENTS_PATH, 'utf8');
    const lines = raw.split('\n');
    for (const line of lines) {
      if (!line) continue;
      let evt;
      try {
        evt = JSON.parse(line);
      } catch {
        skipped++;
        continue;
      }

      // Optional time filter
      if (sinceMs != null) {
        const tsMs = Date.parse(evt?.ts || '');
        if (Number.isFinite(tsMs) && tsMs < sinceMs) continue;
      }

      const lat = evt?.lat;
      const lon = evt?.lon;
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
        skipped++;
        continue;
      }

      let cell;
      try {
        cell = latLngToCell(lat, lon, h3Res);
      } catch {
        skipped++;
        continue;
      }

      totalEvents++;
      const next = (counts.get(cell) || 0) + 1;
      counts.set(cell, next);
      if (next < minCount) minCount = next;
      if (next > maxCount) maxCount = next;
    }

    // Keep response small: sort by count desc, return top N.
    const cellsAll = Array.from(counts.entries()).map(([cell, count]) => ({ cell, count }));
    cellsAll.sort((a, b) => b.count - a.count);
    const truncated = cellsAll.length > limit;
    const cells = truncated ? cellsAll.slice(0, limit) : cellsAll;

    return json(res, 200, {
      ok: true,
      res: h3Res,
      since: sinceMs ? new Date(sinceMs).toISOString() : null,
      total_events: totalEvents,
      unique_cells: counts.size,
      skipped_lines: skipped,
      min_count: Number.isFinite(minCount) ? minCount : 0,
      max_count: maxCount,
      truncated,
      cells
    });
  }

  // World ingest stats (MVP)
  // GET /v1/world/stats?res=9&hours=24
  // - res: target H3 resolution to aggregate into (default 9)
  // - hours: optional lookback window; if provided only counts events with ts >= now-hours
  if (req.method === 'GET' && url.pathname === '/v1/world/stats') {
    const resRaw = url.searchParams.get('res');
    const h3Res = resRaw == null ? 9 : Number(resRaw);
    if (!Number.isInteger(h3Res) || h3Res < 0 || h3Res > 15) {
      return json(res, 400, { ok: false, error: 'invalid res (expected integer 0..15)' });
    }

    const hoursRaw = url.searchParams.get('hours');
    let sinceMs = null;
    if (hoursRaw != null && hoursRaw !== '') {
      const hours = Number(hoursRaw);
      if (!Number.isFinite(hours) || hours <= 0) {
        return json(res, 400, { ok: false, error: 'invalid hours (expected > 0)' });
      }
      sinceMs = Date.now() - hours * 3600 * 1000;
    }

    const nodesDb = loadNodes();
    const nodesTotal = Object.keys(nodesDb?.nodes || {}).length;

    if (!existsSync(EVENTS_PATH)) {
      return json(res, 200, {
        ok: true,
        res: h3Res,
        since: sinceMs ? new Date(sinceMs).toISOString() : null,
        nodes_total: nodesTotal,
        events_total: 0,
        unique_cells: 0,
        active_nodes: 0,
        skipped_lines: 0,
        last_event: null
      });
    }

    const cells = new Set();
    const activeNodes = new Set();
    let totalEvents = 0;
    let skipped = 0;
    let lastEvent = null;
    let lastEventTsMs = -Infinity;

    const raw = readFileSync(EVENTS_PATH, 'utf8');
    const lines = raw.split('\n');
    for (const line of lines) {
      if (!line) continue;
      let evt;
      try {
        evt = JSON.parse(line);
      } catch {
        skipped++;
        continue;
      }

      // Optional time filter
      if (sinceMs != null) {
        const tsMs = Date.parse(evt?.ts || '');
        if (Number.isFinite(tsMs) && tsMs < sinceMs) continue;
      }

      const lat = evt?.lat;
      const lon = evt?.lon;
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
        skipped++;
        continue;
      }

      let cell;
      try {
        cell = latLngToCell(lat, lon, h3Res);
      } catch {
        skipped++;
        continue;
      }

      totalEvents++;
      cells.add(cell);
      if (typeof evt?.node_id === 'string') activeNodes.add(evt.node_id);

      const tsMs = Date.parse(evt?.ts || '');
      if (Number.isFinite(tsMs) && tsMs > lastEventTsMs) {
        lastEventTsMs = tsMs;
        const preview_url = evt.jpeg_blob ? `/v1/${evt.jpeg_blob}` : null;
        lastEvent = {
          id: typeof evt?.id === 'string' ? evt.id : null,
          ts: typeof evt?.ts === 'string' ? evt.ts : null,
          node_id: typeof evt?.node_id === 'string' ? evt.node_id : null,
          lat,
          lon,
          cell,
          preview_url
        };
      }
    }

    return json(res, 200, {
      ok: true,
      res: h3Res,
      since: sinceMs ? new Date(sinceMs).toISOString() : null,
      nodes_total: nodesTotal,
      events_total: totalEvents,
      unique_cells: cells.size,
      active_nodes: activeNodes.size,
      skipped_lines: skipped,
      last_event: lastEvent
    });
  }

  // Query by cell/time (MVP)
  // GET /v1/world/events?cell=<h3>&limit=50[&res=9]
  // If res is provided, events are matched by computing lat/lon -> cell at that resolution.
  if (req.method === 'GET' && url.pathname === '/v1/world/events') {
    const cell = url.searchParams.get('cell');
    const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit') ?? '50')));
    if (!cell) return json(res, 400, { ok: false, error: 'missing cell' });

    const resRaw = url.searchParams.get('res');
    const h3Res = resRaw == null ? null : Number(resRaw);
    if (h3Res != null && (!Number.isInteger(h3Res) || h3Res < 0 || h3Res > 15)) {
      return json(res, 400, { ok: false, error: 'invalid res (expected integer 0..15)' });
    }

    if (!existsSync(EVENTS_PATH)) {
      return json(res, 200, { ok: true, events: [] });
    }

    const lines = readFileSync(EVENTS_PATH, 'utf8').trim().split('\n');
    const events = [];
    for (let i = lines.length - 1; i >= 0 && events.length < limit; i--) {
      const line = lines[i];
      if (!line) continue;
      try {
        const evt = JSON.parse(line);
        const match = h3Res == null
          ? evt.cell === cell
          : (Number.isFinite(evt?.lat) && Number.isFinite(evt?.lon) && latLngToCell(evt.lat, evt.lon, h3Res) === cell);
        if (match) {
          const preview_url = evt.jpeg_blob ? `/v1/${evt.jpeg_blob}` : null;
          events.push({ ...evt, preview_url });
        }
      } catch {
        // ignore
      }
    }

    return json(res, 200, { ok: true, events });
  }

  // ===== COMMUNITIES =====

  // Generate 8-char invite code from random bytes
  function generateInviteCode() {
    return randomBytes(4).toString('hex');
  }

  // Authenticate node from bearer token and return node_id
  function authenticateNode(token) {
    if (!token) return null;
    const nodesDb = loadNodes();
    const node = Object.values(nodesDb.nodes).find((n) => n.token === token);
    return node?.node_id || null;
  }

  // Find communities that include the given cell
  function findCommunitiesForCell(cell) {
    const communitiesDb = loadCommunities();
    const matchingCommunities = [];
    for (const [cid, com] of Object.entries(communitiesDb.communities || {})) {
      if (com?.h3_cells?.includes(cell)) {
        matchingCommunities.push(cid);
      }
    }
    return matchingCommunities;
  }

  // POST /v1/communities - create community
  // { name, lat, lon, radius_km=5 }
  if (req.method === 'POST' && url.pathname === '/v1/communities') {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const name = typeof body?.name === 'string' ? body.name.trim() : '';
    if (!name) return json(res, 400, { ok: false, error: 'missing name' });

    const lat = clampNumber(Number(body?.lat), -90, 90);
    const lon = clampNumber(Number(body?.lon), -180, 180);
    if (lat == null || lon == null) {
      return json(res, 400, { ok: false, error: 'missing or invalid lat/lon' });
    }

    const radiusKm = Number(body?.radius_km ?? 5);
    if (!Number.isFinite(radiusKm) || radiusKm < 0.1 || radiusKm > 100) {
      return json(res, 400, { ok: false, error: 'invalid radius_km (expected 0.1..100)' });
    }

    // Generate H3 cells for the community (kRing at res 9)
    const h3Res = 9;
    const centerCell = latLngToCell(lat, lon, h3Res);
    // Approx kRing radius: k=1 is ~2-3km at res9, so k≈radius/2
    const kRingSize = Math.max(1, Math.round(radiusKm / 2));
    const h3Cells = kRing(centerCell, kRingSize);

    const communitiesDb = loadCommunities();
    const community_id = newId('com');
    const inviteCode = generateInviteCode();

    communitiesDb.communities[community_id] = {
      community_id,
      name,
      lat,
      lon,
      radius_km: radiusKm,
      h3_res: h3Res,
      h3_cells: h3Cells,
      invite_code: inviteCode,
      created_by: node_id,
      created_at: nowIso(),
      members: { [node_id]: { node_id, joined_at: nowIso(), role: 'admin' } }
    };
    saveCommunities(communitiesDb);

    return json(res, 201, {
      ok: true,
      community_id,
      name,
      invite_code: inviteCode,
      h3_cells: h3Cells.length,
      created_at: nowIso()
    });
  }

  // POST /v1/communities/join - join by invite code
  // { invite_code }
  if (req.method === 'POST' && url.pathname === '/v1/communities/join') {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const inviteCode = typeof body?.invite_code === 'string' ? body.invite_code.trim() : '';
    if (!inviteCode) return json(res, 400, { ok: false, error: 'missing invite_code' });

    const communitiesDb = loadCommunities();
    let foundCommunityId = null;
    for (const [cid, com] of Object.entries(communitiesDb.communities || {})) {
      if (com?.invite_code === inviteCode) {
        foundCommunityId = cid;
        break;
      }
    }

    if (!foundCommunityId) {
      return json(res, 404, { ok: false, error: 'invalid invite_code' });
    }

    const community = communitiesDb.communities[foundCommunityId];
    if (community.members?.[node_id]) {
      return json(res, 400, { ok: false, error: 'already a member of this community' });
    }

    community.members[node_id] = { node_id, joined_at: nowIso(), role: 'member' };
    saveCommunities(communitiesDb);

    return json(res, 200, {
      ok: true,
      community_id: foundCommunityId,
      name: community.name,
      joined_at: nowIso()
    });
  }

  // GET /v1/communities/mine - list my communities
  if (req.method === 'GET' && url.pathname === '/v1/communities/mine') {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const communitiesDb = loadCommunities();
    const myCommunities = [];
    for (const [cid, com] of Object.entries(communitiesDb.communities || {})) {
      if (com?.members?.[node_id]) {
        myCommunities.push({
          community_id: cid,
          name: com.name,
          lat: com.lat,
          lon: com.lon,
          radius_km: com.radius_km,
          role: com.members[node_id].role,
          joined_at: com.members[node_id].joined_at,
          created_at: com.created_at,
          member_count: Object.keys(com.members || {}).length
        });
      }
    }

    myCommunities.sort((a, b) => Date.parse(b.joined_at) - Date.parse(a.joined_at));
    return json(res, 200, { ok: true, communities: myCommunities });
  }

  // GET /v1/communities/:id - community detail
  if (req.method === 'GET' && url.pathname.match(/^\/v1\/communities\/[a-z]+_[a-f0-9]+$/)) {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const community_id = url.pathname.split('/')[3];
    const communitiesDb = loadCommunities();
    const community = communitiesDb.communities?.[community_id];

    if (!community) {
      return json(res, 404, { ok: false, error: 'community not found' });
    }

    if (!community.members?.[node_id]) {
      return json(res, 403, { ok: false, error: 'not a member of this community' });
    }

    const members = Object.values(community.members || {}).map((m) => ({
      node_id: m.node_id,
      role: m.role,
      joined_at: m.joined_at
    }));

    return json(res, 200, {
      ok: true,
      community_id: community.community_id,
      name: community.name,
      lat: community.lat,
      lon: community.lon,
      radius_km: community.radius_km,
      h3_res: community.h3_res,
      h3_cells: community.h3_cells,
      created_by: community.created_by,
      created_at: community.created_at,
      members,
      member_count: members.length
    });
  }

  // GET /v1/communities/:id/alerts - filter events by community h3Cells
  // ?limit=50
  if (req.method === 'GET' && url.pathname.match(/^\/v1\/communities\/[a-z]+_[a-f0-9]+\/alerts$/)) {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const community_id = url.pathname.split('/')[3];
    const communitiesDb = loadCommunities();
    const community = communitiesDb.communities?.[community_id];

    if (!community) {
      return json(res, 404, { ok: false, error: 'community not found' });
    }

    if (!community.members?.[node_id]) {
      return json(res, 403, { ok: false, error: 'not a member of this community' });
    }

    const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit') ?? '50')));
    const communityCells = new Set(community.h3_cells || []);

    if (!existsSync(EVENTS_PATH) || communityCells.size === 0) {
      return json(res, 200, { ok: true, events: [] });
    }

    const lines = readFileSync(EVENTS_PATH, 'utf8').trim().split('\n');
    const events = [];
    for (let i = lines.length - 1; i >= 0 && events.length < limit; i--) {
      const line = lines[i];
      if (!line) continue;
      try {
        const evt = JSON.parse(line);
        if (communityCells.has(evt.cell)) {
          const preview_url = evt.jpeg_blob ? `/v1/${evt.jpeg_blob}` : null;
          events.push({ ...evt, preview_url });
        }
      } catch {
        // ignore
      }
    }

    return json(res, 200, { ok: true, events });
  }

  // POST /v1/communities/:id/alerts - broadcast alert
  // { message, type='alert', lat?, lon? }
  if (req.method === 'POST' && url.pathname.match(/^\/v1\/communities\/[a-z]+_[a-f0-9]+\/alerts$/)) {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const community_id = url.pathname.split('/')[3];
    const communitiesDb = loadCommunities();
    const community = communitiesDb.communities?.[community_id];

    if (!community) {
      return json(res, 404, { ok: false, error: 'community not found' });
    }

    if (!community.members?.[node_id]) {
      return json(res, 403, { ok: false, error: 'not a member of this community' });
    }

    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const message = typeof body?.message === 'string' ? body.message.trim() : '';
    if (!message) return json(res, 400, { ok: false, error: 'missing message' });

    const type = typeof body?.type === 'string' ? body.type.trim() : 'alert';
    const lat = body?.lat != null ? clampNumber(Number(body.lat), -90, 90) : null;
    const lon = body?.lon != null ? clampNumber(Number(body.lon), -180, 180) : null;

    // If lat/lon provided, must use community cell
    let cell = null;
    if (lat != null && lon != null) {
      const h3Res = community.h3_res || 9;
      cell = latLngToCell(lat, lon, h3Res);
    }

    const evt = {
      id: newId('evt'),
      type: 'alert',
      community_id,
      ts: nowIso(),
      node_id,
      message,
      alert_type: type,
      lat,
      lon,
      cell,
      h3_res: community.h3_res || 9
    };

    appendFileSync(EVENTS_PATH, JSON.stringify(evt) + '\n');

    // Broadcast to WebSocket clients in this community
    broadcastToCommunity(community_id, {
      type: 'community_alert',
      community_id,
      alert: {
        id: evt.id,
        ts: evt.ts,
        node_id: evt.node_id,
        message: evt.message,
        alert_type: evt.alert_type,
        lat: evt.lat,
        lon: evt.lon,
        cell: evt.cell
      }
    });

    return json(res, 200, { ok: true, id: evt.id, ts: evt.ts });
  }

  // DELETE /v1/communities/:id/members/me - leave community
  if (req.method === 'DELETE' && url.pathname.match(/^\/v1\/communities\/[a-z]+_[a-f0-9]+\/members\/me$/)) {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const community_id = url.pathname.split('/')[3];
    const communitiesDb = loadCommunities();
    const community = communitiesDb.communities?.[community_id];

    if (!community) {
      return json(res, 404, { ok: false, error: 'community not found' });
    }

    if (!community.members?.[node_id]) {
      return json(res, 400, { ok: false, error: 'not a member of this community' });
    }

    delete community.members[node_id];
    saveCommunities(communitiesDb);

    return json(res, 200, { ok: true, message: 'left community' });
  }

  // ===== PUSH NOTIFICATIONS =====

  // POST /v1/push/register - register push token
  // { token, platform }
  if (req.method === 'POST' && url.pathname === '/v1/push/register') {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const pushToken = typeof body?.token === 'string' ? body.token.trim() : '';
    if (!pushToken) return json(res, 400, { ok: false, error: 'missing token' });

    const platform = typeof body?.platform === 'string' ? body.platform.trim() : '';
    if (!platform) return json(res, 400, { ok: false, error: 'missing platform' });

    const data = loadPushTokens();
    data.tokens[node_id] = {
      node_id,
      token: pushToken,
      platform,
      registered_at: nowIso()
    };
    savePushTokens(data);

    return json(res, 200, { ok: true, node_id });
  }

  // POST /v1/push/send - queue push notification
  // { community_id?, node_id?, title, body, data? }
  if (req.method === 'POST' && url.pathname === '/v1/push/send') {
    const token = getAuthToken(req);
    const node_id = authenticateNode(token);
    if (!node_id) return json(res, 401, { ok: false, error: 'missing or invalid bearer token' });

    const body = await readJson(req);
    if (body?.__parse_error) {
      return json(res, 400, { ok: false, error: 'invalid json' });
    }

    const title = typeof body?.title === 'string' ? body.title.trim() : '';
    const messageBody = typeof body?.body === 'string' ? body.body.trim() : '';
    if (!title || !messageBody) {
      return json(res, 400, { ok: false, error: 'missing title or body' });
    }

    const pushData = typeof body?.data === 'object' && body.data !== null ? body.data : {};

    // Determine target: community or specific node
    let targetCommunityId = typeof body?.community_id === 'string' ? body.community_id.trim() : null;
    let targetNodeId = typeof body?.node_id === 'string' ? body.node_id.trim() : null;

    // If community_id provided, get all member node_ids
    if (targetCommunityId) {
      const communitiesDb = loadCommunities();
      const community = communitiesDb.communities?.[targetCommunityId];
      if (!community || !community.members?.[node_id]) {
        return json(res, 403, { ok: false, error: 'not a member of this community' });
      }
    } else if (!targetNodeId) {
      return json(res, 400, { ok: false, error: 'must provide community_id or node_id' });
    }

    addToPushQueue({
      title,
      body: messageBody,
      data: pushData,
      community_id: targetCommunityId,
      node_id: targetNodeId,
      sent_by: node_id
    });

    return json(res, 200, { ok: true });
  }

  return json(res, 404, { ok: false, error: 'not found' });
});

const PORT = Number(process.env.PORT || 8787);
server.listen(PORT, () => {
  console.log(`[claw-relay] listening on http://127.0.0.1:${PORT}`);
});

// WebSocket Server for real-time alerts
const wss = new WebSocketServer({ server, path: '/v1/ws/alerts' });

wss.on('connection', (ws, req) => {
  const url = new URL(req.url || '/', `ws://localhost:${PORT}`);
  const token = url.searchParams.get('token');

  // Authenticate via query token
  const node_id = authenticateNode(token);
  if (!node_id) {
    ws.close(1008, 'invalid token');
    return;
  }

  // Get user's communities
  const communitiesDb = loadCommunities();
  const myCommunities = [];
  for (const [cid, com] of Object.entries(communitiesDb.communities || {})) {
    if (com?.members?.[node_id]) {
      myCommunities.push(cid);
    }
  }

  if (myCommunities.length === 0) {
    ws.close(1008, 'no communities found');
    return;
  }

  // Join all community rooms
  wsToCommunityId.set(ws, myCommunities);
  for (const community_id of myCommunities) {
    if (!wsCommunityRooms.has(community_id)) {
      wsCommunityRooms.set(community_id, new Set());
    }
    wsCommunityRooms.get(community_id).add(ws);
  }

  console.log(`[ws] node ${node_id} connected to ${myCommunities.length} community rooms`);

  // Send welcome message with subscribed communities
  ws.send(JSON.stringify({
    type: 'welcome',
    community_ids: myCommunities,
    ts: nowIso()
  }));

  // 30s keepalive ping/pong
  const pingInterval = setInterval(() => {
    if (ws.readyState === 1) { // OPEN
      ws.ping();
    } else {
      clearInterval(pingInterval);
    }
  }, 30000);

  ws.on('pong', () => {
    // Pong received, connection alive
  });

  ws.on('message', (data) => {
    // Echo back for now
    try {
      const msg = JSON.parse(data.toString());
      if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong', ts: nowIso() }));
      }
    } catch {
      // Ignore invalid messages
    }
  });

  ws.on('close', () => {
    clearInterval(pingInterval);
    const communities = wsToCommunityId.get(ws) || [];
    for (const community_id of communities) {
      const room = wsCommunityRooms.get(community_id);
      if (room) {
        room.delete(ws);
        if (room.size === 0) {
          wsCommunityRooms.delete(community_id);
        }
      }
    }
    console.log(`[ws] node ${node_id} disconnected`);
  });

  ws.on('error', (err) => {
    console.error(`[ws] error for node ${node_id}:`, err.message);
  });
});
