import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { webcrypto } from "node:crypto";

function fixture() {
  const storage = new Map();
  const elements = new Map();
  const el = id => {
    if (!elements.has(id)) elements.set(id, {
      innerHTML: "", textContent: "", value: "", checked: false, disabled: false,
      classList: { toggle() {}, remove() {} }, style: {}, listeners: {},
      querySelectorAll: () => [], querySelector: () => null,
      addEventListener(name, fn) { this.listeners[name] = fn; },
      setAttribute() {},
    });
    return elements.get(id);
  };
  const context = vm.createContext({
    localStorage: { getItem: k => storage.get(k) || null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }, crypto: webcrypto,
    sessionStorage: { getItem: k => storage.get(k) || null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    document: { getElementById: el, addEventListener() {} },
    window: { addEventListener() {}, scrollTo() {} }, console, AbortController,
    clearInterval, clearTimeout, setTimeout, confirm: () => true,
  });
  const html = readFileSync(new URL("../public/index.html", import.meta.url), "utf8");
  vm.runInContext(html.match(/<script>([\s\S]*)<\/script>/)[1], context);
  context.S.account = { role: "COMPANY", companyName: "Synthetic" };
  context.S.nav = 1;
  return { c: context, el };
}

test("obsolete reads abort, stale errors cannot replace new views, writes are never cancelled", async () => {
  const { c, el } = fixture();
  let readSignal, rejectRead, finishWrite, writeSignal;
  c.fetch = (path, opts) => path === "/read"
    ? new Promise((resolve, reject) => { readSignal = opts.signal; rejectRead = reject; })
    : new Promise(resolve => { writeSignal = opts.signal; finishWrite = resolve; });
  const read = c.api("/read").catch(e => e);
  const write = c.api("/write", { method: "POST", body: {} }).catch(e => e);
  c.cancelReads(); c.S.nav++;
  assert.equal(readSignal.aborted, true);
  assert.equal(writeSignal, undefined);
  rejectRead(new Error("Late read failure"));
  finishWrite({ ok: true, json: async () => ({ added: 1 }) });
  for (const pending of [read, write]) assert.equal((await pending).message, "Obsolete view");
  el("flash").innerHTML = "new view";
  c.flash("err", "Obsolete view");
  assert.equal(el("flash").innerHTML, "new view");
  c.vCustomers = () => new Promise((resolve, reject) => { rejectRead = reject; });
  c.go("customers");
  await Promise.resolve();
  c.go("backup");
  await Promise.resolve();
  const current = el("app").innerHTML;
  rejectRead(new Error("Old screen failed"));
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(el("app").innerHTML, current);
});

test("monthly batch blocks double-click, persists uncertain UUID across reopen and permits explicit new billing", async () => {
  const { c, el } = fixture();
  c.loadKw = async () => {};
  c.isCredit = () => true;
  const requests = [];
  let rejectRequest;
  c.api = async (path, opts) => {
    if (path === "/v1/record-types") return ["Service"];
    assert.equal(path, "/v1/statements/monthly");
    requests.push(JSON.parse(JSON.stringify(opts.body)));
    if (requests.length === 1) return new Promise((resolve, reject) => { rejectRequest = reject; });
    return { added: 64, request_id: opts.body.request_id };
  };
  await c.vMonthly();
  el("mt").value = "Service";
  const first = el("go").onclick();
  await el("go").onclick();
  assert.equal(requests.length, 1);
  assert.equal(el("go").disabled, true);
  rejectRequest(new Error("Connection lost"));
  await first;
  await c.vMonthly();
  el("mt").value = "Service";
  await el("go").onclick();
  assert.equal(requests.length, 2);
  assert.deepEqual(requests[0], requests[1]);
  assert.match(requests[0].request_id, /^[0-9a-f-]{36}$/);
  assert.equal(el("go").textContent, "Apply again (new request)");
  await el("go").onclick();
  assert.equal(requests.length, 3);
  assert.notEqual(requests[2].request_id, requests[0].request_id);
  el("md").value = "11/30/2026"; el("md").listeners.input();
  await el("go").onclick();
  assert.notEqual(requests[3].request_id, requests[0].request_id);
  assert.equal(requests[3].date, "11/30/2026");
});

test("charged summary uses scoped bulk balances and never silently substitutes zero", async () => {
  const { c, el } = fixture();
  const paths = [];
  c.api = async path => {
    paths.push(path);
    if (path.startsWith("/v1/customers?")) return { rows: [{ customer_id: "1", first_name: "Demo", last_name: "Avery" }], total: 1 };
    return { rows: [{ customer_id: "1", balance: "123.45" }] };
  };
  await c.vCharged();
  assert.equal(paths.length, 2);
  assert.ok(paths.includes("/v1/balances"));
  assert.match(el("app").innerHTML, /\$123\.45/);
  c.api = async path => path.startsWith("/v1/customers?") ? { rows: [{ customer_id: "1" }], total: 1 } : { rows: [] };
  await assert.rejects(c.vCharged(), /Balance unavailable/);
});

test("statement pages retain server balances and printing fetches ALL rows plus latest settings", async () => {
  const { c, el } = fixture();
  c.S.arg = { id: "1", offset: 50 };
  c.S.kw = { settings: { companyName: "Old" } };
  c.loadKw = async () => c.S.kw;
  const paths = [];
  const all = Array.from({ length: 75 }, (_, i) => ({ record_id: i + 1, record_date: "10/01/2026", amount: 2, type: "Service" }));
  c.api = async path => {
    paths.push(path);
    if (path.includes("/statements?")) return { rows: [{ ...all[0], run: 22 }], total: 75, balance: 150 };
    if (path.endsWith("/statements")) return all;
    if (path === "/v1/settings") return { companyName: "Latest" };
    if (path === "/v1/record-types") return [];
    return { customer_id: "1", first_name: "Demo" };
  };
  let printed;
  c.printStatement = (customer, rows, balance) => { printed = { rows, balance }; };
  await c.vStatement();
  assert.match(el("app").innerHTML, /\$150\.00/);
  assert.match(el("app").innerHTML, /\$22\.00/);
  assert.equal(paths.filter(p => p === "/v1/settings").length, 0);
  assert.ok(paths.some(p => p.endsWith("limit=50&offset=50")));
  await el("pr").onclick();
  assert.equal(printed.rows.length, 75);
  assert.equal(printed.balance, 150);
  assert.equal(c.S.kw.settings.companyName, "Latest");
});

test("PDF settings save as one atomic batch", async () => {
  const { c, el } = fixture();
  c.S.kw = { settings: {} };
  c.loadKw = async () => c.S.kw;
  const writes = [];
  c.api = async (path, opts) => { writes.push({ path, opts }); return {}; };
  await c.vPdf();
  el("s_companyName").value = "Changed";
  el("s_gen").checked = true;
  await el("sv").onclick();
  assert.equal(writes.length, 1);
  assert.equal(writes[0].path, "/v1/settings/batch");
  assert.equal(writes[0].opts.body.settings.companyName, "Changed");
  assert.equal(writes[0].opts.body.settings.showGenerationDate, "true");
});
