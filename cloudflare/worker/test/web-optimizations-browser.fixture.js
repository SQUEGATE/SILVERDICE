// Replay against statement-reader-fixture-server.mjs through /__optimization-tests.js.
async function runWebOptimizationBrowserChecks(page) {
  const evidence = [];
  const check = (value, message) => { if (!value) throw new Error(message); evidence.push(message); };
  await page.evaluate(() => {
    window.optimizationCalls = [];
    const customers = Array.from({ length: 125 }, (_, i) => ({
      customer_id: String(i + 1), first_name: "Synthetic", last_name: "Customer " + (i + 1),
      service_day: i % 2 ? "Tuesday" : "Monday", amount_charged: 10,
    }));
    const statements = Array.from({ length: 75 }, (_, i) => ({
      record_id: i + 1, record_date: "10/01/2026", type: "Service", amount: 2, run: (i + 1) * 2,
    }));
    const quote = { quote_id: 900, quote_number: "SQ-000900", quote_date: "10/01/2026", converted_at: "2026-10-02",
      notes: "Converted detail only", terms: "Synthetic terms", lines: [{ date: "10/01/2026", type: "Service", amount: 2, description: "Full quote detail" }] };
    const visits = Array.from({ length: 75 }, (_, i) => ({
      visit_id: i + 1, visited_at: "2026-10-01T12:00:00Z", ph: "7.4", chlo: "2-4", stb: "60",
      alk: null, cal: null, notes: "Visit " + (i + 1), has_photo: true,
    }));
    S.account = { role: "COMPANY", companyName: "Synthetic Company", companyId: 55, username: "synthetic" };
    S.kw = { credit: ["service"], debit: ["payment"], settings: {} };
    api = async (path, opts = {}) => {
      optimizationCalls.push({ path, method: opts.method || "GET" });
      const url = new URL(path, location.origin), offset = Number(url.searchParams.get("offset") || 0);
      const paged = (rows, extra = {}) => ({ rows: rows.slice(offset, offset + 50), total: rows.length, ...extra });
      if (url.pathname === "/v1/customers") {
        const q = url.searchParams.get("q") || "", day = url.searchParams.get("service_day");
        return paged(customers.filter(c => (!q || (c.first_name + " " + c.last_name + " " + c.customer_id).toLowerCase().includes(q.toLowerCase())) && (!day || c.service_day === day)));
      }

      if (url.pathname === "/v1/customers/1") return customers[0];
      if (url.pathname === "/v1/customers/1/statements") return url.searchParams.has("limit") ? paged(statements.slice().reverse(), { balance: 150 }) : statements;
      if (url.pathname === "/v1/customers/1/quotes") return { rows: [{ quote_id: 900, quote_number: quote.quote_number, quote_date: quote.quote_date, converted_at: quote.converted_at }], total: 1 };
      if (url.pathname === "/v1/quotes/900") return quote;
      if (url.pathname === "/v1/customers/1/visits") return paged(visits);
      if (/^\/v1\/visits\/\d+$/.test(url.pathname)) return { ...visits[Number(url.pathname.split("/").pop()) - 1], photo_data: "" };
      if (url.pathname === "/v1/record-types") return ["Service"];
      if (url.pathname === "/v1/settings") return { companyName: "Latest synthetic settings" };
      if (url.pathname === "/v1/balances") return { rows: customers.map(c => ({ customer_id: c.customer_id, balance: 1 })) };
      throw new Error("Unexpected optimization fixture path " + path);
    };
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => { document.documentElement.style.fontSize = "24px"; go("customers"); });
  await page.locator("#cunext").waitFor();
  check(await page.locator("#lst tbody tr").count() === 50, "125 customers render only one 50-row page at large mobile text");
  await page.locator("#cunext").click();
  await page.waitForFunction(() => document.querySelector("#lst tbody tr")?.dataset.id === "51");
  check(await page.locator("#lst tbody tr").count() === 50, "next customer page uses offset 50");
  await page.locator("#q").fill("Customer 125");
  await page.waitForFunction(() => document.querySelector("#lst tbody tr")?.dataset.id === "125");
  check(await page.locator("#lst tbody tr").count() === 1, "customer search runs before pagination across all 125 records");
  await page.evaluate(() => go("statement", "1"));
  await page.locator("#stnext").waitFor();
  check(await page.locator(".record-table tbody tr").count() === 50, "statement ledger page bounded at 50 with full balance");
  check((await page.locator("main h2").filter({ hasText: "Balance:" }).innerText()).includes("$150.00"), "statement header retains complete balance not page sum");
  await page.locator("#stnext").click();
  await page.waitForFunction(() => document.querySelectorAll(".record-table tbody tr").length === 25);
  check((await page.locator(".record-table tbody tr").first().innerText()).includes("$50.00"), "second statement page retains chronological running balance");
  await page.evaluate(() => {
    window.optimizationPrinted = null;
    printStatement = (customer, rows, balance) => { optimizationPrinted = { count: rows.length, balance, company: S.kw.settings.companyName }; };
  });
  await page.locator("#pr").click();
  check(await page.evaluate(() => optimizationPrinted.count === 75 && optimizationPrinted.balance === 150 && optimizationPrinted.company === "Latest synthetic settings"), "PDF fetches all 75 records and fresh settings while viewing second page");
  await page.evaluate(() => go("quote", { id: "1", qid: 900 }));
  await page.locator("#qnotes").waitFor();
  check(await page.locator("#qnotes").inputValue() === "Converted detail only", "converted quote fetched in full from detail endpoint");
  check(await page.locator("#qdate").isDisabled(), "converted quote remains locked");
  await page.evaluate(() => go("visit", "1"));
  await page.locator("#vinext").waitFor();
  check(await page.locator("#visit-records tbody tr").count() === 50, "visits list uses 50 summary rows");
  check(await page.evaluate(() => !optimizationCalls.some(c => /^\/v1\/visits\//.test(c.path))), "visit photos/details not fetched until selected");
  await page.locator('[data-visit="1"]').click();
  await page.waitForFunction(() => document.getElementById("v_notes").value === "Visit 1");
  check(await page.evaluate(() => optimizationCalls.some(c => c.path === "/v1/visits/1")), "visit selection lazily fetches full detail");
  await page.locator("#vinext").click();
  await page.waitForFunction(() => document.querySelectorAll("#visit-records tbody tr").length === 25);
  check(await page.locator("#v_notes").inputValue() === "", "visit paging resets editor safely to new visit");
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "paged views retain mobile 150% text without horizontal page overflow");
  return evidence;
}

async function runBillingBrowserChecks(page) {
  const evidence = [];
  const check = (ok, message) => { if (!ok) throw new Error(message); evidence.push(message); };
  await page.goto("http://127.0.0.1:8802");
  await page.evaluate(async () => { localStorage.clear(); sessionStorage.clear(); await fetch("/__reset", { method: "POST" }); });
  await page.reload();
  await page.locator("#u").fill("company"); await page.locator("#p").fill("synthetic"); await page.locator("#lg").click();
  await page.waitForFunction(() => S.token === "company");
  await page.evaluate(() => go("monthly"));
  await page.locator("#go").waitFor();
  await page.evaluate(() => fetch("/__fail-monthly", { method: "POST" }));
  page.once("dialog", dialog => dialog.accept()); await page.locator("#go").click();
  await page.locator("#flash .err").waitFor();
  check((await page.locator("#go").innerText()).includes("Retry same"), "lost billing response exposes explicit same-request retry");
  check(await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith("cm.monthly.pending.")).length === 1), "uncertain billing request persisted locally before retry");
  await page.reload();
  await page.waitForFunction(() => S.token === "company");
  await page.evaluate(() => go("monthly")); await page.locator("#go").waitFor();
  check((await page.locator("#go").innerText()).includes("Retry same"), "refresh/reopen restores pending UUID and retry label without automatic write");
  let state = await page.evaluate(() => fetch("/__state").then(r => r.json()));
  check(state.log.filter(c => c.path === "/v1/statements/monthly").length === 1, "refresh performs no automatic billing retry");
  page.once("dialog", dialog => dialog.accept()); await page.locator("#go").click(); await page.locator("#flash .msg").waitFor();
  state = await page.evaluate(() => fetch("/__state").then(r => r.json()));
  const calls = state.log.filter(c => c.path === "/v1/statements/monthly");
  check(calls.length === 2 && calls[0].body.request_id === calls[1].body.request_id && state.monthlyRequests.length === 1, "manual retry after committed-but-lost response uses identical UUID without duplicate billing");
  check(await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith("cm.monthly.pending.")).length === 0), "confirmed success clears uncertain pending key");
  check((await page.locator("#go").innerText()).includes("new request"), "confirmed success permits explicit intentional new billing");
  page.once("dialog", dialog => dialog.accept()); await page.locator("#go").click();
  await page.waitForFunction(async () => (await fetch("/__state").then(r => r.json())).monthlyRequests.length === 2);
  check(true, "explicit apply-again creates a distinct billing request");
  return evidence;
}
