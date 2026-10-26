import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import worker from "../src/index.js";

const secret = "test-only-session-signing-secret";
const env = {
  SESSION_SIGNING_SECRET: secret,
  SILVERDICE_URL: "https://unit-test.invalid",
  SILVERDICE_AUTH_TOKEN: "test-only-database-token-value"
};
const customer = {
  customer_id: "c-1",
  first_name: "Test",
  last_name: "Customer",
  service_day: "Monday"
};
const savedVisit = {
  visit_id: 7,
  customer_id: "c-1",
  visited_at: "2026-10-26T14:00:00.000Z",
  ph: "7.4",
  chlo: "2-4",
  stb: "80",
  alk: 100,
  cal: 200,
  notes: "Routine service",
  photo_data: ""
};

let originalFetch;
let executedSql;
let employeePasswordHash;

function tursoCell(value) {
  if (value === null || value === undefined) return { type: "null" };
  if (typeof value === "number" && Number.isInteger(value)) return { type: "integer", value: String(value) };
  if (typeof value === "number") return { type: "float", value };
  return { type: "text", value: String(value) };
}

function executeResult(rows) {
  const cols = rows.length ? Object.keys(rows[0]).map(name => ({ name })) : [];
  return {
    type: "ok",
    response: {
      result: {
        cols,
        rows: rows.map(row => cols.map(column => tursoCell(row[column.name])))
      }
    }
  };
}

function decodeArgs(args = []) {
  return args.map(arg => arg.type === "integer" || arg.type === "float" ? Number(arg.value) : arg.type === "null" ? null : arg.value);
}

function mockDatabase() {
  executedSql = [];
  globalThis.fetch = async (_url, options) => {
    const pipeline = JSON.parse(options.body);
    const first = pipeline.requests[0];
    if (first.type === "batch") {
      return Response.json({
        results: [{
          type: "ok",
          response: { result: { step_results: first.batch.steps.map(() => ({})), step_errors: [] } }
        }]
      });
    }
    const { sql, args: encodedArgs } = first.stmt;
    const args = decodeArgs(encodedArgs);
    executedSql.push({ sql, args });
    let rows = [];
    if (sql.startsWith("SELECT a.account_id")) {
      rows = [{
        account_id: 3,
        username: "test-employee",
        password_hash: employeePasswordHash,
        role: "EMPLOYEE",
        company_id: 44,
        employee_id: 3,
        first_name: "Test",
        last_name: "Employee",
        phone: "",
        email: "",
        permissions_json: "{}",
        allowed_customer_ids_json: "[]",
        allowed_days_json: "[]",
        company_name: "Test Company"
      }];
    } else if (sql.startsWith("SELECT a.employee_id")) {
      rows = [{
        employee_id: 3,
        company_id: 44,
        username: "test-employee",
        first_name: "Test",
        last_name: "Employee",
        phone: "",
        email: "",
        permissions_json: "{}",
        allowed_customer_ids_json: "[]",
        allowed_days_json: "[]",
        company_name: "Test Company"
      }];
    } else if (sql.startsWith("SELECT employee_id, company_id")) {
      rows = [{
        employee_id: 3,
        company_id: 44,
        first_name: "Test",
        last_name: "Employee",
        phone: "",
        email: "",
        username: "test-employee",
        permissions_json: "{}",
        allowed_customer_ids_json: "[]",
        allowed_days_json: "[]"
      }];
    } else if (/SELECT 1 AS active FROM accounts/.test(sql) || /SELECT 1 AS active FROM companies/.test(sql)) {
      rows = [{ active: 1 }];
    } else if (sql.startsWith("SELECT customer_id, first_name")) {
      if (sql.includes("customer_id = ?")) {
        if (args[0] === 44 && args[1] === "c-1" && !args.includes("blocked-customer")) rows = [customer];
      } else {
        rows = [customer];
      }
    } else if (sql.startsWith("SELECT visit_id, customer_id")) {
      rows = [savedVisit];
    } else if (sql.startsWith("INSERT INTO customer_visits")) {
      rows = [{
        visit_id: 8,
        customer_id: args[2],
        visited_at: args[3],
        ph: args[4],
        chlo: args[5],
        stb: args[6],
        alk: args[7],
        cal: args[8],
        notes: args[9],
        photo_data: args[10]
      }];
    } else if (sql.startsWith("UPDATE customer_visits")) {
      if (args[7] === 44 && args[8] === "c-1" && args[9] === savedVisit.visit_id) {
        rows = [{ ...savedVisit, ph: args[0], chlo: args[1], stb: args[2], alk: args[3], cal: args[4], notes: args[5], photo_data: args[6] ?? savedVisit.photo_data }];
      }
    }
    return Response.json({ results: [executeResult(rows)] });
  };
}

async function tokenFor({
  role = "EMPLOYEE",
  permissions = { canAccessVisits: true },
  companyId = 44,
  employeeId = 3,
  allowedCustomerIds = [],
  allowedDays = []
} = {}) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  const header = encode({ alg: "HS256", typ: "JWT" });
  const payload = encode({
    sub: role === "COMPANY" ? "4" : "3",
    username: role === "COMPANY" ? "test-company" : "test-employee",
    role,
    companyId,
    employeeId: role === "EMPLOYEE" ? employeeId : null,
    permissions,
    allowedCustomerIds,
    allowedDays,
    exp: Math.floor(Date.now() / 1000) + 3600
  });
  const data = `${header}.${payload}`;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return `${data}.${Buffer.from(signature).toString("base64url")}`;
}

async function hashTestPassword(password) {
  const salt = Buffer.from("salt-for-test-123");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: 10000, hash: "SHA-256" }, key, 256);
  return `pbkdf2-sha256$10000$${salt.toString("base64url")}$${Buffer.from(bits).toString("base64url")}`;
}

async function call(path, { method = "GET", body, token } = {}) {
  const authorization = token || await tokenFor();
  return worker.fetch(new Request(`https://worker.test${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${authorization}`,
      ...(body === undefined ? {} : { "Content-Type": "application/json" })
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  }), env);
}

afterEach(() => {
  if (originalFetch) globalThis.fetch = originalFetch;
  originalFetch = undefined;
});

test("creates migration table, lists visits, and supports employee visit permission", async () => {
  originalFetch = globalThis.fetch;
  mockDatabase();
  const response = await call("/v1/customers/c-1/visits");
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), [savedVisit]);
  assert.ok(executedSql.some(({ sql }) => sql.startsWith("CREATE TABLE IF NOT EXISTS customer_visits")));
  assert.ok(executedSql.some(({ sql }) => sql.includes("company_id = ? AND customer_id = ?")));
  assert.ok(executedSql.some(({ sql }) => sql.includes("ORDER BY visited_at DESC, visit_id DESC")));
});

test("allows canAccessVisits to search customers and denies employees unless opted in", async () => {
  originalFetch = globalThis.fetch;
  mockDatabase();
  const allowed = await call("/v1/customers?q=Test");
  assert.equal(allowed.status, 200);
  assert.equal((await allowed.json())[0].customer_id, customer.customer_id);

  const token = await tokenFor({ permissions: {} });
  const denied = await call("/v1/customers", { token });
  assert.equal(denied.status, 403);
});

test("defaults canAccessVisits to false in employee response, login, and preview", async () => {
  originalFetch = globalThis.fetch;
  employeePasswordHash = await hashTestPassword("test-password");
  mockDatabase();

  const login = await call("/v1/auth/login", {
    method: "POST",
    body: { username: "test-employee", password: "test-password" }
  });
  assert.equal(login.status, 200);
  assert.equal((await login.json()).account.permissions.canAccessVisits, false);

  const employees = await call("/v1/admin/companies/44/employees", { token: await tokenFor({ role: "COMPANY" }) });
  assert.equal(employees.status, 200);
  assert.equal((await employees.json())[0].permissions.canAccessVisits, false);

  const preview = await call("/v1/companies/employees/3/session", {
    method: "POST",
    token: await tokenFor({ role: "COMPANY" })
  });
  assert.equal(preview.status, 200);
  const previewData = await preview.json();
  const claims = JSON.parse(Buffer.from(previewData.token.split(".")[1], "base64url").toString());
  assert.equal(claims.permissions.canAccessVisits, false);
});

test("creates visits with server timestamps and updates without changing visited_at", async () => {
  originalFetch = globalThis.fetch;
  mockDatabase();
  const visitBody = { ph: "7.6", chlo: "3-6", stb: "70", alk: 120, cal: 220, notes: "Follow-up" };
  const createdResponse = await call("/v1/customers/c-1/visits", { method: "POST", body: visitBody });
  assert.equal(createdResponse.status, 201);
  const created = await createdResponse.json();
  assert.equal(created.visit_id, 8);
  assert.equal(created.customer_id, "c-1");
  assert.equal(created.ph, "7.6");
  assert.equal(new Date(created.visited_at).toISOString(), created.visited_at);
  assert.ok(!("company_id" in created));

  const updateBody = { ...visitBody, notes: "Edited notes" };
  const updatedResponse = await call("/v1/customers/c-1/visits/7", { method: "PUT", body: updateBody });
  assert.equal(updatedResponse.status, 200);
  const updated = await updatedResponse.json();
  assert.equal(updated.visited_at, savedVisit.visited_at);
  assert.equal(updated.notes, "Edited notes");
  const updateSql = executedSql.find(({ sql }) => sql.startsWith("UPDATE customer_visits")).sql;
  assert.ok(!updateSql.includes("visited_at ="));
});

test("rejects invalid visit fields and prevents access to another customer", async () => {
  originalFetch = globalThis.fetch;
  mockDatabase();
  const invalid = await call("/v1/customers/c-1/visits", {
    method: "POST",
    body: { ph: "7.1", chlo: "2-4", stb: "80", alk: 1, cal: 1, notes: "" }
  });
  assert.equal(invalid.status, 400);

  const badInteger = await call("/v1/customers/c-1/visits", {
    method: "POST",
    body: { ph: "7.0", chlo: "1-2", stb: "100", alk: 2147483648, cal: 1, notes: "" }
  });
  assert.equal(badInteger.status, 400);

  const inaccessible = await call("/v1/customers/blocked-customer/visits");
  assert.equal(inaccessible.status, 404);
  assert.ok(!executedSql.some(({ sql, args }) => sql.startsWith("SELECT visit_id") && args.includes("blocked-customer")));
});

test("company accounts can access visits", async () => {
  originalFetch = globalThis.fetch;
  mockDatabase();
  const response = await call("/v1/customers/c-1/visits", { token: await tokenFor({ role: "COMPANY" }) });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), [savedVisit]);
});

test("backs up visits, accepts legacy backups, and validates restored visits", async () => {
  originalFetch = globalThis.fetch;
  mockDatabase();
  const exported = await call("/v1/backup", { token: await tokenFor({ role: "COMPANY" }) });
  assert.equal(exported.status, 200);
  assert.deepEqual((await exported.json()).customer_visits, [savedVisit]);

  const legacyBackup = {
    format: "comp-manager-backup",
    version: 1,
    company_id: 44,
    customers: [],
    statement_records: [],
    record_types: [],
    pdf_settings: []
  };
  const restoredLegacy = await call("/v1/backup/restore", {
    method: "POST",
    token: await tokenFor({ role: "COMPANY" }),
    body: legacyBackup
  });
  assert.equal(restoredLegacy.status, 200);
  assert.equal((await restoredLegacy.json()).visits, 0);

  const invalidBackup = {
    ...legacyBackup,
    customers: [{ customer_id: "c-1" }],
    customer_visits: [{ ...savedVisit, customer_id: "missing-customer" }]
  };
  const rejected = await call("/v1/backup/restore", {
    method: "POST",
    token: await tokenFor({ role: "COMPANY" }),
    body: invalidBackup
  });
  assert.equal(rejected.status, 400);
});

test("deletes visits with their customer or company", async () => {
  originalFetch = globalThis.fetch;
  mockDatabase();
  const deletedCustomer = await call("/v1/customers/c-1", {
    method: "DELETE",
    token: await tokenFor({ permissions: { canEditCustomers: true } })
  });
  assert.equal(deletedCustomer.status, 200);
  assert.ok(executedSql.some(({ sql, args }) => sql.startsWith("DELETE FROM customer_visits") && args[1] === "c-1"));

  const deletedCompany = await call("/v1/admin/companies/44", {
    method: "DELETE",
    token: await tokenFor({ role: "MASTER" })
  });
  assert.equal(deletedCompany.status, 200);
  assert.ok(executedSql.some(({ sql }) => sql === "DELETE FROM customer_visits WHERE company_id = ?"));
});
