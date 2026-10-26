import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { createHash } from "node:crypto";
import { extractStatementRows, parseStatementPdf, groupTextLines, LIMITS } from "../public/statement-reader.mjs";
import { syntheticPages, makePdf, item, header } from "./fixtures/statement-reader-synthetic.mjs";

test("geometry uses additions, not ending balance; posting date, references, wraps and repeated page headers", () => {
  const { rows } = extractStatementRows(syntheticPages(), { currentYear: 2030 });
  assert.equal(rows.length, 4);
  assert.equal(rows[0].deposit_text, "100.00");
  assert.equal(rows[0].date_text, "9/8");
  assert.equal(rows[0].reference, "Syn123");
  assert.match(rows[0].text, /Wrapped selectable/);
  assert.equal(rows[1].direction, "withdrawal");
  assert.equal(rows[1].deposit_text, "");
  assert.equal(rows[2].deposit_text, "125.00");
  assert.equal(rows[3].deposit_text, "75.00");
  assert.equal(rows[3].source_id.split(":")[0], "2");
  assert.deepEqual(rows, extractStatementRows(syntheticPages()).rows);
});
test("large withdrawal numeric right edge never mistaken for deposit despite starting left", () => {
  const rows = extractStatementRows([{ items: [...header(), item("9/1", 61, 670), item("Fictional account transfer", 160, 670),
    item("123,456,789.00", 440, 670, 74), item("100.00", 541, 670, 30)] }]).rows;
  assert.equal(rows[0].direction, "withdrawal");
  assert.equal(rows[0].deposit_text, "");
});
test("description continuation starting a date is not a new posting row", () => {
  const rows = extractStatementRows([{ items: [...header(), item("9/8", 61, 670), item("Zelle From Demo Avery on", 160, 670),
    item("100.00", 420, 670, 30), item("500.00", 541, 670, 30), item("09/04 Ref # Syn987", 160, 658)] }]).rows;
  assert.equal(rows.length, 1); assert.equal(rows[0].date_text, "9/8"); assert.equal(rows[0].reference, "Syn987");
});
test("fallback single dollar/incoming numeric only; ambiguous money blank; outgoing never deposit", () => {
  const pages = [{ items: [
    item("9/1 Fiction Avery $100.00", 42, 700),
    item("9/2 Zelle From Fiction Avery 120.00", 42, 680),
    item("9/3 Zelle From Fiction Avery $100.00 $500.00", 42, 660),
    item("9/4 Zelle To Fiction Avery $100.00", 42, 640),
    item("9/5 Withdrawal Fiction Avery $100.00", 42, 620),
    item("9/6 Fiction Avery 100.00", 42, 600),
    item("9/7 Zelle From Fiction Avery", 42, 580), item("$100.00", 200, 568),
  ] }];
  const rows = extractStatementRows(pages).rows;
  assert.equal(rows[0].deposit_text, "$100.00"); assert.equal(rows[0].direction, "deposit");
  assert.equal(rows[1].deposit_text, "120.00");
  assert.equal(rows[2].direction, "deposit"); assert.equal(rows[2].deposit_text, "");
  assert.equal(rows[3].direction, "withdrawal"); assert.equal(rows[4].direction, "withdrawal");
  assert.equal(rows[5].direction, "unknown");
  assert.equal(rows[6].direction, "deposit"); assert.equal(rows[6].deposit_text, "");
});
test("empty selectable PDF/no transactions, scans, limits, clipped fields and stable geometry grouping", () => {
  assert.deepEqual(extractStatementRows([{ items: [item("Synthetic account summary", 42, 700)] }]).rows, []);
  assert.throws(() => extractStatementRows([{ items: [] }]), /Scanned\/image PDF needs selectable text/);
  assert.throws(() => extractStatementRows(Array(101).fill([])), /100-page/);
  assert.throws(() => extractStatementRows([{ items: Array.from({ length: 1001 }, (_, i) => item("9/1 Zelle From Fiction Avery $10.00", 42, i * 10)) }]), /1,000-transaction/);
  const rows = extractStatementRows([{ items: [item("9/1 Zelle From " + "x".repeat(5000) + " $10.00", 42, 700)] }]).rows;
  assert.equal(rows[0].text.length, 4000);
  assert.equal(groupTextLines([item("B", 100, 700), item("A", 42, 701)])[0].text, "A B");
});
test("real PDF.js parses generated selectable PDF locally and hashes original bytes independent of filename", async () => {
  const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
  const bytes = makePdf(), file = new File([bytes], "synthetic-only.pdf", { type: "application/pdf" });
  // The production worker URL is browser-relative. PDF.js' Node fake worker uses its own local module.
  globalThis.pdfjsWorker = await import("pdfjs-dist/legacy/build/pdf.worker.mjs");
  const parsed = await parseStatementPdf(file, { pdfjs, currentYear: 2031 });
  assert.equal(parsed.document_hash, createHash("sha256").update(bytes).digest("hex"));
  assert.equal(parsed.currentYear, 2031); assert.equal(parsed.rows.length, 4);
  assert.equal(parsed.rows[0].date_text, "9/8");
  assert.equal(parsed.rows[0].deposit_text, "100.00");
  assert.equal(parsed.rows[1].direction, "withdrawal");
  assert.equal(parsed.rows[3].deposit_text, "75.00");
  const renamed = await parseStatementPdf(new File([bytes], "different.pdf"), { pdfjs });
  assert.equal(renamed.document_hash, parsed.document_hash);
  await assert.rejects(parseStatementPdf(new File([makePdf([{ items: [] }])], "scan.pdf"), { pdfjs }), /Scanned\/image/);
});
test("file validation rejects oversized, empty, invalid and stale uploads", async () => {
  await assert.rejects(parseStatementPdf({ size: LIMITS.bytes + 1 }), /25 MB/);
  await assert.rejects(parseStatementPdf(new File([], "empty.pdf")), /non-empty/);
  await assert.rejects(parseStatementPdf(new File(["not pdf"], "fake.pdf")), /not a PDF/);
  await assert.rejects(parseStatementPdf(new File([makePdf()], "cancel.pdf"), { isCurrent: () => false }), /cancelled/);
});
test("actual inline UI compiles; access is opt-in, default landing eligible and direct guard blocks API", async () => {
  const html = readFileSync(new URL("../public/index.html", import.meta.url), "utf8");
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, { innerHTML: "", classList: { toggle() {}, remove() {} }, querySelectorAll: () => [], addEventListener() {} });
    return elements.get(id);
  };
  const c = vm.createContext({
    localStorage: { getItem: () => null }, document: { getElementById: element, addEventListener() {} },
    window: { addEventListener() {}, scrollTo() {} }, console, clearInterval() {},
  });
  vm.runInContext(html.match(/<script>([\s\S]*)<\/script>/)[1], c);
  c.S.account = { role: "EMPLOYEE", permissions: {} };
  let calls = 0; c.api = async () => { calls++; return []; };
  c.go("reader"); assert.match(c.app.innerHTML, /not permitted/); assert.equal(calls, 0);
  await assert.rejects(c.vStatementReader(), /not permitted/);
  assert.ok(c.PERMS.some(p => p[0] === "canAccessStatementReader"));
  assert.equal(c.VIEWS.find(v => v.id === "reader").perm(), false);
  c.S.account.permissions.canAccessStatementReader = true;
  assert.equal(c.VIEWS.filter(v => v.perm())[0].id, "reader");
  assert.equal(c.can("canEditStatements"), false);
  c.S.account = { role: "COMPANY" };
  assert.equal(c.VIEWS.find(v => v.id === "reader").perm(), true);
});
