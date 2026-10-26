import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";

const schema = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
const env = {
  SESSION_SIGNING_SECRET: "quotes-integration-signing-secret",
  SILVERDICE_URL: "https://quotes-sqlite.invalid",
  SILVERDICE_AUTH_TOKEN: "quotes-integration-transport-token"
};
const password = "synthetic-password";
const salt = Buffer.from("synthetic-quote-salt");
const material = await crypto.subtle.importKey("raw", Buffer.from(password), "PBKDF2", false, ["deriveBits"]);
const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: 10000, hash: "SHA-256" }, material, 256);
const hash = `pbkdf2-sha256$10000$${salt.toString("base64url")}$${Buffer.from(bits).toString("base64url")}`;
const valid = {
  quote_date: "10/26/2026", notes: "Independent quote", terms: "Due on acceptance",
  lines: [
    { date: "10/27/2026", type: "Charge", description: "Replacement pump", amount: -123.45 },
    { date: "10/28/2026", type: "Credit", description: "", amount: 0.29 },
    { date: "02/29/2028", type: "Credit", description: "Credit", amount: 10 }
  ]
};
const path = "/v1/customers/shared/quotes";
let fixtureId = 0;

function encode(value) {
  return value === null ? { type: "null" }
    : typeof value === "number" ? { type: Number.isInteger(value) ? "integer" : "float", value: String(value) }
      : { type: "text", value };
}

async function fixture(run, { legacy = false } = {}) {
  const db = new DatabaseSync(":memory:");
  const originalFetch = globalThis.fetch;
  db.exec("PRAGMA foreign_keys = ON");
  db.exec(schema);
  if (legacy) db.exec("DROP TABLE sales_quotes; DROP TABLE sales_quote_sequences");
  db.exec(`
    INSERT INTO companies (company_id, company_name, username) VALUES (1, 'One', 'one'), (2, 'Two', 'two');
    INSERT INTO customers (company_id, customer_id, first_name, last_name, service_day) VALUES
      (1, 'shared', 'Assigned', 'One', 'Monday'), (1, 'other', 'Unassigned', 'One', 'Tuesday'),
      (1, 'day', 'DayAssigned', 'One', 'Wednesday'), (2, 'shared', 'Assigned', 'Two', 'Monday');
    INSERT INTO statement_records VALUES (50, 1, 'shared', '10/01/2026', 'Existing', -7);
    INSERT INTO record_types VALUES (1, 'Charge'), (1, 'Credit');
    INSERT INTO pdf_settings VALUES (1, 'companyName', 'Synthetic One');
  `);
  const insert = db.prepare("INSERT INTO accounts (account_id, username, password_hash, role, company_id, employee_id, permissions_json, allowed_customer_ids_json, allowed_days_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
  const accounts = [
    ["one", "COMPANY", 1, {}], ["two", "COMPANY", 2, {}],
    ["quotes", "EMPLOYEE", 1, { canAccessSalesQuote: true }],
    ["editor", "EMPLOYEE", 1, { canAccessSalesQuote: true, canEditStatements: true }],
    ["denied", "EMPLOYEE", 1, {}], ["statement-editor", "EMPLOYEE", 1, { canEditStatements: true }],
    ["master", "MASTER", null, {}]
  ];
  accounts.forEach(([name, role, company, permissions], index) =>
    insert.run(index + 1, name, hash, role, company, index + 1, JSON.stringify(permissions),
      role === "EMPLOYEE" ? '["shared"]' : "[]", role === "EMPLOYEE" ? '["Wednesday"]' : "[]"));
  let failConversion = false;
  function execute(stmt) {
    if (failConversion && stmt.sql.startsWith("UPDATE sales_quotes SET converted_at")) {
      failConversion = false;
      throw new Error("Synthetic conversion failure");
    }
    const prepared = db.prepare(stmt.sql);
    const args = (stmt.args || []).map(arg => arg.type === "null" ? null
      : ["integer", "float"].includes(arg.type) ? Number(arg.value) : arg.value);
    const rows = prepared.all(...args);
    const cols = prepared.columns().map(column => ({ name: column.name }));
    return { cols, rows: rows.map(row => cols.map(column => encode(row[column.name]))) };
  }
  // Mock only the Turso wire protocol; all SQL, auth and handlers are real.
  globalThis.fetch = async (url, options) => {
    assert.equal(url, `${env.SILVERDICE_URL}/v2/pipeline`);
    const { requests } = JSON.parse(options.body);
    return Response.json({ results: requests.map(request => {
      if (request.type === "close") return { type: "ok", response: { type: "close" } };
      if (request.type === "execute") {
        try { return { type: "ok", response: { result: execute(request.stmt) } }; }
        catch (error) { return { type: "error", error: { message: error.message } }; }
      }
      assert.equal(request.type, "batch");
      const step_results = [], step_errors = [];
      const satisfied = condition => !condition || (condition.type === "ok"
        ? step_results[condition.step] != null : !satisfied(condition.cond));
      request.batch.steps.forEach((step, index) => {
        step_results[index] = step_errors[index] = null;
        if (!satisfied(step.condition)) return;
        try { step_results[index] = execute(step.stmt); }
        catch (error) { step_errors[index] = { message: error.message }; }
      });
      return { type: "ok", response: { result: { step_results, step_errors } } };
    }) });
  };
  try {
    const { default: worker } = await import(`../src/index.js?quotes-fixture=${++fixtureId}`);
    const call = async (target, { method = "GET", token, body } = {}) => {
      const response = await worker.fetch(new Request(`https://worker.test${target}`, {
        method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      }), env);
      return { status: response.status, body: await response.json() };
    };
    const login = async username => {
      const response = await call("/v1/auth/login", { method: "POST", body: { username, password } });
      assert.equal(response.status, 200);
      return response.body.token;
    };
    await run({ db, call, login, failConversion: () => { failConversion = true; } });
  } finally { globalThis.fetch = originalFetch; db.close(); }
}

test("quotes SQLite: CRUD, full rows, isolation, durable company numbering and legacy migration", async () => {
  await fixture(async ({ db, call, login }) => {
    const one = await login("one"), two = await login("two"), employee = await login("quotes");
    assert.equal((await call(path)).status, 401);
    assert.equal((await call(path, { token: "invalid" })).status, 401);
    const first = await call(path, { method: "POST", token: employee, body: valid });
    assert.equal(first.status, 201);
    assert.deepEqual(first.body.lines, valid.lines);
    assert.equal(first.body.quote_number, "SQ-000001");
    assert.equal(first.body.converted_at, null);
    assert.equal(new Date(first.body.created_at).toISOString(), first.body.created_at);
    const target = `${path}/${first.body.quote_id}`;
    assert.deepEqual((await call(path, { token: one })).body, [first.body]);
    assert.deepEqual((await call(path, { token: two })).body, []);
    for (const method of ["PUT", "DELETE"]) {
      assert.equal((await call(target, { method, token: two, body: method === "PUT" ? valid : undefined })).status, 404);
      assert.equal((await call("/v1/customers/other/quotes/1", { method, token: employee, body: method === "PUT" ? valid : undefined })).status, 404);
    }
    const changed = { ...valid, notes: "Edited", lines: [{ ...valid.lines[0], type: "Removed historic type", description: "Freeform" }] };
    const updated = await call(target, { method: "PUT", token: employee, body: changed });
    assert.equal(updated.status, 200);
    assert.deepEqual(updated.body.lines, changed.lines);
    assert.equal(updated.body.created_at, first.body.created_at);
    assert.equal((await call(target, { method: "DELETE", token: employee })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records").get().n, 1);
    const next = await call("/v1/customers/day/quotes", { method: "POST", token: employee, body: valid });
    assert.equal(next.body.quote_number, "SQ-000002");
    const otherCompany = await call(path, { method: "POST", token: two, body: valid });
    assert.equal(otherCompany.body.quote_number, "SQ-000001");
    await call("/v1/customers/day", { method: "DELETE", token: one });
    const third = await call(path, { method: "POST", token: one, body: valid });
    assert.equal(third.body.quote_number, "SQ-000003");
    const concurrent = await Promise.all([1, 2].map(() => call(path, { method: "POST", token: one, body: valid })));
    assert.deepEqual(concurrent.map(response => response.body.quote_number).sort(), ["SQ-000004", "SQ-000005"]);
    for (const id of [3, 4, 5]) assert.equal((await call(`${path}/${id}`, { method: "DELETE", token: one })).status, 200);
    const emptyBackup = (await call("/v1/backup", { token: one })).body;
    assert.deepEqual(emptyBackup.sales_quotes, []);
    assert.equal(emptyBackup.sales_quote_sequences[0].next_id, 6);
    assert.equal((await call("/v1/backup/restore", { method: "POST", token: one, body: emptyBackup })).status, 200);
    assert.equal((await call(path, { method: "POST", token: one, body: valid })).body.quote_number, "SQ-000006");
  }, { legacy: true });
});

test("quotes SQLite: quote-only permissions expose dependencies but never statements or edits", async () => {
  await fixture(async ({ call, login }) => {
    const token = await login("quotes");
    assert.equal((await call("/v1/customers", { token })).body.length, 2);
    for (const target of ["/v1/customers/shared", "/v1/customers/day", "/v1/record-types", "/v1/settings"]) {
      assert.equal((await call(target, { token })).status, 200, target);
    }
    assert.equal((await call("/v1/customers/other", { token })).status, 404);
    for (const target of ["/v1/customers/shared/statements", "/v1/customers/shared/balance", "/v1/statement-types"]) {
      assert.equal((await call(target, { token })).status, 403, target);
    }
    for (const [target, method, body] of [
      ["/v1/settings", "PUT", { key: "foo", value: "bar" }],
      ["/v1/record-types", "POST", { type_name: "foo" }],
      ["/v1/record-types", "DELETE", { type_name: "Charge" }],
      ["/v1/customers/shared/statements", "POST", { record_date: "10/26/2026", type: "Charge", amount: 1 }]
    ]) assert.equal((await call(target, { token, method, body })).status, 403);
    for (const name of ["denied", "statement-editor", "master"]) {
      const denied = await login(name);
      assert.equal((await call(path, { token: denied })).status, 403);
      assert.equal((await call(path, { token: denied, method: "POST", body: valid })).status, 403);
    }
    const deniedLogin = await call("/v1/auth/login", { method: "POST", body: { username: "denied", password } });
    assert.equal(deniedLogin.body.account.permissions.canAccessSalesQuote, false);
    const master = await login("master");
    const preview = await call("/v1/admin/companies/1/session", { method: "POST", token: master });
    assert.equal((await call(path, { token: preview.body.token, method: "POST", body: valid })).status, 201);
  });
});

test("quotes SQLite: strict date, line, money and server-owned field validation", async () => {
  await fixture(async ({ db, call, login }) => {
    const token = await login("one");
    const bad = [
      { ...valid, quote_date: "02/29/2026" }, { ...valid, quote_date: "2026-10-26" },
      { ...valid, lines: [] }, { ...valid, lines: Array(201).fill(valid.lines[0]) },
      { ...valid, terms: null }, { ...valid, notes: "x".repeat(16001) },
      { ...valid, converted_at: "2026-10-26T00:00:00.000Z" }, { ...valid, quote_id: 99 },
      ...[0, 1.001, 999999999.999, 1000000000, "1.23", null].map(amount =>
        ({ ...valid, lines: [{ ...valid.lines[0], amount }] })),
      ...[{ date: "04/31/2026" }, { type: "" }, { description: "x".repeat(2001) }, { extra: true }].map(line =>
        ({ ...valid, lines: [{ ...valid.lines[0], ...line }] }))
    ];
    for (const body of bad) assert.equal((await call(path, { method: "POST", token, body })).status, 400, JSON.stringify(body).slice(0, 150));
    const accepted = await call(path, { method: "POST", token, body: { ...valid, lines: Array(200).fill({ ...valid.lines[0], amount: 999999999.99 }) } });
    assert.equal(accepted.status, 201);
    assert.equal(accepted.body.quote_id, 1);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM sales_quotes").get().n, 1);
    const large = { ...valid, notes: "中".repeat(16000), terms: "中".repeat(16000),
      lines: Array(200).fill({ ...valid.lines[0], description: "中".repeat(2000) }) };
    assert.equal((await call(path, { method: "POST", token, body: large })).status, 201);
    assert.equal((await call(`${path}/1`, { method: "PUT", token, body: large })).status, 200);
    assert.equal((await call(path, { method: "POST", token, body: { ...valid, notes: "x".repeat(2 * 1024 * 1024) } })).status, 413);
  });
});

test("quotes SQLite: conversion permissions, exact records, concurrent retry and read-only audit", async () => {
  await fixture(async ({ db, call, login }) => {
    const owner = await login("one"), quotes = await login("quotes"), editor = await login("editor");
    const created = await call(path, { method: "POST", token: quotes, body: valid });
    const target = `${path}/${created.body.quote_id}`;
    assert.equal((await call(`${target}/convert`, { method: "POST", token: quotes })).status, 403);
    assert.equal((await call("/v1/customers/other/quotes/1/convert", { method: "POST", token: editor })).status, 404);
    const attempts = await Promise.all([1, 2].map(() => call(`${target}/convert`, { method: "POST", token: editor })));
    assert.deepEqual(attempts.map(response => response.status).sort(), [200, 409]);
    const converted = attempts.find(response => response.status === 200).body;
    assert.equal(new Date(converted.converted_at).toISOString(), converted.converted_at);
    assert.equal(converted.updated_at, converted.converted_at);
    const records = db.prepare("SELECT record_id, record_date, type, amount FROM statement_records WHERE company_id = 1 ORDER BY record_id").all();
    assert.deepEqual(records.map(row => ({ ...row })), [
      { record_id: 50, record_date: "10/01/2026", type: "Existing", amount: -7 },
      { record_id: 51, record_date: "10/27/2026", type: "Charge: Replacement pump", amount: -123.45 },
      { record_id: 52, record_date: "10/28/2026", type: "Credit", amount: 0.29 },
      { record_id: 53, record_date: "02/29/2028", type: "Credit", amount: 10 }
    ]);
    for (const [suffix, method, body] of [["/convert", "POST"], ["", "DELETE"], ["", "PUT", valid]]) {
      assert.equal((await call(target + suffix, { method, token: owner, body })).status, 409);
    }
    assert.deepEqual((await call(path, { token: quotes })).body, [converted]);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records").get().n, 4);
  });
});

test("quotes SQLite: conversion rolls back all inserts if the atomic claim fails", async () => {
  await fixture(async ({ db, call, login, failConversion }) => {
    const token = await login("one");
    const created = await call(path, { method: "POST", token, body: valid });
    const target = `${path}/${created.body.quote_id}/convert`;
    failConversion();
    assert.equal((await call(target, { method: "POST", token })).status, 500);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records").get().n, 1);
    assert.equal((await call(path, { token })).body[0].converted_at, null);
    assert.equal((await call(target, { method: "POST", token })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records").get().n, 4);
  });
});

test("quotes SQLite: backups preserve converted state and high watermarks, reject invalid quotes, accept old backups", async () => {
  await fixture(async ({ db, call, login }) => {
    const token = await login("one");
    const first = await call(path, { method: "POST", token, body: valid });
    await call(`${path}/${first.body.quote_id}/convert`, { method: "POST", token });
    const backup = (await call("/v1/backup", { token })).body;
    assert.equal(backup.sales_quotes.length, 1);
    assert.equal(backup.sales_quote_sequences[0].next_id, 2);
    const later = await call(path, { method: "POST", token, body: valid });
    await call(`${path}/${later.body.quote_id}`, { method: "DELETE", token });
    assert.equal(db.prepare("SELECT next_id FROM sales_quote_sequences WHERE company_id = 1").get().next_id, 3);
    for (const modify of [
      copy => copy.sales_quotes.push(copy.sales_quotes[0]),
      copy => { copy.sales_quotes[0].quote_number = "SQ-000999"; },
      copy => { copy.sales_quotes[0].converted_at = "invalid"; },
      copy => { delete copy.sales_quotes[0].converted_at; },
      copy => { copy.sales_quotes[0].converted_at = "2000-01-01T00:00:00.000Z"; },
      copy => { copy.sales_quotes[0].customer_id = "missing"; },
      copy => { copy.sales_quotes[0].lines_json = "[]"; },
      copy => { copy.sales_quote_sequences[0].next_id = 1; },
      copy => { copy.sales_quote_sequences.push({ next_id: 3 }); }
    ]) {
      const bad = structuredClone(backup); modify(bad);
      assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: bad })).status, 400);
      assert.equal(db.prepare("SELECT COUNT(*) AS n FROM sales_quotes").get().n, 1);
    }
    assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: backup })).status, 200);
    assert.equal(db.prepare("SELECT next_id FROM sales_quote_sequences WHERE company_id = 1").get().next_id, 3,
      "restoring backup next_id=2 must retain live next_id=3 after quote 2 was deleted");
    assert.equal((await call(`${path}/1/convert`, { method: "POST", token })).status, 409);
    const third = await call(path, { method: "POST", token, body: valid });
    assert.equal(third.body.quote_number, "SQ-000003");
    const old = structuredClone(backup);
    delete old.sales_quotes; delete old.sales_quote_sequences;
    assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: old })).status, 200);
    assert.deepEqual((await call(path, { token })).body, []);
    assert.equal((await call(path, { method: "POST", token, body: valid })).body.quote_number, "SQ-000004");
    const imported = structuredClone(backup);
    imported.sales_quotes[0].quote_id = 80;
    imported.sales_quotes[0].quote_number = "SQ-000080";
    imported.sales_quote_sequences = [{ next_id: 100 }];
    assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: imported })).status, 200);
    assert.equal((await call(path, { method: "POST", token, body: valid })).body.quote_number, "SQ-000100");
  });
});

test("quotes SQLite: combined quote, visit photo and PDF logo backup can exceed the quote request cap", async () => {
  await fixture(async ({ call, login }) => {
    const token = await login("one");
    const largeQuote = { ...valid, notes: "中".repeat(16000), terms: "中".repeat(16000),
      lines: Array(200).fill({ ...valid.lines[0], description: "中".repeat(2000) }) };
    const image = "data:image/png;base64," + "YQ==".repeat(149990);
    assert.equal((await call(path, { method: "POST", token, body: largeQuote })).status, 201);
    assert.equal((await call("/v1/customers/shared/visits", { method: "POST", token,
      body: { ph: "7.4", chlo: "2-4", stb: "80", alk: null, cal: null, notes: "", photo_data: image } })).status, 201);
    assert.equal((await call("/v1/settings", { method: "PUT", token, body: { key: "logoData", value: image } })).status, 200);
    const backup = (await call("/v1/backup", { token })).body;
    assert.ok(Buffer.byteLength(JSON.stringify(backup)) > 2 * 1024 * 1024);
    assert.ok(Buffer.byteLength(JSON.stringify(backup)) < 50 * 1024 * 1024);
    assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: backup })).status, 200);
    assert.deepEqual((await call(path, { token })).body[0].lines, largeQuote.lines);
    assert.equal((await call("/v1/customers/shared/visits", { token })).body[0].photo_data, image);
    assert.equal((await call("/v1/settings", { token })).body.logoData, image);
  });
});

test("quotes SQLite: company deletion removes quotes and sequences without affecting other companies", async () => {
  await fixture(async ({ db, call, login }) => {
    const one = await login("one"), two = await login("two"), master = await login("master");
    for (const token of [one, two]) assert.equal((await call(path, { method: "POST", token, body: valid })).status, 201);
    assert.equal((await call("/v1/admin/companies/1", { method: "DELETE", token: master })).status, 200);
    assert.deepEqual(db.prepare("SELECT company_id FROM sales_quotes").all().map(row => row.company_id), [2]);
    assert.deepEqual(db.prepare("SELECT company_id FROM sales_quote_sequences").all().map(row => row.company_id), [2]);
  });
});
