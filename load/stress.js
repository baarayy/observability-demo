// Stress test: keep adding concurrent users until the system breaks.
// Goal: find the saturation point. Which resource runs out first? (DB pool? CPU? Tomcat threads?)
// Run: scripts/load.sh stress
import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SKUS = ['KEYBOARD', 'MOUSE', 'MONITOR', 'LAPTOP', 'HEADSET'];

export const options = {
  stages: [
    { target: 20, duration: '1m' },
    { target: 50, duration: '1m' },
    { target: 100, duration: '1m' },
    { target: 200, duration: '1m' },
    { target: 300, duration: '1m' },
    { target: 0, duration: '30s' },
  ],
};

export default function () {
  const sku = SKUS[Math.floor(Math.random() * SKUS.length)];
  http.post(`${BASE_URL}/api/orders`, JSON.stringify({ sku, quantity: 1 }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'POST /api/orders' } });
  http.get(`${BASE_URL}/api/orders`, { tags: { name: 'GET /api/orders' } });
  sleep(0.2);
}
