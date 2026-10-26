// Replay with any existing Playwright page; no package dependencies added.
// Start quotes-fixture-server.mjs. In the integrated browser:
// const source=await page.evaluate(()=>fetch("/__browser-tests.js").then(r=>r.text()));
// return await new Function("page",source+"\nreturn runQuoteBrowserChecks(page)")(page);
async function runQuoteBrowserChecks(page) {
  const evidence = [];
  const check = (v, text) => { if (!v) throw new Error(text); evidence.push(text); };
  async function login(user) {
    await page.goto("http://127.0.0.1:8801");
    if (await page.locator("#logout").isVisible()) await page.locator("#logout").click();
    await page.locator("#u").fill(user); await page.locator("#p").fill("synthetic");
    await page.locator("#lg").click();
  }
  async function alice() {
    await page.locator('[data-qc="1"]').click();
    await page.locator("#qdate").waitFor();
  }
  async function save() {
    await page.locator("#qsave").click();
    await page.locator("#qpdf:not([disabled])").waitFor();
  }
  async function state() { return page.evaluate(() => fetch("/__state").then(r => r.json())); }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(async () => { localStorage.clear(); await fetch("/__reset", { method: "POST" }); });
  await login("quoteonly"); await page.locator("#qq").waitFor();
  check(await page.locator('#nav [data-go="quotes"]').count() === 1, "quote-only default landing and navigation");
  await page.locator("#menuBtn").click(); await page.locator('#nav [data-star="quotes"]').click();
  check(await page.locator('#quick [data-go="quotes"]').count() === 1, "permission-filtered favorite quickbar");
  await page.evaluate(() => document.getElementById("drawer").classList.remove("open"));
  await page.locator("#qd").selectOption("Tuesday");
  check(await page.locator("[data-qc]").count() === 1 && await page.locator('[data-qc="2"]').count() === 1, "day filter");
  await page.locator("#qd").selectOption(""); await page.locator("#qq").fill("Alice");
  check(await page.locator("[data-qc]").count() === 1, "customer search");
  await alice();
  check(await page.locator("#qpdf").isDisabled(), "unsaved PDF disabled");
  await page.locator("#qld0").fill("10/26/2026");
  await page.locator("#qls0").fill("PUMP MOTOR <synthetic>");
  await page.locator("#qla0").fill("120");
  await page.locator("#qadd").click();
  await page.locator("#qlt1").selectOption("Payment");
  await page.locator("#qls1").fill("Deposit"); await page.locator("#qla1").fill("20");
  await page.locator("#qnotes").fill("Synthetic notes");
  check((await page.locator("#qtotal").innerText()).includes("-$100.00"), "shared CREDIT-positive/DEBIT-negative total");
  await save();
  check(await page.locator("#qconvert").count() === 0, "quote-only cannot convert");
  let s = await state(), q = s.quotes.at(-1), qid = q.quote_id;
  check(q.quote_number.startsWith("SQ-") && q.lines[0].amount === -120 && q.lines[1].amount === 20, "server number and statement-compatible signed payload");
  check(s.statements.length === 0, "save never writes statements");
  await page.locator("#qls0").fill("Edited motor"); await page.locator('[data-qremove="1"]').click();
  check(await page.locator("#qpdf").isDisabled(), "dirty PDF disabled");
  await save();
  s = await state(); check(s.quotes.find(q => q.quote_id === qid).lines.length === 1, "edit existing line and remove row persist");
  await page.evaluate(() => {
    window.__quotePrint = null;
    window.print = () => {
      window.__quotePrint = { title: document.title, html: document.getElementById("printarea").innerHTML };
      window.dispatchEvent(new Event("afterprint"));
    };
  });
  const before = await page.title();
  await page.locator("#qpdf").click();
  await page.waitForFunction(() => !!window.__quotePrint);
  const print = await page.evaluate(() => window.__quotePrint);
  check(print.title === "ALPHA_ALICE_SALES_QUOTATION_" + q.quote_number, "PDF filename includes server number");
  check(print.html.includes("Edited motor") && print.html.includes("Forms of Payment") && print.html.includes("payment in advance") &&
    print.html.includes("Synthetic Pool Co") && print.html.includes("Company logo") && print.html.includes("-$120.00"), "PDF content, settings logo, terms and signed total");
  check(await page.title() === before, "print title restored afterprint");
  await page.reload(); await page.locator("#qq").waitFor(); await alice();
  await page.locator('[data-qid="' + qid + '"]').click(); await page.locator("#qpdf:not([disabled])").waitFor();
  check(await page.locator("#qls0").inputValue() === "Edited motor", "saved edit survives reload");
  await page.locator("#qnew").click(); await page.locator("#qla0").fill("5"); await save();
  s = await state(); const second = s.quotes.at(-1).quote_id;
  check(await page.locator("[data-qid]").count() >= 2, "multiple quotes per customer");
  page.once("dialog", d => d.dismiss()); await page.locator("#qdelete").click();
  check((await state()).quotes.some(q => q.quote_id === second), "delete cancel preserves quote");
  page.once("dialog", d => d.accept()); await page.locator("#qdelete").click();
  await page.locator("#qpdf[disabled]").waitFor();
  check(!(await state()).quotes.some(q => q.quote_id === second), "confirmed delete removes quote");
  await login("editor"); await page.locator("#qq").waitFor(); await alice();
  await page.locator('[data-qid="' + qid + '"]').click(); await page.locator("#qconvert").waitFor();
  await page.locator("#qnotes").fill("Unsaved conversion guard");
  check(await page.locator("#qconvert").isDisabled() && await page.locator("#qpdf").isDisabled() &&
    (await page.locator("#qstate").innerText()).includes("Save quote first"), "dirty conversion and PDF explicitly require save");
  await save();
  page.once("dialog", d => d.dismiss()); await page.locator("#qconvert").click();
  check(!(await state()).quotes.find(q => q.quote_id === qid).converted_at, "conversion cancel preserves quote");
  page.once("dialog", d => d.accept()); await page.locator("#qconvert").click();
  await page.locator("#qfields[disabled]").waitFor();
  check(await page.locator("#qsave,#qdelete,#qconvert").count() === 0 && !await page.locator("#qpdf").isDisabled(), "converted quote locked; PDF still available");
  s = await state(); check(s.statements.length === 1 && !!s.quotes.find(q => q.quote_id === qid).converted_at, "explicit conversion appends once");
  await page.reload(); await page.locator("#qq").waitFor(); await alice();
  await page.locator('[data-qid="' + qid + '"]').click(); await page.locator("#qfields[disabled]").waitFor();
  check(await page.locator("#qdelete").count() === 0, "conversion lock survives reload");
  await page.evaluate(() => document.documentElement.style.fontSize = "24px");
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "390px layout has no horizontal overflow at 150% text");
  await login("noperm"); await page.locator("#q").waitFor();
  check(await page.locator('[data-go="quotes"]').count() === 0, "no-permission navigation and quickbar hidden");
  const requestsBefore = (await state()).log.filter(l => l.path.includes("/quotes")).length;
  await page.evaluate(() => go("quote", { id: "1" }));
  check((await page.locator("#app").innerText()).includes("not permitted"), "direct view permission guard");
  check((await state()).log.filter(l => l.path.includes("/quotes")).length === requestsBefore, "guard issues no quotation API requests");
  return evidence;
}

async function runQuoteLongPrintChecks(page) {
  await page.emulateMedia({ media: null });
  await page.setViewportSize({ width: 816, height: 1056 });
  await page.goto("http://127.0.0.1:8801");
  if (await page.locator("#logout").isVisible()) await page.locator("#logout").click();
  await page.locator("#u").fill("boss"); await page.locator("#p").fill("synthetic"); await page.locator("#lg").click();
  await page.locator("#q").waitFor();
  const seeded = await page.evaluate(async () => {
    const notes = ("LONG NOTES <escaped> " + "n".repeat(100) + "\n").repeat(140).slice(0, 16000);
    const terms = ("LONG TERMS — advance payment " + "t".repeat(100) + "\n\n").repeat(140).slice(0, 16000);
    const body = { quote_date: "10/26/2026", notes, terms, lines: Array.from({ length: 200 }, (_, i) => ({
      date: "10/26/2026", type: "Service", description: "ROW " + i + " " + "Long description ".repeat(20), amount: -1.01,
    })) };
    const r = await fetch("/v1/customers/1/quotes", { method: "POST", headers: { Authorization: "Bearer boss", "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (!r.ok) throw new Error("Could not seed long quote");
    return r.json();
  });
  await page.evaluate(qid => go("quote", { id: "1", qid }), seeded.quote_id);
  await page.locator("#qpdf:not([disabled])").waitFor();
  await page.evaluate(() => { window.__quotePrinted = false; window.print = () => { window.__quotePrinted = true; window.dispatchEvent(new Event("afterprint")); }; });
  await page.locator("#qpdf").click(); await page.waitForFunction(() => window.__quotePrinted);
  await page.emulateMedia({ media: "print" });
  const geometry = await page.evaluate(expected => {
    const root = document.getElementById("printarea"), nodes = [".sq-top", "h1", ".sq-box", ".sq-notes", "table", ".sq-total", ".sq-terms", ".sq-pay"].map(s => root.querySelector(s));
    const rects = nodes.map(n => { const r = n.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, width: r.width }; });
    const rows = [...root.querySelectorAll("tbody tr")];
    return {
      blocksInOrder: rects.every((r, i) => !i || r.top >= rects[i - 1].bottom - 1),
      rowsInOrder: rows.every((r, i) => !i || r.getBoundingClientRect().top >= rows[i - 1].getBoundingClientRect().bottom - 1),
      rows: rows.length, notes: root.querySelector(".sq-notes").textContent.length,
      terms: root.querySelector(".sq-terms").textContent.length, height: root.getBoundingClientRect().height,
      termsMatch: root.querySelector(".sq-terms").textContent === expected.replace(/\n\n/g, ""),
      header: getComputedStyle(root.querySelector("thead")).display,
      total: root.querySelector(".sq-total").textContent,
      titleRestored: !document.title.includes("_SALES_QUOTATION_"),
      safeEscaping: !root.querySelector(".sq-notes escaped"),
    };
  }, seeded.terms);
  if (!geometry.blocksInOrder || !geometry.rowsInOrder || geometry.rows !== 200 || geometry.notes < 16000 || !geometry.termsMatch ||
      geometry.height < 5000 || geometry.header !== "table-header-group" || !geometry.total.includes("-$202.00") || !geometry.titleRestored || !geometry.safeEscaping)
    throw new Error("Long print failed: " + JSON.stringify(geometry));
  await page.emulateMedia({ media: null });
  return geometry;
}
