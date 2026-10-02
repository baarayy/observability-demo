// Sudden traffic spike: 5 rps -> 150 rps -> 5 rps.
// Watch: request rate, p95/p99 latency, DB pool usage, CPU, threads.
// Run: scripts/load.sh spike
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SKUS = ['KEYBOARD', 'MOUSE', 'MONITOR', 'LAPTOP', 'HEADSET'];

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 400,
      stages: [
        { target: 5, duration: '1m' },    // warm-up / baseline
        { target: 150, duration: '15s' }, // spike!
        { target: 150, duration: '2m' },  // hold
        { target: 5, duration: '15s' },   // drop
        { target: 5, duration: '1m' },    // recovery
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  const sku = SKUS[Math.floor(Math.random() * SKUS.length)];
  const res = http.post(`${BASE_URL}/api/orders`, JSON.stringify({ sku, quantity: 1 }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/orders' } });
  check(res, { 'order created': (r) => r.status === 201 });
}
