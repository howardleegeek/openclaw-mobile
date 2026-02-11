import http from 'node:http';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { readFileSync, existsSync, mkdirSync, writeFileSync, appendFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { latLngToCell } from 'h3-js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Local dev storage (no DB yet): JSON files + JSONL event log.
const DATA_DIR = join(__dirname, '..', 'data');
const NODES_PATH = join(DATA_DIR, 'nodes.json');
const EVENTS_PATH = join(DATA_DIR, 'events.jsonl');
const BLOBS_DIR = join(DATA_DIR, 'blobs');

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

  return json(res, 404, { ok: false, error: 'not found' });
});

const PORT = Number(process.env.PORT || 8787);
server.listen(PORT, () => {
  console.log(`[claw-relay] listening on http://127.0.0.1:${PORT}`);
});
