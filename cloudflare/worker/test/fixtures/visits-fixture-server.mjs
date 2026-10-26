// Local fake API + static server for browser-testing public/index.html. Synthetic data only.
// Old seeded rows (ids 10, 11) have no photo_data field, like pre-migration API rows.
// Usage: node test/fixtures/visits-fixture-server.mjs [port]   (default 8799)
// Logins: employee "visitonly" (only canAccessVisits), "noperm" (no permissions), "boss" (COMPANY). Any password.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const html = path.resolve(here, "../../public/index.html");
const port = Number(process.argv[2] || 8799);
const customers = [
  { customer_id: "1", first_name: "Alice", last_name: "Alpha", address: "1 Test St", city: "Faketown", state: "ZZ", zip: "00000", phone: "", email: "", service_day: "Monday", amount_charged: 0, notes: "", starting_date: "", status: "Active", route_order: 1 },
  { customer_id: "2", first_name: "Bob", last_name: "Beta", address: "2 Test St", city: "Faketown", state: "ZZ", zip: "00000", phone: "", email: "", service_day: "Tuesday", amount_charged: 0, notes: "", starting_date: "", status: "Active", route_order: 1 },
  { customer_id: "3", first_name: "Alicia", last_name: "Gamma", address: "3 Test St", city: "Faketown", state: "ZZ", zip: "00000", phone: "", email: "", service_day: "Tuesday", amount_charged: 0, notes: "", starting_date: "", status: "Active", route_order: 2 },
];
const visits = {
  1: [
    { visit_id: 10, customer_id: 1, visited_at: "2024-05-01T14:30:00.000Z", ph: "7.2", chlo: "2-4", stb: "70", alk: 90, cal: 300, notes: "older" },
    { visit_id: 11, customer_id: 1, visited_at: "2024-06-15T18:05:00.000Z", ph: "7.8", chlo: "7.5-15", stb: "40", alk: 100, cal: 400, notes: "newer" },
  ],
  2: [{ visit_id: 20, customer_id: 2, visited_at: "2024-06-01T12:00:00.000Z", ph: "9.9", chlo: "3-6", stb: "50", alk: 80, cal: 300, notes: "corrupt" }],
  3: [],
};
let nextId = 100;
const log = [];
const accounts = {
  visitonly: { role: "EMPLOYEE", username: "visitonly", companyId: 1, companyName: "Fixture Co", permissions: { canAccessVisits: true } },
  noperm: { role: "EMPLOYEE", username: "noperm", companyId: 1, companyName: "Fixture Co", permissions: { canViewCustomers: true } },
  boss: { role: "COMPANY", username: "boss", companyId: 1, companyName: "Fixture Co", permissions: {} },
};
const send = (res, code, body) => { res.writeHead(code, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
const readBody = (req) => new Promise((r) => { let s = ""; req.on("data", (c) => (s += c)); req.on("end", () => r(s ? JSON.parse(s) : {})); });
const PH = ["7.0", "7.2", "7.4", "7.6", "7.8", "8.0"], CH = ["1-2", "2-4", "3-6", "5-10", "7.5-15", "10-20"], ST = ["100", "90", "80", "70", "60", "50", "40", "30"];
const posInt = (v) => Number.isInteger(v) && v > 0 && v <= 2147483647;
const optInt = (v) => v === null || v === undefined || posInt(v);
const photoOk = (v) => v === undefined || v === null || v === "" || (typeof v === "string" && v.length <= 700000 && /^data:image\/(png|jpeg);base64,[A-Za-z0-9+\/]+={0,2}$/.test(v));
const withPhoto = (r) => ({ ...r, alk: r.alk ?? null, cal: r.cal ?? null, photo_data: r.photo_data || "" });

http.createServer(async (req, res) => {
  const u = new URL(req.url, "http://x");
  if (u.pathname === "/" || u.pathname === "/index.html") { res.writeHead(200, { "Content-Type": "text/html" }); return res.end(fs.readFileSync(html)); }
  if (u.pathname === "/__log") return send(res, 200, log);
  if (u.pathname === "/__reset") { log.length = 0; return send(res, 200, {}); }
  const tok = (req.headers.authorization || "").replace("Bearer ", "");
  const acct = accounts[tok];
  if (u.pathname === "/v1/auth/login") {
    const b = await readBody(req); const a = accounts[b.username];
    return a ? send(res, 200, { token: b.username, account: a }) : send(res, 401, { error: "Bad login" });
  }
  if (!acct) return send(res, 401, { error: "Unauthorized" });
  const ok = acct.role !== "EMPLOYEE" || acct.permissions.canAccessVisits;
  const m = u.pathname.match(/^\/v1\/customers(?:\/(\d+))?(?:\/visits(?:\/(\d+))?)?$/);
  if (!m) return send(res, 404, { error: "Not found" });
  const [, cid, vid] = m;
  const isVisits = u.pathname.includes("/visits");
  log.push(req.method + " " + u.pathname);
  if (!ok && !(acct.permissions.canViewCustomers && !isVisits)) return send(res, 403, { error: "Forbidden" });
  if (!cid) return send(res, 200, customers);
  const c = customers.find((x) => x.customer_id === cid);
  if (!c) return send(res, 404, { error: "Customer not found" });
  if (!isVisits) return send(res, 200, c);
  if (!ok) return send(res, 403, { error: "Forbidden" });
  if (req.method === "GET") return send(res, 200, visits[cid].map(withPhoto));
  if (req.method === "DELETE") {
    const index = visits[cid].findIndex(v => String(v.visit_id) === vid);
    if (index < 0) return send(res, 404, { error: "Visit not found" });
    visits[cid].splice(index, 1);
    return send(res, 200, { deleted: true });
  }
  const b = await readBody(req);
  if (!PH.includes(b.ph) || !CH.includes(b.chlo) || !ST.includes(b.stb) || !optInt(b.alk) || !optInt(b.cal) || !photoOk(b.photo_data) || typeof b.notes !== "string" || b.notes.length > 16000) return send(res, 400, { error: "Invalid visit" });
  await new Promise((r) => setTimeout(r, 300));
  if (req.method === "POST") {
    const row = { visit_id: nextId++, customer_id: Number(cid), visited_at: new Date().toISOString(), ph: b.ph, chlo: b.chlo, stb: b.stb, alk: b.alk ?? null, cal: b.cal ?? null, notes: b.notes, photo_data: b.photo_data || "" };
    visits[cid].push(row); return send(res, 200, withPhoto(row));
  }
  if (req.method === "PUT") {
    const row = visits[cid].find((v) => String(v.visit_id) === vid);
    if (!row) return send(res, 404, { error: "Visit not found" });
    Object.assign(row, { ph: b.ph, chlo: b.chlo, stb: b.stb, alk: b.alk ?? null, cal: b.cal ?? null, notes: b.notes, photo_data: b.photo_data || "" });
    return send(res, 200, withPhoto(row));
  }
  send(res, 405, { error: "Method" });
}).listen(port, "127.0.0.1", () => console.log("fixture on http://127.0.0.1:" + port));
