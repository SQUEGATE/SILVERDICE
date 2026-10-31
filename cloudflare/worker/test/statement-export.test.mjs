import test from "node:test";
import assert from "node:assert/strict";
import { jsPDF } from "jspdf";
import { applyPlugin } from "jspdf-autotable";
import { getDocument } from "pdfjs-dist/legacy/build/pdf.mjs";
import { createStatementPdf, statementFilename, exportStatements } from "../public/statement-export.mjs";

applyPlugin(jsPDF);
const customer = { customer_id: "synthetic-1", first_name: "Avery", last_name: "Example", address: "123 Synthetic St", notes: "Test note", route_order: 2, service_day: "Monday" };
const settings = { companyName: "Synthetic Pools", companyAddress: "123 Test St|Sample City", showLogo: "false", showGenerationDate: "false", paymentInfo: "Check|Zelle", footerText: "Synthetic footer" };

async function textFrom(blob) {
  const task = getDocument({ data: new Uint8Array(await blob.arrayBuffer()), useSystemFonts: true });
  const pdf = await task.promise;
  try {
    const pages = [];
    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i);
      pages.push((await page.getTextContent()).items.map(item => item.str).join(" ").replace(/\s+/g, " "));
    }
    return pages;
  } finally { await task.destroy(); }
}

test("batch PDF includes full history, balance below title, footer and all pages", async () => {
  const records = Array.from({ length: 160 }, (_, index) => ({ record_date: "10/31/2026", type: "Record " + index, amount: index === 0 ? -10 : 1 }));
  const pages = await textFrom(createStatementPdf(jsPDF, customer, records, settings, "Fallback"));
  assert.ok(pages.length > 3);
  assert.match(pages[0], /STATEMENT Balance: \$149\.00/);
  const all = pages.join(" ");
  for (let i = 0; i < 160; i++) assert.ok(all.includes("RECORD " + i));
  assert.match(all, /Total: \$149\.00/);
  for (const [i, page] of pages.entries()) {
    assert.ok(page.includes("Synthetic footer"));
    assert.ok(page.includes("Page " + (i + 1) + " of " + pages.length));
  }
});

test("PDF balances handle empty history, negative amounts, and reject unavailable amounts", async () => {
  assert.match((await textFrom(createStatementPdf(jsPDF, customer, [], settings))).join(" "), /Balance: \$0\.00/);
  assert.match((await textFrom(createStatementPdf(jsPDF, customer, [{ record_date: "10/31/2026", type: "Payment", amount: -25 }], settings))).join(" "), /Balance: \(\$25\.00\)/);
  assert.throws(() => createStatementPdf(jsPDF, customer, [{ amount: null }], settings), /unavailable/);
  assert.throws(() => createStatementPdf(jsPDF, customer, [{ amount: "bad" }], settings), /unavailable/);
  assert.notEqual(statementFilename(customer, 0), statementFilename(customer, 1));
  assert.ok(!statementFilename({ ...customer, last_name: "../Example" }, 0).includes("/"));
});

test("batch folder writes actual PDFs, continues after failure, and downloads separately without folder access", async () => {
  const oldDocument = globalThis.document, oldWindow = globalThis.window;
  globalThis.window = { jspdf: { jsPDF } };
  globalThis.document = { createElement: () => ({}), head: { appendChild: script => queueMicrotask(() => script.onload()) } };
  try {
    const customers = [customer, { ...customer, customer_id: "fail" }, { ...customer, customer_id: "last" }];
    const written = new Map(), progress = [];
    const folder = { getFileHandle: async filename => ({ createWritable: async () => ({
      write: async blob => written.set(filename, blob), close: async () => {},
    }) }) };
    const getRecords = async c => { if (c.customer_id === "fail") throw new Error("Synthetic read failure"); return [{ record_date: "10/31/2026", type: "Bill", amount: 55 }]; };
    const result = await exportStatements({ customers, getRecords, settings, folder, progress: n => progress.push(n) });
    assert.equal(result.saved, 2);
    assert.equal(result.failures.length, 1);
    assert.match(result.failures[0].error, /Synthetic read failure/);
    assert.deepEqual(progress, [1, 2, 3]);
    for (const blob of written.values()) assert.match((await textFrom(blob)).join(" "), /Balance: \$55\.00/);
    const downloads = [];
    const downloaded = await exportStatements({ customers: [customer], getRecords, settings, folder: null, progress: () => {},
      download: (blob, filename) => downloads.push({ blob, filename }) });
    assert.equal(downloaded.saved, 1);
    assert.equal(downloads.length, 1);
    assert.equal(downloads[0].filename, statementFilename(customer, 0));
    assert.match((await textFrom(downloads[0].blob)).join(" "), /Balance: \$55\.00/);
  } finally {
    globalThis.document = oldDocument; globalThis.window = oldWindow;
  }
});
