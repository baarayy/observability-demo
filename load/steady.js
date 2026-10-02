// Steady, realistic background traffic: mostly happy-path orders with a little noise.
// Run: scripts/load.sh steady [duration]   (default 10m)
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SKUS = ['KEYBOARD', 'MOUSE', 'MONITOR', 'LAPTOP', 'HEADSET'];

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 5), // requests per second
      timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  // Client-side SLOs: k6 marks the run as failed if these are violated.
  // The deliberate 4xx requests below declare their expected status, so only real failures count.
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<800'],
  },
};

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const json = { headers: { 'Content-Type': 'application/json' } };

export default function () {
  const r = Math.random();

  if (r < 0.55) {
    // happy path: place an order
    const res = http.post(`${BASE_URL}/api/orders`,
      JSON.stringify({ sku: pick(SKUS), quantity: 1 + Math.floor(Math.random() * 12) }),
      // 409 can legitimately happen when a SKU runs low between restocks
      { ...json, tags: { name: 'POST /api/orders' }, responseCallback: http.expectedStatuses(201, 409) });
    check(res, { 'order created': (x) => x.status === 201 });
  } else if (r < 0.80) {
    http.get(`${BASE_URL}/api/orders`, { tags: { name: 'GET /api/orders' } });
  } else if (r < 0.92) {
    const id = 1 + Math.floor(Math.random() * 200);
    // ids are random, so 404 is a normal answer here
    http.get(`${BASE_URL}/api/orders/${id}`,
      { tags: { name: 'GET /api/orders/{id}' }, responseCallback: http.expectedStatuses(200, 404) });
  } else if (r < 0.96) {
    // client error: unknown product -> 404
    http.post(`${BASE_URL}/api/orders`, JSON.stringify({ sku: 'UNICORN', quantity: 1 }),
      { ...json, tags: { name: 'POST /api/orders (bad sku)' }, responseCallback: http.expectedStatuses(404) });
  } else if (r < 0.98) {
    // business error: way more than in stock -> 409
    http.post(`${BASE_URL}/api/orders`, JSON.stringify({ sku: 'LAPTOP', quantity: 5000 }),
      { ...json, tags: { name: 'POST /api/orders (too many)' }, responseCallback: http.expectedStatuses(409) });
  } else {
    // noisy endpoint with long-tail latency and ~10% errors
    http.get(`${BASE_URL}/api/chaos/random`, { tags: { name: 'GET /api/chaos/random' } });
  }
  sleep(0.1);
}
