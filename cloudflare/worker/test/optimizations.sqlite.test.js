import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createHmac, randomUUID } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";

const schema = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
let serial = 0;
const env = { SESSION_SIGNING_SECRET: "synthetic-optimization-secret",
  SILVERDICE_URL: "https://optimizations.invalid", SILVERDICE_AUTH_TOKEN: "synthetic-transport-token" };
const monthly = { request_id: "12345678-1234-4123-8123-123456789012", date: "10/01/2026", type: "Monthly payment" };
const permissions = { canViewCustomers: true, canViewStatements: true, canEditStatements: true,
  canAccessVisits: true, canAccessSalesQuote: true, canEditPdf: true, canViewPdf: true };
const base = "/v1/customers/a";
const encode = value => value === null ? { type: "null" } : typeof value === "number"
  ? { type: Number.isInteger(value) ? "integer" : "float", value: String(value) } : { type: "text", value };

async function fixture(run, { legacy = false } = {}) {
  const db = new DatabaseSync(":memory:"), originalFetch = globalThis.fetch;
  db.exec("PRAGMA foreign_keys = ON");
  db.exec(schema);
  db.exec(`
    INSERT INTO companies (company_id,company_name,username) VALUES (1,'Synthetic One','one'),(2,'Synthetic Two','two');
    INSERT INTO accounts (account_id,username,password_hash,role,company_id) VALUES
      (1,'company','unused','COMPANY',1),(2,'employee','unused','EMPLOYEE',1),
      (3,'other','unused','COMPANY',2),(4,'master','unused','MASTER',NULL);
    INSERT INTO record_types VALUES (1,'Monthly payment'),(1,'Service'),(2,'Monthly payment');
    INSERT INTO customers (company_id,customer_id,first_name,last_name,service_day,amount_charged,status) VALUES
      (1,'a','Alpha','One','Monday',100,'Active'),(1,'b','Beta','Two','Tuesday',200,'Active'),
      (1,'c','Gamma','Three','Wednesday',300,'Active'),(1,'d','Delta','Four','Monday',400,'Inactive'),
      (2,'a','Foreign','One','Monday',900,'Active');
    INSERT INTO statement_records VALUES
      (1,1,'a','10/02/2026','Service',100),(2,1,'a','10/01/2026','Payment',-20),
      (3,1,'a','10/01/2026','Service',50),(4,1,'a','10/01/2026','Service',30),
      (5,2,'a','10/01/2026','Service',999);
    INSERT INTO customer_visits (company_id,visit_id,customer_id,visited_at,ph,chlo,stb,notes,photo_data) VALUES
      (1,1,'a','2026-10-01T10:00:00.000Z','7.2','2-4','80','Synthetic notes','data:image/png;base64,AAAA'),
      (1,2,'a','2026-10-02T10:00:00.000Z','7.4','2-4','70','',''),
      (1,3,'b','2026-10-03T10:00:00.000Z','7.4','2-4','70','','');
    INSERT INTO sales_quotes VALUES
      (1,1,'SQ-000001','a','10/01/2026','Full notes','Full terms',
       '[{"date":"10/01/2026","type":"Service","description":"","amount":25}]',
       '2026-10-01T10:00:00.000Z','2026-10-01T10:00:00.000Z','2026-10-01T10:00:00.000Z');
  `);
  if (legacy) db.exec("DROP TABLE schema_migrations; DROP TABLE monthly_statement_requests; ALTER TABLE customers DROP COLUMN starting_date; ALTER TABLE customers DROP COLUMN route_order");
  const sqlLog = [];
  let fail = () => false;
  const execute = stmt => {
    sqlLog.push(stmt.sql);
    if (fail(stmt.sql)) throw new Error("Synthetic database failure");
    const prepared = db.prepare(stmt.sql);
    const args = (stmt.args || []).map(arg => arg.type === "null" ? null
      : ["integer", "float"].includes(arg.type) ? Number(arg.value) : arg.value);
    const rows = prepared.all(...args), cols = prepared.columns().map(column => ({ name: column.name }));
    return { cols, rows: rows.map(row => cols.map(column => encode(row[column.name]))) };
  };
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
  let worker;
  const reload = async () => { worker = (await import(`../src/index.js?optimization=${++serial}`)).default; };
  const token = (overrides = {}) => {
    const session = { sub: 1, username: "company", role: "COMPANY", companyId: 1,
      permissions, allowedCustomerIds: [], allowedDays: [], exp: Math.floor(Date.now() / 1000) + 3600, ...overrides };
    const data = `${Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url")}.${Buffer.from(JSON.stringify(session)).toString("base64url")}`;
    return `${data}.${createHmac("sha256", env.SESSION_SIGNING_SECRET).update(data).digest("base64url")}`;
  };
  const company = token(), employee = token({ sub: 2, username: "employee", role: "EMPLOYEE",
    allowedCustomerIds: ["a"], allowedDays: ["Wednesday"] });
  const call = async (path, { method = "GET", body, auth = company } = {}) => {
    const response = await worker.fetch(new Request(`https://worker.test${path}`, { method,
      headers: { Authorization: `Bearer ${auth}`, "Content-Type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) }), env);
    return { status: response.status, body: await response.json() };
  };
  try {
    await reload();
    await run({ db, call, token, employee, reload, sqlLog, setFailure: callback => { fail = callback; } });
  } finally { globalThis.fetch = originalFetch; db.close(); }
}

test("customer lists treat omitted or blank day as all days for paged and desktop full-list callers", async () => {
  await fixture(async ({ call, employee }) => {
    for (const suffix of ["", "?service_day=", "?service_day=%20%20"]) {
      const full = await call("/v1/customers" + suffix);
      assert.equal(full.status, 200);
      assert.equal(full.body.length, 4);
    }
    for (const day of ["", "&service_day=", "&service_day=%20"]) {
      const page = await call("/v1/customers?limit=50&offset=0" + day);
      assert.equal(page.status, 200);
      assert.equal(page.body.total, 4);
      assert.equal(page.body.rows.length, 4);
    }
    const scoped = await call("/v1/customers?limit=50&service_day=", { auth: employee });
    assert.deepEqual(scoped.body.rows.map(c => c.customer_id), ["a", "c"]);
    const selectedDay = await call("/v1/customers?limit=50&service_day=Monday");
    assert.deepEqual(selectedDay.body.rows.map(c => c.customer_id).sort(), ["a", "d"]);
    const searched = await call("/v1/customers?limit=50&q=Beta&service_day=");
    assert.deepEqual(searched.body.rows.map(c => c.customer_id), ["b"]);
  });
});

test("optimization: grouped balances include zeroes and enforce permissions, company and assigned ID/day scope", async () => {
  await fixture(async ({ call, employee, token }) => {
    assert.deepEqual((await call("/v1/balances", { auth: employee })).body,
      { rows: [{ customer_id: "a", balance: 160 }, { customer_id: "c", balance: 0 }] });
    assert.equal((await call("/v1/balances")).body.rows.length, 4);
    assert.deepEqual((await call("/v1/balances", { auth: token({ sub: 3, username: "other", companyId: 2 }) })).body.rows,
      [{ customer_id: "a", balance: 999 }]);
    assert.equal((await call("/v1/balances", { auth: token({ sub: 2, username: "employee", role: "EMPLOYEE", permissions: {} }) })).status, 403);
    assert.equal((await call("/v1/balances", { auth: token({ sub: 4, username: "master", role: "MASTER" }) })).status, 403);
  });
});

test("optimization: monthly atomic active assigned selection, durable concurrent idempotency and mismatched request protection", async () => {
  await fixture(async ({ db, call, employee, reload, token }) => {
    const post = (body = monthly, auth = employee) => call("/v1/statements/monthly", { method: "POST", body, auth });
    const results = await Promise.all([post(), post(), post()]);
    assert.ok(results.every(result => result.status === 200));
    assert.ok(results.every(result => result.body.added === 2));
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records WHERE type = ?").get(monthly.type).n, 2);
    assert.deepEqual(db.prepare("SELECT customer_id,amount FROM statement_records WHERE type = ? ORDER BY customer_id").all(monthly.type)
      .map(row => ({ ...row })), [{ customer_id: "a", amount: 100 }, { customer_id: "c", amount: 300 }]);
    await reload();
    assert.equal((await post()).body.added, 2);
    for (const body of [{ ...monthly, date: "11/01/2026" }, { ...monthly, type: "Different" }]) assert.equal((await post(body)).status, 409);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records WHERE company_id=1").get().n, 6);
    assert.equal((await post(monthly, token({ sub: 3, username: "other", companyId: 2 }))).body.added, 1);
    for (const permissions of [{ canEditStatements: true }, { canViewStatements: true }]) {
      assert.equal((await post(monthly, token({ sub: 2, username: "employee", role: "EMPLOYEE", permissions }))).status, 403);
    }
    for (const body of [{ ...monthly, request_id: "bad" }, { ...monthly, date: "02/30/2026" }, { ...monthly, type: "" }, { ...monthly, extra: 1 }]) {
      assert.equal((await post(body)).status, 400);
    }
    const empty = token({ sub: 2, username: "employee", role: "EMPLOYEE", allowedCustomerIds: ["missing"] });
    assert.equal((await post({ ...monthly, request_id: randomUUID() }, empty)).body.added, 0);
    const all = await call("/v1/statements/monthly", { method: "POST", body: { ...monthly, request_id: randomUUID() } });
    assert.equal(all.body.added, 3);
  });
});

test("optimization: monthly and settings failures roll back claims and every write; retry can succeed", async () => {
  await fixture(async ({ db, call, setFailure, token }) => {
    setFailure(sql => sql.startsWith("UPDATE monthly_statement_requests"));
    assert.equal((await call("/v1/statements/monthly", { method: "POST", body: monthly })).status, 500);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM monthly_statement_requests").get().n, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records WHERE company_id=1").get().n, 4);
    setFailure(() => false);
    assert.equal((await call("/v1/settings/batch", { method: "PUT", body: { settings: {} },
      auth: token({ sub: 2, username: "employee", role: "EMPLOYEE", permissions: { canViewPdf: true } }) })).status, 403);
    assert.equal((await call("/v1/statements/monthly", { method: "POST", body: monthly })).body.added, 3);
    let inserts = 0;
    setFailure(sql => sql.startsWith("INSERT INTO pdf_settings") && ++inserts === 2);
    assert.equal((await call("/v1/settings/batch", { method: "PUT", body: { settings: { first: "one", second: "two" } } })).status, 500);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM pdf_settings").get().n, 0);
    setFailure(() => false);
    assert.equal((await call("/v1/settings/batch", { method: "PUT", body: { settings: { first: "one", logoData: "data:image/png;base64,AAAA" } } })).status, 200);
    for (const settings of [{ first: 3 }, { logoData: "data:image/svg+xml;base64,AAAA" }, { first: "a".repeat(16001) }, []]) {
      assert.equal((await call("/v1/settings/batch", { method: "PUT", body: { settings } })).status, 400);
    }
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM pdf_settings").get().n, 2);
    assert.equal((await call("/v1/settings/batch", { method: "PUT", body: { settings: { " first ": "updated" } } })).status, 200);
    assert.equal(db.prepare("SELECT setting_value FROM pdf_settings WHERE company_id=1 AND setting_key='first'").get().setting_value, "updated");
  });
});

test("optimization: monthly type classification uses company keywords, preserves zero charges and permits historical retries", async () => {
  await fixture(async ({ db, call }) => {
    const post = body => call("/v1/statements/monthly", { method: "POST", body });
    assert.equal((await post({ ...monthly, type: "Service" })).status, 400);
    assert.equal((await post({ ...monthly, type: "Unregistered payment" })).status, 400);
    db.exec("INSERT INTO pdf_settings VALUES (1,'recordTypeCreditKeywords','service, custom'); UPDATE customers SET amount_charged=0 WHERE company_id=1 AND customer_id='a'");
    assert.equal((await post(monthly)).status, 400);
    const request = { ...monthly, type: "Service" };
    assert.equal((await post(request)).body.added, 3);
    assert.equal(db.prepare("SELECT amount FROM statement_records WHERE company_id=1 AND customer_id='a' AND type='Service' ORDER BY record_id DESC LIMIT 1").get().amount, 0);
    db.exec("DELETE FROM record_types WHERE company_id=1 AND type_name='Service'");
    assert.equal((await post(request)).body.added, 3);
  });
});

test("optimization: opt-in customer paging/filter count and financial pages preserve legacy full responses", async () => {
  await fixture(async ({ call, employee }) => {
    assert.ok(Array.isArray((await call("/v1/customers")).body));
    const page = await call("/v1/customers?limit=1&offset=1&order=id");
    assert.equal(page.body.total, 4);
    assert.equal(page.body.rows[0].customer_id, "b");
    const assigned = await call("/v1/customers?limit=1&offset=1&order=id", { auth: employee });
    assert.equal(assigned.body.total, 2);
    assert.equal(assigned.body.rows[0].customer_id, "c");
    assert.equal((await call("/v1/customers?limit=1&service_day=Monday&q=Alpha%20One")).body.total, 1);
    assert.equal((await call("/v1/customers?limit=1&offset=99")).body.rows.length, 0);
    const legacy = await call(`${base}/statements`);
    assert.deepEqual(legacy.body.map(row => row.record_id), [1, 2, 3, 4]);
    const first = (await call(`${base}/statements?limit=2&offset=0`)).body;
    const second = (await call(`${base}/statements?limit=2&offset=2`)).body;
    assert.deepEqual(first, { total: 4, balance: 160, rows: [
      { record_id: 1, customer_id: "a", record_date: "10/02/2026", type: "Service", amount: 100, run: 160 },
      { record_id: 2, customer_id: "a", record_date: "10/01/2026", type: "Payment", amount: -20, run: 60 }
    ] });
    assert.deepEqual(second.rows.map(row => [row.record_id, row.run]), [[4, 80], [3, 50]]);
    for (const query of ["limit=0", "limit=501", "limit=1.1", "offset=-1", "offset=9007199254740992", "limit=x"]) {
      assert.equal((await call(`${base}/statements?${query}`)).status, 400);
    }
    assert.equal((await call("/v1/customers/b/statements?limit=1", { auth: employee })).status, 404);
  });
});

test("optimization: summary queries omit heavy fields, full detail includes converted quotes/photos and backups remain complete", async () => {
  await fixture(async ({ call, employee }) => {
    const visits = (await call(`${base}/visits?summary=1&limit=1&offset=1`)).body;
    assert.equal(visits.total, 2);
    assert.equal(visits.rows[0].has_photo, true);
    assert.ok(!("photo_data" in visits.rows[0]));
    assert.equal((await call(`${base}/visits?summary=1&limit=1`)).body.rows[0].has_photo, false);
    for (const path of [`${base}/visits/1`, "/v1/visits/1"]) {
      assert.equal((await call(path)).body.photo_data, "data:image/png;base64,AAAA");
    }
    assert.equal((await call("/v1/visits/3", { auth: employee })).status, 404);
    assert.ok("photo_data" in (await call(`${base}/visits`)).body[0]);
    const quotes = (await call(`${base}/quotes?summary=1&limit=1`)).body;
    assert.equal(quotes.total, 1);
    assert.equal(quotes.rows[0].total, 25);
    for (const key of ["lines", "lines_json", "notes", "terms"]) assert.ok(!(key in quotes.rows[0]));
    for (const path of [`${base}/quotes/1`, "/v1/quotes/1"]) {
      const detail = await call(path);
      assert.equal(detail.status, 200);
      assert.equal(detail.body.lines[0].amount, 25);
      assert.equal(detail.body.notes, "Full notes");
      assert.ok(detail.body.converted_at);
    }
    const backup = (await call("/v1/backup")).body;
    assert.equal(backup.customer_visits[0].photo_data, "data:image/png;base64,AAAA");
    assert.equal(backup.sales_quotes[0].notes, "Full notes");
    assert.ok(backup.sales_quotes[0].lines_json.includes("25"));
  });
});

test("optimization: financial pages accept legacy unpadded dates and place invalid calendar dates last", async () => {
  await fixture(async ({ db, call }) => {
    db.exec(`INSERT INTO statement_records VALUES
      (6,1,'a','bad','Service',10),(7,1,'a','9/8/2026','Payment',-3),
      (8,1,'a','02/30/2026','Service',2),(9,1,'a',' 09/01/2026 ','Service',5)`);
    const page = (await call(`${base}/statements?limit=50`)).body;
    assert.equal(page.balance, 174);
    assert.deepEqual(page.rows.map(row => [row.record_id, row.run]),
      [[8,174],[6,172],[1,162],[2,62],[4,82],[3,52],[7,2],[9,5]]);
  });
});

test("optimization: monthly audit is unioned on restore and survives customer/statement deletion, rejects conflicting backups atomically", async () => {
  await fixture(async ({ db, call, reload }) => {
    const old = (await call("/v1/backup")).body;
    delete old.monthly_statement_requests;
    const post = () => call("/v1/statements/monthly", { method: "POST", body: monthly });
    assert.equal((await post()).body.added, 3);
    const current = (await call("/v1/backup")).body;
    assert.equal(current.monthly_statement_requests.length, 1);
    assert.equal((await call("/v1/backup/restore", { method: "POST", body: old })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM monthly_statement_requests").get().n, 1);
    await reload();
    assert.equal((await post()).body.added, 3);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records WHERE company_id=1").get().n, 4);
    const conflict = structuredClone(current);
    conflict.monthly_statement_requests[0].type = "Different";
    assert.equal((await call("/v1/backup/restore", { method: "POST", body: conflict })).status, 409);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records WHERE company_id=1").get().n, 4);
    assert.equal((await call(base, { method: "DELETE" })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM monthly_statement_requests").get().n, 1);
    assert.equal((await post()).body.added, 3);
    assert.equal((await call("/v1/backup/restore", { method: "POST", body: current })).status, 200);
    const malformed = structuredClone(current);
    malformed.monthly_statement_requests[0].added = -1;
    assert.equal((await call("/v1/backup/restore", { method: "POST", body: malformed })).status, 400);
  });
});

test("optimization: durable version migrations reuse legacy upgrades and do no DDL on warmed or new isolates", async () => {
  await fixture(async ({ db, call, reload, sqlLog }) => {
    assert.equal((await call("/v1/health")).status, 200);
    assert.deepEqual(db.prepare("SELECT version FROM schema_migrations ORDER BY version").all().map(row => row.version), [1, 2, 3]);
    assert.ok(db.prepare("PRAGMA table_info(customers)").all().some(column => column.name === "route_order"));
    sqlLog.length = 0;
    await call("/v1/health");
    assert.deepEqual(sqlLog, ["SELECT 1 AS ready"]);
    await reload();
    sqlLog.length = 0;
    await call("/v1/health");
    assert.equal(sqlLog.filter(sql => /^(CREATE|ALTER|DROP|INSERT)/.test(sql)).length, 0);
  }, { legacy: true });
});

test("optimization: unrelated migration failure is surfaced, not marked applied, and safely retried", async () => {
  await fixture(async ({ db, call, setFailure }) => {
    setFailure(sql => sql.startsWith("ALTER TABLE customers ADD COLUMN starting_date"));
    assert.equal((await call("/v1/health")).status, 500);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM schema_migrations").get().n, 0);
    setFailure(() => false);
    assert.equal((await call("/v1/health")).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM schema_migrations").get().n, 3);
  }, { legacy: true });
});

test("optimization: atomic company deletion removes monthly audit while rollback preserves all live data", async () => {
  await fixture(async ({ db, call, setFailure, token }) => {
    assert.equal((await call("/v1/statements/monthly", { method: "POST", body: monthly })).status, 200);
    const master = token({ sub: 4, username: "master", role: "MASTER" });
    setFailure(sql => sql === "DELETE FROM customers WHERE company_id = ?");
    assert.equal((await call("/v1/admin/companies/1", { method: "DELETE", auth: master })).status, 500);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM monthly_statement_requests").get().n, 1);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM companies WHERE company_id=1").get().n, 1);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records WHERE company_id=1").get().n, 7);
    setFailure(() => false);
    assert.equal((await call("/v1/admin/companies/1", { method: "DELETE", auth: master })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM monthly_statement_requests").get().n, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM companies WHERE company_id=2").get().n, 1);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM statement_records WHERE company_id=2").get().n, 1);
  });
});
