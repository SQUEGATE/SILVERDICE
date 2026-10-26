// Run from worker: node --test test/quotes-web.test.js (no packages).
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import { createQuoteFixture } from "./fixtures/quotes-fixture-server.mjs";

const html = fs.readFileSync(new URL("../public/index.html", import.meta.url), "utf8");
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];
function context() {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, { innerHTML: "", value: "", classList: { toggle() {}, remove() {} }, querySelectorAll: () => [], addEventListener() {} });
    return elements.get(id);
  };
  const ctx = vm.createContext({
    localStorage: { getItem: () => null }, document: { getElementById: element, addEventListener() {}, title: "Original" },
    window: { addEventListener() {}, removeEventListener() {}, print() {}, scrollTo() {} },
    setTimeout() {}, clearInterval() {}, console,
  });
  vm.runInContext(script, ctx);
  vm.runInContext('S.account={role:"EMPLOYEE",companyName:"Fixture",permissions:{canAccessSalesQuote:true}};S.kw={credit:["payment","discount"],settings:{}};', ctx);
  return ctx;
}
const good = () => ({ quote_date: "10/26/2026", notes: "", terms: "Fixed terms", lines: [{ date: "10/26/2026", type: "Service", description: "Motor", amount: "120.00" }] });
test("actual inline script compiles; quote validation preserves fields and derives signs from shared classification", () => {
  const c = context(), q = good();
  q.lines.push({ ...q.lines[0], type: "Payment", amount: "-20" });
  const body = c.quoteValidate(q);
  assert.equal(body.lines[0].amount, -120); assert.equal(body.lines[1].amount, 20);
  assert.equal(body.terms, "Fixed terms");
  assert.equal(body.quote_number, undefined);
  assert.equal(c.quoteValidate({ ...good(), lines: [{ ...q.lines[0], amount: "-5" }] }).lines[0].amount, -5);
});
test("strict quote dates, monetary bounds, limits, empty types", () => {
  const c = context();
  for (const amount of ["", "0", "0.00", "1.001", "1e3", "Infinity", "1000000000", "abc"])
    assert.throws(() => c.quoteValidate({ ...good(), lines: [{ ...good().lines[0], amount }] }), /amount/);
  for (const amount of ["0.01", "999999999.99", "-999999999.99"])
    assert.ok(c.quoteValidate({ ...good(), lines: [{ ...good().lines[0], amount }] }));
  assert.throws(() => c.quoteValidate({ ...good(), quote_date: "02/30/2026" }), /Quote date/);
  assert.throws(() => c.quoteValidate({ ...good(), lines: [] }), /1 and 200/);
  assert.throws(() => c.quoteValidate({ ...good(), lines: Array(201).fill(good().lines[0]) }), /1 and 200/);
  assert.ok(c.quoteValidate({ ...good(), lines: Array(200).fill(good().lines[0]), notes: "x".repeat(16000) }));
  assert.throws(() => c.quoteValidate({ ...good(), notes: "x".repeat(16001) }), /16000/);
  assert.throws(() => c.quoteValidate({ ...good(), terms: "x".repeat(16001) }), /16000/);
  assert.throws(() => c.quoteValidate({ ...good(), lines: [{ ...good().lines[0], type: "" }] }), /type/);
  assert.throws(() => c.quoteValidate({ ...good(), lines: [{ ...good().lines[0], date: "13/01/2026" }] }), /Line 1/);
});
test("company keyword configuration controls signs, not hardcoded type names", () => {
  const c = context();
  c.S.kw.credit = ["service"];
  assert.equal(c.quoteValidate(good()).lines[0].amount, 120);
  assert.equal(c.quoteValidate({ ...good(), lines: [{ ...good().lines[0], type: "Payment" }] }).lines[0].amount, -120);
});
test("PDF escapes customer, notes, terms, description; actual settings; negative payment; title", async () => {
  const c = context();
  const q = { ...good(), quote_number: "SQ-000001", notes: "<script>bad</script>", terms: "Hello <Alice>\n\nAdvance payment", lines: [
    { date: "10/26/2026", type: "Service", description: "Motor <test>", amount: 120 },
    { date: "10/26/2026", type: "Payment", description: "Deposit", amount: -20 },
  ] };
  c.printQuote({ first_name: "Alice", last_name: "Alpha", address: "1 <Street>" }, q,
    { companyName: "Sample & Co", companyAddress: "1 Main|City", companyPhone: "5555550100", paymentInfo: "Zelle|Check" });
  await Promise.resolve();
  const pdf = c.document.getElementById("printarea").innerHTML;
  for (const s of ["SALES QUOTATION", "SQ-000001", "Sample &amp; Co", "Motor &lt;test&gt;", "&lt;script&gt;bad", "$100.00", "-$20.00", "Forms of Payment", "Zelle<br>Check", "table-header-group", "break-inside:avoid"])
    assert.ok(pdf.includes(s), s);
  assert.equal(c.document.title, "ALPHA_ALICE_SALES_QUOTATION_SQ-000001");
});
test("quote permission is opt-in; guards block direct view and never fetch", async () => {
  const c = context(); let calls = 0;
  c.api = async () => { calls++; };
  c.S.account.permissions = {};
  c.go("quote", { id: "1" }); assert.equal(calls, 0);
  assert.match(c.app.innerHTML, /not permitted/);
  await assert.rejects(c.vQuotes(), /not permitted/);
  await assert.rejects(c.vQuote(), /not permitted/);
  assert.ok(c.PERMS.some(p => p[0] === "canAccessSalesQuote"));
  assert.equal(c.VIEWS.find(v => v.id === "quotes").perm(), false);
  c.S.arg = {}; c.vEmployee();
  assert.match(c.app.innerHTML, /data-perm="canAccessSalesQuote"><span>Access Sales Quote/);
});
test("200-line print retains maximum notes/terms, table headers, total and payment footer without fixed positioning", () => {
  const c = context(), q = { ...good(), quote_number: "SQ-000999", notes: "N".repeat(16000), terms: "T".repeat(16000),
    lines: Array.from({ length: 200 }, (_, i) => ({ date: "10/26/2026", type: "Service", description: "LONG ROW " + i, amount: -1.01 })) };
  c.printQuote({ first_name: "Synthetic", last_name: "Only" }, q, { paymentInfo: "TEST ONLY", showLogo: "false", logoData: "data:image/png;base64,AAAA" });
  const pdf = c.document.getElementById("printarea").innerHTML;
  assert.ok(pdf.includes(q.notes)); assert.ok(pdf.includes(q.terms)); assert.ok(pdf.includes("LONG ROW 199"));
  assert.equal((pdf.match(/<tr><td>/g) || []).length, 200);
  assert.ok(pdf.includes("-$202.00")); assert.ok(pdf.includes("TEST ONLY"));
  assert.ok(pdf.includes("table-header-group")); assert.ok(pdf.includes("orphans:3;widows:3"));
  assert.ok(!pdf.includes("position:fixed")); assert.ok(!pdf.includes("<img"));
});
test("synthetic API contracts: multiple saves, edit/delete, explicit once-only conversion, permissions", async t => {
  const server = createQuoteFixture(); await new Promise(r => server.listen(0, "127.0.0.1", r));
  t.after(() => new Promise(r => server.close(r)));
  const base = "http://127.0.0.1:" + server.address().port;
  async function req(path, method = "GET", body, token = "quoteonly") {
    const r = await fetch(base + path, { method, headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
    return { status: r.status, body: await r.json() };
  }
  const p = "/v1/customers/1/quotes", body = context().quoteValidate(good());
  assert.equal((await req(p, "GET", undefined, "noperm")).status, 403);
  const first = (await req(p, "POST", body)).body, second = (await req(p, "POST", body)).body;
  assert.equal(first.quote_number, "SQ-000001"); assert.equal(second.quote_number, "SQ-000002");
  assert.equal((await req(p)).body.length, 2);
  assert.equal((await req("/__state")).body.statements.length, 0);
  assert.equal((await req(p + "/1", "PUT", { ...body, notes: "Edited" })).body.notes, "Edited");
  assert.equal((await req(p + "/2", "DELETE")).body.deleted, true);
  assert.equal((await req(p + "/1/convert", "POST")).status, 403);
  assert.ok((await req(p + "/1/convert", "POST", undefined, "editor")).body.converted_at);
  for (const [path, method] of [[p + "/1/convert", "POST"], [p + "/1", "PUT"], [p + "/1", "DELETE"]])
    assert.equal((await req(path, method, body, "editor")).status, 409);
  assert.equal((await req("/__state")).body.statements.length, 1);
  assert.equal((await req(p)).body[0].notes, "Edited");
});
