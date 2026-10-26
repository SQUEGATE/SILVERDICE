import test from "node:test";
import assert from "node:assert/strict";
import { createStatementReaderFixture } from "./fixtures/statement-reader-fixture-server.mjs";
import { syntheticPages } from "./fixtures/statement-reader-synthetic.mjs";
import { extractStatementRows } from "../public/statement-reader.mjs";

test("synthetic UI API contract: permission boundaries, selected-only import, negative persisted result and same-PDF audit", async t => {
  const server = createStatementReaderFixture();
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = "http://127.0.0.1:" + server.address().port;
  async function req(path, method = "GET", body, token = "editor") {
    const response = await fetch(base + path, { method, headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
    return { status: response.status, body: await response.json() };
  }
  const raw = { document_hash: "a".repeat(64), rows: extractStatementRows(syntheticPages()).rows };
  assert.equal((await req("/v1/statement-reader/preview", "POST", raw, "")).status, 401);
  assert.equal((await req("/v1/statement-reader/preview", "POST", raw, "noperm")).status, 403);
  const preview = (await req("/v1/statement-reader/preview", "POST", raw, "reader")).body;
  assert.equal(preview.year, 2026);
  assert.deepEqual(preview.rows.map(r => r.status), ["ready", "ignored", "duplicate", "review"]);
  const { source_id, page, line, text, date, amount, customer_id, reference } = preview.rows[0];
  const body = { document_hash: raw.document_hash, rows: [{ source_id, page, line, text, date, amount, customer_id, reference, override_duplicate: false }] };
  assert.equal((await req("/v1/statement-reader/import", "POST", body, "reader")).status, 403);
  assert.equal((await req("/v1/statement-reader/import", "POST", body)).body.imported, 1);
  const state = (await req("/__state")).body;
  assert.equal(state.records.length, 1);
  assert.equal(state.records[0].amount, -100);
  assert.equal(state.records[0].type, "Zelle Payment");
  assert.equal((await req("/v1/statement-reader/import", "POST", body)).body.skipped, 1);
  const repeated = (await req("/v1/statement-reader/preview", "POST", raw)).body;
  assert.equal(repeated.rows[0].status, "imported");
  assert.match(repeated.rows[0].reason, /already imported/);
});
