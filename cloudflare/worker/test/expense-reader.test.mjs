import assert from "node:assert/strict";
import { test } from "node:test";
import { normalizeKeywords, keywordPattern, lineAmount, matchExpenseLines, expenseTotals, pdfLines } from "../public/expense-reader.mjs";
import { item } from "./fixtures/statement-reader-synthetic.mjs";

const line = (text, page = 1, n = 1) => ({ page, line: n, text });

test("expenses: keyword normalization trims, dedupes case-insensitively and enforces limits", () => {
  assert.deepEqual(normalizeKeywords(["  Home   Depot ", "HOME DEPOT", "", "Lowe's"]), ["Home Depot", "Lowe's"]);
  assert.throws(() => normalizeKeywords(["x".repeat(81)]), /80 characters/);
  assert.throws(() => normalizeKeywords(Array.from({ length: 101 }, (_, i) => `k${i}`)), /100 keywords/);
});

test("expenses: whole-word, case-insensitive phrase matching with flexible spaces and literal symbols", () => {
  assert.ok(keywordPattern("Home Depot").test("01/03 home   DEPOT #123"));
  assert.ok(!keywordPattern("Depot").test("Depotting service"));
  assert.ok(keywordPattern("Lowe's").test("LOWE'S #42 12.00"));
  assert.ok(keywordPattern("A+B (inc)").test("paid a+b (INC) 3.00"));
  assert.ok(!keywordPattern("a.b").test("axb 1.00"));
});

test("expenses: first amount on the line, commas, dollar signs, negatives and parentheses as absolute cents", () => {
  assert.deepEqual(lineAmount("HOME DEPOT $45.10 1,000.00"), { text: "$45.10", cents: 4510 });
  assert.equal(lineAmount("Pool (1,200.50)").cents, 120050);
  assert.equal(lineAmount("Fee -3.05").cents, 305);
  assert.equal(lineAmount("Ref 12/31 #4455"), null);
  assert.equal(lineAmount("big 0.10").cents, 10);
});

test("expenses: each line is counted once under the first listed keyword; totals skip excluded and amountless lines", () => {
  const lines = [line("HOME DEPOT supplies 10.00", 1, 1), line("Supplies only 2.50", 1, 2),
    line("Home Depot pending", 1, 3), line("Unrelated 99.99", 2, 1)];
  const keywords = ["home depot", "supplies", "unused"];
  const matches = matchExpenseLines(lines, keywords);
  assert.deepEqual(matches.map(m => [m.keyword, m.cents]), [["home depot", 1000], ["supplies", 250], ["home depot", null]]);
  let totals = expenseTotals(matches, keywords);
  assert.equal(totals.cents, 1250);
  assert.equal(totals.count, 2);
  assert.deepEqual(totals.keywords.map(k => [k.keyword, k.count, k.cents]), [["home depot", 1, 1000], ["supplies", 1, 250], ["unused", 0, 0]]);
  matches[0].included = false;
  totals = expenseTotals(matches, keywords);
  assert.equal(totals.cents, 250);
  assert.equal(expenseTotals([{ keyword: "a", cents: 10 }, { keyword: "a", cents: 20 }], ["a"]).cents, 30);
});

test("expenses: PDF text items are grouped into lines per page; image-only PDFs are rejected", () => {
  const lines = pdfLines([{ items: [item("01/03 HOME DEPOT", 60, 700, 80), item("$45.10", 300, 701), item("Next", 60, 680)] },
    { items: [item("Page two 1.00", 60, 700)] }]);
  assert.deepEqual(lines.map(l => [l.page, l.text]), [[1, "01/03 HOME DEPOT $45.10"], [1, "Next"], [2, "Page two 1.00"]]);
  assert.throws(() => pdfLines([{ items: [] }]), /selectable text/);
});

test("expenses: web view searches only the user's checked keywords and remembers them per user on the device", async () => {
  const html = await (await import("node:fs/promises")).readFile(new URL("../public/index.html", import.meta.url), "utf8");
  assert.match(html, /selKey="cm\.expsel\."\+\(S\.account&&S\.account\.username\)/);
  assert.match(html, /matchExpenseLines\(lines,selected\(\)\)/);
  assert.match(html, /expenseTotals\(matches,selected\(\)\)/);
  assert.match(html, /Check at least one saved keyword/);
  assert.ok(!/matchExpenseLines\(lines,keywords\)/.test(html));
});
