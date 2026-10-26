import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";

const schema = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
const env = { SESSION_SIGNING_SECRET: "synthetic-reader-secret",
  SILVERDICE_URL: "https://reader-sqlite.invalid", SILVERDICE_AUTH_TOKEN: "synthetic-transport" };
const password = "synthetic-password";
const salt = Buffer.from("synthetic-reader-salt");
const material = await crypto.subtle.importKey("raw", Buffer.from(password), "PBKDF2", false, ["deriveBits"]);
const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: 10000, hash: "SHA-256" }, material, 256);
const hash = `pbkdf2-sha256$10000$${salt.toString("base64url")}$${Buffer.from(bits).toString("base64url")}`;
const documentHash = "a".repeat(64);
const previewPath = "/v1/statement-reader/preview", importPath = "/v1/statement-reader/import";
const source = (changes = {}) => ({ source_id: "1:1", page: 1, line: 1, text: "Zelle from García José",
  date_text: "10/20", deposit_text: "125.50", direction: "deposit", reference: "", ...changes });
const selected = (changes = {}) => ({ source_id: "1:1", page: 1, line: 1, text: "Synthetic deposit",
  date: "10/20/2026", amount: 125.5, customer_id: "jose", reference: "", override_duplicate: false, ...changes });
const document = (rows, document_hash = documentHash) => ({ document_hash, rows });
let sequence = 0;

function encode(value) {
  return value === null ? { type: "null" } : typeof value === "number"
    ? { type: Number.isInteger(value) ? "integer" : "float", value: String(value) } : { type: "text", value };
}

async function fixture(run, { legacy = false } = {}) {
  const db = new DatabaseSync(":memory:"), originalFetch = globalThis.fetch;
  db.exec("PRAGMA foreign_keys = ON");
  db.exec(schema);
  if (legacy) db.exec("DROP TABLE reader_imports");
  db.exec(`
    INSERT INTO companies (company_id, company_name, username) VALUES (1,'Synthetic One','one'),(2,'Synthetic Two','two');
    INSERT INTO customers (company_id,customer_id,first_name,last_name,service_day) VALUES
      (1,'jose','José','García','Monday'),(1,'other','Unassigned','Person','Tuesday'),
      (1,'day','Ana María','De la Cruz','Wednesday'),(1,'punct','Mary','O''Neil','Monday'),
      (1,'amb1','Alex','Smith','Monday'),(1,'amb2','Smith','Alex','Monday'),
      (2,'jose','Foreign','Customer','Monday');
    INSERT INTO statement_records VALUES (50,1,'jose','10/01/2026','Arbitrary credit type',-7);
  `);
  const accounts = [["one", "COMPANY", 1, {}], ["two", "COMPANY", 2, {}],
    ["reader", "EMPLOYEE", 1, { canAccessStatementReader: true }],
    ["editor", "EMPLOYEE", 1, { canAccessStatementReader: true, canEditStatements: true }],
    ["denied", "EMPLOYEE", 1, {}], ["statements", "EMPLOYEE", 1, { canEditStatements: true }],
    ["master", "MASTER", null, {}]];
  const insert = db.prepare("INSERT INTO accounts (account_id,username,password_hash,role,company_id,employee_id,permissions_json,allowed_customer_ids_json,allowed_days_json) VALUES (?,?,?,?,?,?,?,?,?)");
  accounts.forEach(([name, role, company, permissions], index) => insert.run(index + 1, name, hash, role, company,
    index + 1, JSON.stringify(permissions), role === "EMPLOYEE" ? '["jose"]' : "[]",
    role === "EMPLOYEE" ? '["Wednesday"]' : "[]"));
  let failAt = 0, paymentInserts = 0;
  function execute(stmt) {
    if (stmt.sql.startsWith("INSERT INTO statement_records") && ++paymentInserts === failAt) {
      throw new Error("Synthetic payment failure");
    }
    const prepared = db.prepare(stmt.sql);
    const args = (stmt.args || []).map(arg => arg.type === "null" ? null
      : ["integer", "float"].includes(arg.type) ? Number(arg.value) : arg.value);
    const rows = prepared.all(...args), cols = prepared.columns().map(column => ({ name: column.name }));
    return { cols, rows: rows.map(row => cols.map(column => encode(row[column.name]))) };
  }
  globalThis.fetch = async (url, options) => {
    assert.equal(url, `${env.SILVERDICE_URL}/v2/pipeline`);
    const { requests } = JSON.parse(options.body);
    return Response.json({ results: requests.map(request => {
      if (request.type === "close") return { type: "ok", response: { type: "close" } };
      if (request.type === "execute") {
        try { return { type: "ok", response: { result: execute(request.stmt) } }; }
        catch (error) { return { type: "error", error: { message: error.message } }; }
      }
      const step_results = [], step_errors = [];
      const satisfied = condition => !condition || (condition.type === "ok"
        ? step_results[condition.step] != null : !satisfied(condition.cond));
      for (const [index, step] of request.batch.steps.entries()) {
        step_results[index] = step_errors[index] = null;
        if (!satisfied(step.condition)) continue;
        try { step_results[index] = execute(step.stmt); }
        catch (error) { step_errors[index] = { message: error.message }; }
      }
      return { type: "ok", response: { result: { step_results, step_errors } } };
    }) });
  };
  try {
    const { default: worker } = await import(`../src/index.js?reader=${++sequence}`);
    const call = async (path, { method = "GET", token, body, raw } = {}) => {
      const response = await worker.fetch(new Request(`https://worker.test${path}`, {
        method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
        ...(raw !== undefined ? { body: raw } : body === undefined ? {} : { body: JSON.stringify(body) })
      }), env);
      return { status: response.status, body: await response.json() };
    };
    const login = async username => {
      const result = await call("/v1/auth/login", { method: "POST", body: { username, password } });
      assert.equal(result.status, 200);
      return result.body.token;
    };
    await run({ db, call, login, failPayment: ordinal => { failAt = ordinal; paymentInserts = 0; } });
  } finally { globalThis.fetch = originalFetch; db.close(); }
}

test("reader: permissions, company and customer isolation, master preview and legacy schema", async () => {
  await fixture(async ({ call, login, db }) => {
    const one = await login("one"), reader = await login("reader"), editor = await login("editor");
    const post = (token, rows = [source()]) => call(previewPath, { method: "POST", token, body: document(rows) });
    assert.equal((await post()).status, 401);
    assert.equal((await post("invalid")).status, 401);
    for (const name of ["denied", "statements", "master"]) assert.equal((await post(await login(name))).status, 403);
    assert.equal((await post(reader)).body.rows[0].status, "ready");
    assert.deepEqual((await post(reader)).body.customers, [
      { customer_id: "jose", first_name: "José", last_name: "García" }
    ]);
    assert.equal((await post(await login("two"))).body.rows[0].customer_id, "");
    assert.equal((await post(reader, [source({ text: "Unassigned Person" })])).body.rows[0].candidate_ids.length, 0);
    assert.deepEqual((await post(reader, [source({ text: "Unassigned Person" })])).body.customers, []);
    assert.equal((await post(reader, [source({ text: "la Cruz Ana María De" })])).body.rows[0].customer_id, "day");
    assert.equal((await call("/v1/customers", { token: reader })).status, 200);
    assert.equal((await call("/v1/customers/jose", { token: reader })).status, 200);
    assert.equal((await call("/v1/customers/other", { token: reader })).status, 404);
    assert.equal((await call("/v1/customers/jose/statements", { token: reader })).status, 403);
    assert.equal((await call("/v1/customers/jose/statements", { method: "POST", token: reader, body: {} })).status, 403);
    assert.equal((await call(importPath, { method: "POST", token: reader, body: document([selected()]) })).status, 403);
    assert.equal((await call(importPath, { method: "POST", token: await login("statements"), body: document([selected()]) })).status, 403);
    assert.equal((await call(importPath, { method: "POST", token: editor, body: document([selected({ customer_id: "other" })]) })).status, 404);
    const master = await login("master");
    const preview = (await call("/v1/admin/companies/1/session", { method: "POST", token: master })).body.token;
    assert.equal((await post(preview)).status, 200);
    assert.equal((await call(importPath, { method: "POST", token: preview, body: document([selected()]) })).status, 403);
    const account = await call("/v1/auth/login", { method: "POST", body: { username: "denied", password } });
    assert.equal(account.body.account.permissions.canAccessStatementReader, false);
    assert.ok(db.prepare("SELECT name FROM sqlite_master WHERE name = 'reader_imports'").get());
    assert.equal((await post(one)).status, 200);
  }, { legacy: true });
});

test("reader: Zelle/Zel filtering preserves database customer matches in either name order", async () => {
  const { zelleDepositRows } = await import("../public/statement-reader.mjs");
  await fixture(async ({ call, login, db }) => {
    const token = await login("one");
    const before = db.prepare("SELECT COUNT(*) n FROM statement_records").get().n;
    const rows = [
      "Zelle From Jose Garcia", "Zelle From Garcia Jose",
      "Zel From Jose Garcia", "zEL from GARCIA, JOSE",
    ].map((text, i) => source({ text, source_id: `1:${i + 1}`, line: i + 1, date_text: "9/8", deposit_text: `${110 + i}.00` }));
    const filtered = zelleDepositRows(rows);
    assert.equal(filtered.length, 4);
    const result = await call(previewPath, { method: "POST", token, body: document(filtered) });
    assert.equal(result.status, 200);
    assert.deepEqual(result.body.rows.map(row => [row.status, row.customer_id]),
      rows.map(() => ["ready", "jose"]));
    assert.ok(result.body.rows.every(row => row.date === `09/08/${result.body.year}`));
    assert.equal(db.prepare("SELECT COUNT(*) n FROM statement_records").get().n, before);
  });
});

test("reader: normalized bounded full names, partial and ambiguous review, strict posting dates and deposit columns", async () => {
  await fixture(async ({ call, login, db }) => {
    const token = await login("one");
    const before = db.prepare("SELECT COUNT(*) n FROM statement_records").get().n;
    const cases = [
      [{ text: "GARCIA JOSE" }, "ready", "jose"],
      [{ text: "JoseGarcia" }, "ready", "jose"],
      [{ text: "Mary O Neil" }, "ready", "punct"],
      [{ text: "María De Cruz Ana la" }, "ready", "day"],
      [{ text: "José" }, "review", ""],
      [{ text: "Alex Smith" }, "review", ""],
      [{ text: "XJosé GarciaX" }, "review", ""],
      [{ date_text: "02/29/2028" }, "ready", "jose"],
      [{ date_text: "02/29/2027" }, "ignored", ""],
      [{ date_text: "04/31" }, "ignored", ""],
      [{ date_text: "10/20/26" }, "ignored", ""],
      [{ date_text: "1/02" }, "ready", "jose"],
      [{ date_text: "9/8" }, "ready", "jose"],
      [{ date_text: "9/8/2025" }, "ready", "jose"],
      [{ date_text: "2/30" }, "ignored", ""],
      [{ date_text: "", text: "Beginning balance 10/20 $500" }, "ignored", ""],
      [{ direction: "withdrawal" }, "ignored", ""],
      [{ direction: "unknown" }, "ignored", ""],
      [{ deposit_text: "", text: "Jose Garcia balance $999.99" }, "ignored", ""],
      [{ deposit_text: "$1,234.56" }, "ready", "jose"],
      [{ deposit_text: "1,23.45" }, "ignored", ""],
      [{ deposit_text: "0" }, "ignored", ""],
      [{ deposit_text: "-1" }, "ignored", ""],
      [{ deposit_text: "1.001" }, "ignored", ""],
      [{ deposit_text: "1000000000" }, "ignored", ""],
      [{ deposit_text: "999999999.99" }, "ready", "jose"]
    ];
    for (const [changes, status, customer] of cases) {
      const result = await call(previewPath, { method: "POST", token, body: document([source(changes)]) });
      assert.equal(result.status, 200, JSON.stringify(changes));
      assert.equal(result.body.rows[0].status, status, JSON.stringify(changes));
      assert.equal(result.body.rows[0].customer_id, customer, JSON.stringify(changes));
      assert.equal(result.body.rows[0].text, source(changes).text);
    }
    const year = new Date().getUTCFullYear();
    const response = await call(previewPath, { method: "POST", token, body: document([source()]) });
    assert.equal(response.body.year, year);
    assert.equal(response.body.rows[0].date, `10/20/${year}`);
    const columns = await call(previewPath, { method: "POST", token,
      body: document([source({ text: "José García deposit 125.50 balance $9,999.99" })]) });
    assert.equal(columns.body.rows[0].amount, 125.5);
    const leap = await call(previewPath, { method: "POST", token, body: document([source({ date_text: "02/29" })]) });
    assert.equal(leap.body.rows[0].date, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? `02/29/${year}` : "");
    assert.equal(db.prepare("SELECT COUNT(*) n FROM statement_records").get().n, before);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reader_imports").get().n, 0);
  });
});

test("reader: signed payments, selected import, suspected overrides, permanent source and bank identities, concurrent retries", async () => {
  await fixture(async ({ call, login, db }) => {
    const token = await login("one"), employee = await login("editor");
    const send = (rows, hash = documentHash, who = token) => call(importPath, { method: "POST", token: who, body: document(rows, hash) });
    const preview = rows => call(previewPath, { method: "POST", token, body: document(rows) });
    const existing = selected({ date: "10/01/2026", amount: 7 });
    assert.equal((await send([existing])).body.skipped, 1);
    assert.equal((await send([{ ...existing, override_duplicate: true }])).body.imported, 1);
    assert.equal((await send([{ ...existing, override_duplicate: true }])).body.skipped, 1);
    const rows = [selected({ source_id: "1:2", line: 2, reference: "BANK-123" }),
      selected({ source_id: "1:3", line: 3, reference: "BANK-124" })];
    const result = await send(rows, documentHash, employee);
    assert.equal(result.body.imported, 1);
    assert.equal(result.body.skipped, 1);
    const payment = db.prepare("SELECT * FROM statement_records WHERE record_id = ? AND company_id = 1").get(result.body.rows[0].record_id);
    assert.equal(payment.amount, -125.5);
    assert.equal(payment.type, "Zelle Payment");
    assert.equal(db.prepare("SELECT source_text FROM reader_imports WHERE reference = 'BANK-123'").get().source_text, rows[0].text);
    assert.equal((await send([{ ...rows[1], override_duplicate: true }])).body.imported, 1);
    assert.equal((await send([selected({ reference: "bank-123", override_duplicate: true })], "b".repeat(64))).body.skipped, 1);
    const sameReference = await send([
      selected({ source_id: "2:1", page: 2, amount: 51, reference: "REF-111" }),
      selected({ source_id: "2:2", page: 2, line: 2, amount: 52, reference: "ref-111", override_duplicate: true })
    ], "e".repeat(64));
    assert.equal(sameReference.body.imported, 1);
    assert.equal(sameReference.body.skipped, 1);
    const importedPreview = await preview([source({ source_id: "1:2", line: 2 })]);
    assert.equal(importedPreview.body.rows[0].status, "imported");
    assert.equal((await preview([source({ reference: "bank-123" })])).body.rows[0].status, "imported");
    const repeated = await preview([source({ source_id: "1:8", line: 8, date_text: "10/22/2026" }), source({ source_id: "1:9", line: 9, date_text: "10/22/2026" })]);
    assert.deepEqual(repeated.body.rows.map(row => row.status), ["duplicate", "duplicate"]);
    assert.equal((await preview([source({ source_id: "1:8", line: 8, date_text: "10/20/2026" })])).body.rows[0].status, "duplicate");
    const concurrent = await Promise.all([send([selected({ reference: "BANK-999", amount: 17 })], "c".repeat(64)),
      send([selected({ reference: "BANK-999", amount: 17 })], "c".repeat(64))]);
    assert.equal(concurrent.reduce((sum, result) => sum + (result.body.imported || 0), 0), 1);
    assert.ok(concurrent.every(result => [200, 409].includes(result.status)));
    const bankRace = await Promise.all([
      send([selected({ reference: "RACE-101", amount: 19 })], "f".repeat(64)),
      send([selected({ reference: "race-101", amount: 20, override_duplicate: true })], "1".repeat(64))
    ]);
    assert.equal(bankRace.reduce((sum, result) => sum + (result.body.imported || 0), 0), 1);
    db.exec("DELETE FROM statement_records WHERE company_id = 1");
    assert.equal((await send([selected({ reference: "BANK-999", amount: 17, override_duplicate: true })], "c".repeat(64))).body.skipped, 1);
    const two = await login("two");
    assert.equal((await send([selected({ reference: "BANK-999", amount: 17 })], "c".repeat(64), two)).body.imported, 1);
  });
});

test("reader: validation, raw PDF rejection, request limits and atomic rollback", async () => {
  await fixture(async ({ call, login, db, failPayment }) => {
    const token = await login("one");
    const send = body => call(importPath, { method: "POST", token, body });
    for (const changes of [{ amount: -1 }, { amount: 0 }, { amount: "1" }, { amount: 1.001 },
      { amount: 1.0000000001 }, { amount: 1000000000 }, { date: "02/29/2027" }, { date: "10/20" },
      { reference: "Jose Garcia" }, { override_duplicate: "true" }, { page: 0 }, { line: 1.2 },
      { source_id: "" }, { text: "x".repeat(4001) }, { reference: "1".repeat(161) }]) {
      assert.equal((await send(document([selected(changes)]))).status, 400, JSON.stringify(changes));
    }
    assert.equal((await send(document([selected(), selected()]))).status, 400);
    assert.equal((await send(document([selected(), selected({ source_id: "1:2", line: 2, amount: -1 })]))).status, 400);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reader_imports").get().n, 0);
    assert.equal((await send(document([selected()], "A".repeat(64)))).status, 400);
    assert.equal((await send({ ...document([]), pdf: "raw PDF data" })).status, 400);
    assert.equal((await call(previewPath, { method: "POST", token, raw: "%PDF-1.7 synthetic" })).status, 400);
    assert.equal((await call(previewPath, { method: "POST", token, raw: " ".repeat(4 * 1024 * 1024 + 1) })).status, 413);
    assert.equal((await send(document(Array.from({ length: 1001 }, (_, index) => selected({ source_id: `1:${index + 1}`, line: index + 1 }))))).status, 400);
    const baseline = db.prepare("SELECT COUNT(*) n FROM statement_records").get().n;
    failPayment(2);
    const rows = [selected(), selected({ source_id: "1:2", line: 2, amount: 29 })];
    assert.equal((await send(document(rows))).status, 409);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM statement_records").get().n, baseline);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reader_imports").get().n, 0);
    failPayment(0);
    assert.equal((await send(document(rows))).body.imported, 2);
  });
});

test("reader: backup union retains live audit with old backups, validates new audit, rollback and privacy cleanup", async () => {
  await fixture(async ({ call, login, db }) => {
    const token = await login("one");
    const old = (await call("/v1/backup", { token })).body;
    delete old.reader_imports;
    const send = (body, hash = documentHash) => call(importPath, { method: "POST", token, body: document([body], hash) });
    const restore = body => call("/v1/backup/restore", { method: "POST", token, body });
    assert.equal((await send(selected({ reference: "BANK-123" }))).body.imported, 1);
    const backup = (await call("/v1/backup", { token })).body;
    assert.equal(backup.reader_imports.length, 1);
    db.exec("DELETE FROM reader_imports");
    assert.equal((await restore(backup)).status, 200);
    assert.equal(db.prepare("SELECT reference FROM reader_imports").get().reference, "BANK-123");
    assert.equal((await restore(old)).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reader_imports").get().n, 1);
    assert.equal((await send(selected({ override_duplicate: true }))).body.skipped, 1);
    const renumbered = structuredClone(backup);
    renumbered.statement_records.forEach(row => { row.record_id += 100; });
    renumbered.reader_imports[0].record_id += 100;
    assert.equal((await restore(renumbered)).status, 200);
    assert.equal(db.prepare("SELECT record_id FROM reader_imports").get().record_id, backup.reader_imports[0].record_id);
    for (const changes of [{ amount: 1 }, { record_date: "02/30/2026" }, { document_hash: "bad" },
      { customer_id: "" }, { record_id: 0 }, { imported_at: "bad" }, { reference: "Payer Name" }]) {
      const bad = structuredClone(backup);
      Object.assign(bad.reader_imports[0], changes);
      assert.equal((await restore(bad)).status, 400, JSON.stringify(changes));
    }
    const conflict = structuredClone(backup);
    conflict.reader_imports[0].amount = -99;
    assert.equal((await restore(conflict)).status, 409);
    assert.equal(db.prepare("SELECT amount FROM reader_imports").get().amount, -125.5);
    const refConflict = structuredClone(backup);
    refConflict.reader_imports[0].document_hash = "b".repeat(64);
    assert.equal((await restore(refConflict)).status, 409);
    const omitted = structuredClone(old);
    omitted.customers = omitted.customers.filter(row => row.customer_id !== "jose");
    omitted.statement_records = [];
    assert.equal((await restore(omitted)).status, 200);
    const tombstoneBackup = (await call("/v1/backup", { token })).body;
    assert.equal((await restore(tombstoneBackup)).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reader_imports").get().n, 1);
    assert.equal((await restore(old)).status, 200);
    assert.equal((await call("/v1/customers/jose", { method: "DELETE", token })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reader_imports").get().n, 0);
    await send(selected({ customer_id: "day", amount: 23 }), "d".repeat(64));
    const master = await login("master");
    assert.equal((await call("/v1/admin/companies/1", { method: "DELETE", token: master })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reader_imports").get().n, 0);
  });
});
