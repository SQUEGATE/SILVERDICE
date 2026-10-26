import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";

const schema = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
const env = {
  SESSION_SIGNING_SECRET: "integration-test-signing-secret-only",
  SILVERDICE_URL: "https://sqlite-transport.invalid",
  SILVERDICE_AUTH_TOKEN: "integration-test-transport-only"
};
const valid = { ph: "7.4", chlo: "2-4", stb: "80", alk: 100, cal: 200, notes: "" };
const password = "synthetic-test-password";
const salt = Buffer.from("integration-test-salt");
const material = await crypto.subtle.importKey("raw", Buffer.from(password), "PBKDF2", false, ["deriveBits"]);
const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: 10000, hash: "SHA-256" }, material, 256);
const passwordHash = `pbkdf2-sha256$10000$${salt.toString("base64url")}$${Buffer.from(bits).toString("base64url")}`;
let fixtureId = 0;

function encode(value) {
  if (value === null) return { type: "null" };
  if (typeof value === "number") return { type: Number.isInteger(value) ? "integer" : "float", value: String(value) };
  return { type: "text", value };
}

async function fixture(run, { legacy = false, oldVisits = false, existingPhoto = false, nullableVisits = false, failMigrationOnce = false } = {}) {
  const db = new DatabaseSync(":memory:");
  const originalFetch = globalThis.fetch;
  db.exec("PRAGMA foreign_keys = ON");
  db.exec(schema);
  if (oldVisits || nullableVisits) {
    db.exec("DROP TABLE customer_visits");
    let oldSchema = schema.slice(schema.indexOf("CREATE TABLE IF NOT EXISTS customer_visits"), schema.indexOf("CREATE TABLE IF NOT EXISTS record_types"));
    if (oldVisits) oldSchema = oldSchema.replace("alk INTEGER CHECK", "alk INTEGER NOT NULL CHECK").replace("cal INTEGER CHECK", "cal INTEGER NOT NULL CHECK");
    if (!existingPhoto) oldSchema = oldSchema.replace("    photo_data TEXT NOT NULL DEFAULT '',", "");
    db.exec(oldSchema);
  }
  if (legacy) {
    db.exec("DROP TABLE customer_visits");
    db.exec("ALTER TABLE customers DROP COLUMN starting_date");
    db.exec("ALTER TABLE customers DROP COLUMN status");
  }
  db.exec(`
    INSERT INTO companies (company_id, company_name, username) VALUES
      (1, 'Synthetic One', 'company-one'), (2, 'Synthetic Two', 'company-two');
    INSERT INTO customers (company_id, customer_id, first_name, last_name, service_day) VALUES
      (1, 'shared', 'Synthetic', 'Assigned', 'Monday'),
      (1, 'unassigned', 'Synthetic', 'Other', 'Tuesday'),
      (1, 'day-assigned', 'Synthetic', 'Day', 'Wednesday'),
      (2, 'shared', 'Synthetic', 'OtherCompany', 'Monday');
  `);
  if (oldVisits || nullableVisits) {
    db.exec("CREATE INDEX custom_visit_notes_idx ON customer_visits(company_id, notes)");
    db.exec("INSERT INTO customer_visits (company_id, visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes) VALUES (1, 19, 'shared', '2026-10-26T14:00:00.000Z', '7.4', '2-4', '80', 100, 200, 'Preserve me')");
    if (existingPhoto) db.prepare("UPDATE customer_visits SET photo_data = ?").run("data:image/png;base64,YQ==");
  }
  const account = db.prepare("INSERT INTO accounts (account_id, username, password_hash, role, company_id, employee_id, permissions_json, allowed_customer_ids_json, allowed_days_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
  account.run(1, "company-one", passwordHash, "COMPANY", 1, null, "{}", "[]", "[]");
  account.run(2, "company-two", passwordHash, "COMPANY", 2, null, "{}", "[]", "[]");
  account.run(3, "visits-only", passwordHash, "EMPLOYEE", 1, 3, '{"canAccessVisits":true}', '["shared"]', '["Wednesday"]');
  account.run(4, "denied", passwordHash, "EMPLOYEE", 1, 4, "{}", "[]", "[]");
  account.run(5, "master", passwordHash, "MASTER", null, null, "{}", "[]", "[]");

  function execute(stmt) {
    if (failMigrationOnce && stmt.sql === "ALTER TABLE customer_visits_migrating RENAME TO customer_visits") {
      failMigrationOnce = false;
      throw new Error("Synthetic migration failure");
    }
    const prepared = db.prepare(stmt.sql);
    const args = (stmt.args || []).map(arg =>
      arg.type === "null" ? null : ["integer", "float"].includes(arg.type) ? Number(arg.value) : arg.value);
    const rows = prepared.all(...args);
    const cols = prepared.columns().map(column => ({ name: column.name }));
    return { cols, rows: rows.map(row => cols.map(column => encode(row[column.name]))) };
  }

  // Only the HTTP wire protocol is mocked; every statement executes against SQLite.
  globalThis.fetch = async (url, options) => {
    assert.equal(url, `${env.SILVERDICE_URL}/v2/pipeline`);
    const { requests } = JSON.parse(options.body);
    const results = requests.map(request => {
      if (request.type === "close") return { type: "ok", response: { type: "close" } };
      if (request.type === "execute") {
        try {
          return { type: "ok", response: { result: execute(request.stmt) } };
        } catch (error) {
          return { type: "error", error: { message: error.message } };
        }
      }
      assert.equal(request.type, "batch");
      const step_results = [];
      const step_errors = [];
      const satisfied = condition => !condition
        || (condition.type === "ok" ? step_results[condition.step] != null : !satisfied(condition.cond));
      for (const [index, step] of request.batch.steps.entries()) {
        step_results[index] = null;
        step_errors[index] = null;
        if (!satisfied(step.condition)) continue;
        try {
          step_results[index] = execute(step.stmt);
        } catch (error) {
          step_errors[index] = { message: error.message };
        }
      }
      return { type: "ok", response: { result: { step_results, step_errors } } };
    });
    return Response.json({ results });
  };
  try {
    const { default: worker } = await import(`../src/index.js?sqlite-fixture=${++fixtureId}`);
    const call = async (path, { method = "GET", body, token } = {}) => {
      const response = await worker.fetch(new Request(`https://worker.test${path}`, {
        method,
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(body === undefined ? {} : { "Content-Type": "application/json" })
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      }), env);
      return { status: response.status, body: await response.json() };
    };
    const login = async username => {
      const response = await call("/v1/auth/login", { method: "POST", body: { username, password } });
      assert.equal(response.status, 200);
      return response.body.token;
    };
    await run({ db, call, login });
  } finally {
    globalThis.fetch = originalFetch;
    db.close();
  }
}

test("SQLite: visit deletion checks permissions, customer and company and removes the photo", async () => {
  await fixture(async ({ db, call, login }) => {
    const one = await login("company-one");
    const two = await login("company-two");
    const employee = await login("visits-only");
    const denied = await login("denied");
    const master = await login("master");
    const path = "/v1/customers/shared/visits";
    const created = await call(path, { method: "POST", token: one,
      body: { ...valid, photo_data: "data:image/png;base64,YQ==" } });
    const target = `${path}/${created.body.visit_id}`;
    assert.equal(created.status, 201);
    assert.equal((await call(target, { method: "DELETE" })).status, 401);
    for (const token of [denied, master]) {
      assert.equal((await call(target, { method: "DELETE", token })).status, 403);
    }
    assert.equal((await call(target, { method: "DELETE", token: two })).status, 404);
    assert.equal((await call("/v1/customers/unassigned/visits/1", { method: "DELETE", token: employee })).status, 404);
    assert.equal((await call("/v1/customers/unassigned/visits/1", { method: "DELETE", token: one })).status, 404);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits").get().n, 1);
    assert.equal((await call(target, { method: "DELETE", token: employee })).status, 200);
    assert.deepEqual((await call(path, { token: one })).body, []);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits").get().n, 0);
    assert.equal((await call(target, { method: "DELETE", token: one })).status, 404);
  });
});

test("SQLite: create/read/edit preserve ID and timestamp and isolate companies", async () => {
  await fixture(async ({ db, call, login }) => {
    const one = await login("company-one");
    const two = await login("company-two");
    const before = Date.now();
    const created = await call("/v1/customers/shared/visits", {
      method: "POST", token: one, body: { ...valid, visited_at: "2000-01-01T00:00:00.000Z", visit_id: 999 }
    });
    assert.equal(created.status, 201);
    assert.equal(created.body.visit_id, 1);
    assert.equal(created.body.customer_id, "shared");
    assert.equal(new Date(created.body.visited_at).toISOString(), created.body.visited_at);
    assert.ok(Date.parse(created.body.visited_at) >= before);
    assert.ok(!("company_id" in created.body));
    assert.deepEqual((await call("/v1/customers/shared/visits", { token: one })).body, [created.body]);
    assert.deepEqual((await call("/v1/customers/shared/visits", { token: two })).body, []);
    assert.equal((await call("/v1/customers/shared/visits/1", { method: "PUT", token: two, body: valid })).status, 404);

    const edited = await call("/v1/customers/shared/visits/1", {
      method: "PUT", token: one, body: { ...valid, ph: "8.0", notes: "Edited", visited_at: "2000-01-01T00:00:00.000Z" }
    });
    assert.equal(edited.status, 200);
    assert.deepEqual(edited.body, { ...created.body, ph: "8.0", notes: "Edited" });
    assert.equal((await call("/v1/customers/unassigned/visits/1", { method: "PUT", token: one, body: valid })).status, 404);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits").get().n, 1);
  });
});

test("SQLite: visits-only permissions, ID/day assignments, denied employee and master preview", async () => {
  await fixture(async ({ call, login }) => {
    const visits = await login("visits-only");
    const denied = await login("denied");
    const master = await login("master");
    const search = await call("/v1/customers?q=Synthetic", { token: visits });
    assert.equal(search.status, 200);
    assert.deepEqual(search.body.map(row => row.customer_id).sort(), ["day-assigned", "shared"]);
    assert.equal((await call("/v1/customers/shared", { token: visits })).status, 200);
    for (const id of ["shared", "day-assigned"]) {
      assert.equal((await call(`/v1/customers/${id}/visits`, { method: "POST", token: visits, body: valid })).status, 201);
    }
    for (const [method, suffix] of [["GET", ""], ["POST", ""], ["PUT", "/1"]]) {
      const args = { method, token: visits, ...(method === "GET" ? {} : { body: valid }) };
      assert.equal((await call(`/v1/customers/unassigned/visits${suffix}`, args)).status, 404);
      assert.equal((await call(`/v1/customers/shared/visits${suffix}`, { ...args, token: denied })).status, 403);
      assert.equal((await call(`/v1/customers/shared/visits${suffix}`, { ...args, token: master })).status, 403);
    }
    const preview = await call("/v1/admin/companies/1/session", { method: "POST", token: master });
    assert.equal(preview.status, 200);
    assert.equal((await call("/v1/customers/shared/visits", { token: preview.body.token })).status, 200);
    assert.equal((await call("/v1/customers/shared/visits")).status, 401);
  });
});

test("SQLite: validates all enums, integer types/ranges and notes before writes", async () => {
  await fixture(async ({ db, call, login }) => {
    const token = await login("visits-only");
    const invalid = [
      { ph: "7.1" }, { ph: 7.4 }, { chlo: "3-4" }, { stb: 80 }, { stb: "20" },
      ...["alk", "cal"].flatMap(field =>
        [0, -1, 1.5, "100", false, 2147483648, Number.MAX_SAFE_INTEGER + 1].map(value => ({ [field]: value }))),
      { notes: "a".repeat(16001) }, { notes: 1 }, { notes: null }
    ];
    for (const patch of invalid) {
      const result = await call("/v1/customers/shared/visits", { method: "POST", token, body: { ...valid, ...patch } });
      assert.equal(result.status, 400, JSON.stringify(patch).slice(0, 100));
    }
    const missing = { ...valid };
    delete missing.alk;
    assert.equal((await call("/v1/customers/shared/visits", { method: "POST", token, body: missing })).status, 201);
    db.exec("DELETE FROM customer_visits");
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits").get().n, 0);
    const result = await call("/v1/customers/shared/visits", {
      method: "POST", token, body: { ...valid, alk: 2147483647, cal: 1, notes: "水".repeat(16000) }
    });
    assert.equal(result.status, 201);
    assert.equal(result.body.notes.length, 16000);
    assert.equal((await call("/v1/customers/shared/visits/1", {
      method: "PUT", token, body: { ...valid, alk: 1.5 }
    })).status, 400);
  });
});

test("SQLite: new backups round-trip visits, old backups work, invalid visits do not destroy data", async () => {
  await fixture(async ({ db, call, login }) => {
    const one = await login("company-one");
    const two = await login("company-two");
    const visit = await call("/v1/customers/shared/visits", { method: "POST", token: one, body: valid });
    await call("/v1/customers/shared/visits", { method: "POST", token: two, body: valid });
    const backup = (await call("/v1/backup", { token: one })).body;
    assert.deepEqual(backup.customer_visits, [visit.body]);
    await call("/v1/customers/shared/visits/1", { method: "PUT", token: one, body: { ...valid, notes: "Changed" } });
    const restored = await call("/v1/backup/restore", { method: "POST", token: one, body: backup });
    assert.equal(restored.status, 200);
    assert.equal(restored.body.visits, 1);
    assert.deepEqual((await call("/v1/customers/shared/visits", { token: one })).body, [visit.body]);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits WHERE company_id = 2").get().n, 1);

    for (const customer_visits of [
      [{ ...visit.body, customer_id: "unknown" }],
      [visit.body, visit.body],
      [{ ...visit.body, visited_at: "invalid" }],
      [{ ...visit.body, visit_id: 0 }],
      [{ ...visit.body, alk: 0 }],
      null
    ]) {
      assert.equal((await call("/v1/backup/restore", {
        method: "POST", token: one, body: { ...backup, customer_visits }
      })).status, 400);
      assert.deepEqual((await call("/v1/customers/shared/visits", { token: one })).body, [visit.body]);
    }
    const old = { ...backup };
    delete old.customer_visits;
    const restoredOld = await call("/v1/backup/restore", { method: "POST", token: one, body: old });
    assert.equal(restoredOld.status, 200);
    assert.equal(restoredOld.body.visits, 0);
    assert.deepEqual((await call("/v1/customers/shared/visits", { token: one })).body, []);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits WHERE company_id = 2").get().n, 1);
  });
});

test("SQLite: customer and company deletion clean up only scoped visits", async () => {
  await fixture(async ({ db, call, login }) => {
    const one = await login("company-one");
    const two = await login("company-two");
    const master = await login("master");
    for (const token of [one, two]) {
      assert.equal((await call("/v1/customers/shared/visits", { method: "POST", token, body: valid })).status, 201);
    }
    assert.equal((await call("/v1/customers/day-assigned/visits", { method: "POST", token: one, body: valid })).status, 201);
    assert.equal((await call("/v1/customers/shared", { method: "DELETE", token: one })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits WHERE company_id = 1 AND customer_id = 'shared'").get().n, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits").get().n, 2);
    assert.equal((await call("/v1/admin/companies/1", { method: "DELETE", token: master })).status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits WHERE company_id = 1").get().n, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM customer_visits WHERE company_id = 2").get().n, 1);
  });
});

test("SQLite: runtime migration upgrades existing DB before serving authenticated visits", async () => {
  await fixture(async ({ db, call, login }) => {
    assert.equal(db.prepare("SELECT name FROM sqlite_master WHERE name = 'customer_visits'").get(), undefined);
    const token = await login("visits-only");
    const created = await call("/v1/customers/shared/visits", { method: "POST", token, body: valid });
    assert.equal(created.status, 201);
    assert.deepEqual((await call("/v1/customers/shared/visits", { token })).body, [created.body]);
    assert.ok(db.prepare("SELECT name FROM sqlite_master WHERE name = 'customer_visits_customer_idx'").get());
    const columns = db.prepare("PRAGMA table_info(customers)").all().map(row => row.name);
    for (const name of ["starting_date", "status", "route_order"]) assert.ok(columns.includes(name));
  }, { legacy: true });
});

test("SQLite: optional ALK/CAL normalize to null and photo add/preserve/remove round-trips", async () => {
  await fixture(async ({ call, login }) => {
    const token = await login("visits-only");
    for (const patch of [{ alk: null, cal: null }, { alk: "", cal: " \t " }, { alk: undefined, cal: undefined }]) {
      const created = await call("/v1/customers/shared/visits", { method: "POST", token, body: { ...valid, ...patch } });
      assert.equal(created.status, 201);
      assert.equal(created.body.alk, null);
      assert.equal(created.body.cal, null);
      assert.equal(created.body.photo_data, "");
    }
    const photo = "data:image/jpeg;base64,YQ==";
    const created = await call("/v1/customers/shared/visits", { method: "POST", token, body: { ...valid, photo_data: photo } });
    assert.equal(created.status, 201);
    const path = `/v1/customers/shared/visits/${created.body.visit_id}`;
    const preserved = await call(path, { method: "PUT", token, body: { ...valid, alk: "", cal: null } });
    assert.equal(preserved.body.photo_data, photo);
    assert.equal(preserved.body.alk, null);
    assert.equal(preserved.body.cal, null);
    assert.equal(preserved.body.visited_at, created.body.visited_at);
    assert.equal(preserved.body.visit_id, created.body.visit_id);
    assert.equal((await call("/v1/customers/shared/visits", { token })).body.find(row => row.visit_id === created.body.visit_id).photo_data, photo);
    const removed = await call(path, { method: "PUT", token, body: { ...valid, photo_data: "" } });
    assert.equal(removed.body.photo_data, "");
  });
});

test("SQLite: photo formats/limits and actual 700KB request size are enforced", async () => {
  await fixture(async ({ db, call, login }) => {
    const token = await login("visits-only");
    for (const photo_data of [null, 1, "https://photo.invalid", "data:image/gif;base64,YQ==", "data:image/png;base64,@@", "data:image/png;base64," + "A".repeat(600000)]) {
      assert.equal((await call("/v1/customers/shared/visits", { method: "POST", token, body: { ...valid, photo_data } })).status, 400);
    }
    const prefix = "data:image/png;base64,";
    const photo_data = prefix + "A".repeat(600000 - prefix.length);
    const created = await call("/v1/customers/shared/visits", { method: "POST", token, body: { ...valid, photo_data } });
    assert.equal(created.status, 201);
    assert.equal(created.body.photo_data.length, 600000);
    const path = `/v1/customers/shared/visits/${created.body.visit_id}`;
    assert.equal((await call(path, { method: "PUT", token, body: { ...valid, photo_data: "invalid" } })).status, 400);
    for (const [method, url] of [["POST", "/v1/customers/shared/visits"], ["PUT", path]]) {
      assert.equal((await call(url, { method, token, body: { ...valid, unused: "a".repeat(700 * 1024) } })).status, 413);
    }
    assert.equal(db.prepare("SELECT photo_data FROM customer_visits").get().photo_data, photo_data);
  });
});

test("SQLite: backup restores nulls/photos and old numeric/no-photo visits without loss", async () => {
  await fixture(async ({ call, login }) => {
    const token = await login("company-one");
    const photo_data = "data:image/png;base64,YQ==";
    const created = await call("/v1/customers/shared/visits", { method: "POST", token, body: { ...valid, alk: null, cal: "", photo_data } });
    const backup = (await call("/v1/backup", { token })).body;
    assert.equal(backup.customer_visits[0].alk, null);
    assert.equal(backup.customer_visits[0].photo_data, photo_data);
    assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: backup })).status, 200);
    assert.deepEqual((await call("/v1/customers/shared/visits", { token })).body, [created.body]);
    for (const invalidPhoto of [null, "invalid", "data:image/png;base64," + "A".repeat(600000)]) {
      const invalid = structuredClone(backup);
      invalid.customer_visits[0].photo_data = invalidPhoto;
      assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: invalid })).status, 400);
      assert.deepEqual((await call("/v1/customers/shared/visits", { token })).body, [created.body]);
    }
    const old = structuredClone(backup);
    delete old.customer_visits[0].photo_data;
    old.customer_visits[0].alk = 100;
    old.customer_visits[0].cal = 200;
    assert.equal((await call("/v1/backup/restore", { method: "POST", token, body: old })).status, 200);
    assert.deepEqual((await call("/v1/customers/shared/visits", { token })).body, [{ ...created.body, alk: 100, cal: 200, photo_data: "" }]);
  });
});

for (const options of [{ oldVisits: true }, { oldVisits: true, existingPhoto: true }, { nullableVisits: true }]) {
  test(`SQLite: migrates existing visits without losing rows or indexes ${JSON.stringify(options)}`, async () => {
    await fixture(async ({ db, call, login }) => {
      const token = await login("visits-only");
      const rows = await call("/v1/customers/shared/visits", { token });
      assert.equal(rows.status, 200);
      assert.equal(rows.body[0].visit_id, 19);
      assert.equal(rows.body[0].notes, "Preserve me");
      assert.equal(rows.body[0].visited_at, "2026-10-26T14:00:00.000Z");
      assert.equal(rows.body[0].alk, 100);
      assert.equal(rows.body[0].photo_data, options.existingPhoto ? "data:image/png;base64,YQ==" : "");
      const columns = db.prepare("PRAGMA table_info(customer_visits)").all();
      for (const name of ["alk", "cal"]) assert.equal(columns.find(column => column.name === name).notnull, 0);
      assert.ok(db.prepare("SELECT name FROM sqlite_master WHERE name = 'custom_visit_notes_idx'").get());
      assert.ok(db.prepare("SELECT name FROM sqlite_master WHERE name = 'customer_visits_customer_idx'").get());
      assert.deepEqual(db.prepare("PRAGMA foreign_key_check").all(), []);
      const updated = await call("/v1/customers/shared/visits/19", { method: "PUT", token, body: { ...valid, alk: null, cal: null } });
      assert.equal(updated.status, 200);
      assert.equal(updated.body.alk, null);
      assert.equal(updated.body.cal, null);
      assert.equal(updated.body.photo_data, rows.body[0].photo_data);
    }, options);
  });
}

test("SQLite: failed table rebuild rolls back all visits/indexes and retries safely", async () => {
  await fixture(async ({ db, call, login }) => {
    const errors = [];
    const originalError = console.error;
    console.error = (...args) => errors.push(args);
    try {
      const failed = await call("/v1/auth/login", {
        method: "POST", body: { username: "visits-only", password }
      });
      assert.equal(failed.status, 500);
      assert.equal(errors.length, 1);
    } finally {
      console.error = originalError;
    }
    assert.equal(db.prepare("SELECT notes FROM customer_visits WHERE visit_id = 19").get().notes, "Preserve me");
    assert.equal(db.prepare("PRAGMA table_info(customer_visits)").all().find(column => column.name === "alk").notnull, 1);
    assert.ok(db.prepare("SELECT name FROM sqlite_master WHERE name = 'custom_visit_notes_idx'").get());
    assert.equal(db.prepare("SELECT name FROM sqlite_master WHERE name = 'customer_visits_migrating'").get(), undefined);
    const token = await login("visits-only");
    const rows = await call("/v1/customers/shared/visits", { token });
    assert.equal(rows.status, 200);
    assert.equal(rows.body[0].visit_id, 19);
    assert.equal(rows.body[0].photo_data, "");
  }, { oldVisits: true, failMigrationOnce: true });
});
