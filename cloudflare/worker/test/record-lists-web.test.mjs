import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

function fixture() {
  const html = readFileSync(new URL("../public/index.html", import.meta.url), "utf8");
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      innerHTML: "", value: "", classList: { toggle() {}, remove() {} },
      style: {}, querySelectorAll: () => [], querySelector: () => null,
      addEventListener() {}, setAttribute() {},
    });
    return elements.get(id);
  };
  const c = vm.createContext({
    localStorage: { getItem: () => null },
    document: { getElementById: element, addEventListener() {} },
    window: { addEventListener() {}, scrollTo() {} }, console, clearInterval() {}, clearTimeout() {},
  });
  vm.runInContext(html.match(/<script>([\s\S]*)<\/script>/)[1], c);
  c.S.account = { role: "COMPANY" };
  return { c, element, html };
}

test("shared record tables have labeled scroll regions, headers and escaped content", () => {
  const { c, html } = fixture();
  const table = c.recordTable(["<Date>", "Amount"], "<tr><td>safe</td><td>10</td></tr>", 'Records "test"');
  assert.match(table, /role="region".*tabindex="0"/);
  assert.match(table, /<th scope="col">&lt;Date&gt;<\/th>/);
  assert.match(table, /aria-label="Records &quot;test&quot;"/);
  assert.match(html, /\.record-scroll\{overflow:auto;max-width:100%/);
  assert.match(html, /\.record-table tbody tr:nth-child\(even\)/);
  assert.match(html, /font-variant-numeric:tabular-nums/);
});

test("customer tables preserve separate fields, route arrows, safe map links and actions", () => {
  const { c, element } = fixture();
  const customers = [{ customer_id: "1", first_name: "<Demo>", last_name: "Avery",
    address: "123 Fiction St", service_day: "Monday", amount_charged: 150, route_order: 2 }];
  const table = c.customerTable(customers, () => '<button data-cu="1">Details</button>', true);
  assert.match(table, /<tr data-id="1">/);
  assert.match(table, /data-mv="-1"/);
  assert.match(table, /data-mv="1"/);
  assert.match(table, /Avery, &lt;Demo&gt;/);
  assert.match(table, /<td>Monday<\/td>/);
  assert.match(table, /\$150\.00/);
  assert.match(table, /data-cu="1"/);
  assert.doesNotMatch(c.customerTable(customers, () => "", false), /data-mv/);
  c.api = async () => [];
  c.S.arg = null;
  return c.vCustomers().then(() => {
    element("df").value = "Monday";
    assert.doesNotThrow(() => element("df").onchange());
    assert.match(element("lst").innerHTML, /No customers/);
  });
});

test("statement table preserves newest-first order, running balances and read-only permissions", async () => {
  const { c, element } = fixture();
  c.S.arg = "1";
  c.loadKw = async () => {};
  c.api = async path => path.includes("/statements")
    ? [{ record_id: 1, record_date: "10/01/2026", type: "Service", amount: 100 },
      { record_id: 2, record_date: "10/02/2026", type: "Zelle Payment", amount: -30 }]
    : path === "/v1/record-types" ? ["Service"] : { customer_id: "1", first_name: "Demo", last_name: "Avery" };
  await c.vStatement();
  const rendered = element("app").innerHTML;
  assert.match(rendered, /class="record-table"/);
  assert.ok(rendered.indexOf("10/02/2026") < rendered.indexOf("10/01/2026"));
  assert.match(rendered, /-\$30\.00/);
  assert.match(rendered, /\$70\.00/);
  assert.match(rendered, /data-del="2"/);
  c.S.account = { role: "EMPLOYEE", permissions: { canViewStatements: true } };
  await c.vStatement();
  assert.doesNotMatch(element("app").innerHTML, /data-del=/);
});
