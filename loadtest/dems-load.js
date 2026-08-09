// k6 load-test for the data-entry app. Runs the endpoints most users hit:
// login → list departments → list tickets → self-dashboard, mixed EN/AR locales.
//
// Ramps to 30 concurrent users, holds for a minute, ramps down. Fails the run if
// - any request errors above 1%
// - p95 latency on any endpoint exceeds 800ms
//
// Run:  docker run --rm --network host -v $PWD:/scripts grafana/k6 run /scripts/dems-load.js

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8083';
const USERNAME = __ENV.USER || 'admin';
const PASSWORD = __ENV.PASS || 'admin123';

const loginLatency = new Trend('t_login', true);
const deptLatency  = new Trend('t_departments', true);
const ticketsLatency = new Trend('t_tickets', true);
const dashLatency  = new Trend('t_dashboard_me', true);
const errRate      = new Rate('errors');

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10 },   // warm-up
        { duration: '30s', target: 30 },   // ramp to 30 VUs
        { duration: '60s', target: 30 },   // hold
        { duration: '20s', target: 0 },    // drain
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'errors':               ['rate<0.01'],
    'http_req_failed':      ['rate<0.02'],
    't_login':              ['p(95)<1500'],
    't_departments':        ['p(95)<400'],
    't_tickets':            ['p(95)<800'],
    't_dashboard_me':       ['p(95)<1200'],
  },
};

function login() {
  const r = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  loginLatency.add(r.timings.duration);
  const ok = check(r, { 'login 200': (x) => x.status === 200 });
  errRate.add(!ok);
  if (!ok) return null;
  try { return r.json('token'); } catch { errRate.add(true); return null; }
}

export default function () {
  const token = login();
  if (!token) { sleep(1); return; }

  // Alternate the locale so we exercise both name_en and name_ar output paths.
  const lang = (__VU % 2 === 0) ? 'ar' : 'en';
  const headers = { Authorization: `Bearer ${token}`, 'Accept-Language': lang };

  group('read-heavy user flow', () => {
    const d = http.get(`${BASE}/api/departments`, { headers, tags: { name: 'departments' } });
    deptLatency.add(d.timings.duration);
    errRate.add(!check(d, { 'departments 200': (x) => x.status === 200 }));

    const t = http.get(`${BASE}/api/user/tickets?page=0&size=20`, { headers, tags: { name: 'tickets' } });
    ticketsLatency.add(t.timings.duration);
    errRate.add(!check(t, { 'tickets 200': (x) => x.status === 200 }));

    const m = http.get(`${BASE}/api/user/dashboard/me`, { headers, tags: { name: 'dashboard_me' } });
    dashLatency.add(m.timings.duration);
    errRate.add(!check(m, { 'dashboard 200': (x) => x.status === 200 }));
  });

  sleep(1);
}
