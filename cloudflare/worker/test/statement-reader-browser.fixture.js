// Start fixtures/statement-reader-fixture-server.mjs; replay with an existing Playwright page:
// const source=await page.evaluate(()=>fetch("/__browser-tests.js").then(r=>r.text()));
// return await new Function("page",source+"\nreturn runStatementReaderBrowserChecks(page)")(page);
async function runRecordListBrowserChecks(page) {
  const evidence = [];
  function check(ok, text) { if (!ok) throw new Error(text); evidence.push(text); }
  await page.evaluate(() => {
    const customers = [
      { customer_id: "1", first_name: "Demo", last_name: "Avery", service_day: "Monday", route_order: 1, amount_charged: 150, address: "123 Fictional Street", city: "Example" },
      { customer_id: "2", first_name: "Sample", last_name: "Blake", service_day: "Monday", route_order: 2, amount_charged: 160, address: "456 Synthetic Avenue" },
    ];
    S.account = { role: "COMPANY", companyName: "Synthetic Company" };
    api = async (path, opts = {}) => {
      path=path.split("?")[0];
      if (path === "/v1/settings") return {};
      if (path === "/v1/record-types") return ["Service", "Zelle Payment"];
      if (path === "/v1/customers/reorder") {
        window.testOrder = opts.body.customer_ids;
        customers.sort((a,b) => window.testOrder.indexOf(a.customer_id) - window.testOrder.indexOf(b.customer_id));
        customers.forEach((c,i) => c.route_order = i + 1); return {};
      }
      if (path === "/v1/customers") return customers;
      if (/\/statements$/.test(path)) return [
        { record_id: 1, record_date: "10/01/2026", type: "Service", amount: 100 },
        { record_id: 2, record_date: "10/02/2026", type: "Zelle Payment", amount: -30 },
      ];
      if (/\/quotes(?:\/1)?$/.test(path)) { const quote={ quote_id: 1, quote_number: "SQ-000001", quote_date: "10/01/2026",
        notes: "", terms: "", lines: [{ date: "10/01/2026", type: "Service", description: "Synthetic cleaning", amount: -100 }] }; return path.endsWith("/1")?quote:[quote]; }
      if (path === "/v1/visits/1") return { visit_id: 1, visited_at: "2026-10-01T12:00:00Z", ph: "7.4", chlo: "2-4", stb: "60", alk: null, cal: 300, notes: "Synthetic visit notes", photo_data: "" };
      if (path === "/v1/balances") return {rows:customers.map(c=>({customer_id:c.customer_id,balance:70}))};
      if (/\/visits$/.test(path)) return [{ visit_id: 1, visited_at: "2026-10-01T12:00:00Z", ph: "7.4", chlo: "2-4", stb: "60", alk: null, cal: 300, notes: "Synthetic visit notes", photo_data: "" }];
      if (/\/balance$/.test(path)) return { balance: 70 };
      if (/^\/v1\/customers\/[^/]+$/.test(path)) return customers[0];
      if (path === "/v1/revenue") return { by_day: [{ service_day: "Monday", total: 310 }], total: 310 };
      if (path.endsWith("/employees")) return [{ employee_id: 1, first_name: "Demo", last_name: "Staff", username: "synthetic" }];
      throw new Error("Unhandled synthetic path: " + path);
    };
  });
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport);
    await page.evaluate(size => document.documentElement.style.fontSize = size, viewport.width === 390 ? "24px" : "16px");
    for (const [view, arg, caption] of [
      ["customers", null, "Customers"], ["statement", "1", "Statement records"],
      ["quote", { id: "1", qid: 1 }, "Quote lines"], ["visit", "1", "Past visit records"],
      ["charged", null, "Outstanding balances"], ["revenue", null, "Revenue by day"],
      ["types", null, "Record types"], ["employees", null, "Employees"],
    ]) {
      await page.evaluate(({view,arg}) => go(view,arg), {view,arg});
      await page.getByRole("region", { name: caption, exact: true }).waitFor();
      check(await page.locator(".record-table th[scope=col]").count() > 0, `${view}: labeled table at ${viewport.width}px`);
      check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${view}: no page overflow at ${viewport.width}px`);
    }
  }
  await page.evaluate(() => go("customers"));
  await page.locator("#df").waitFor(); await page.locator("#df").selectOption("Monday");
  await page.locator('[data-id="2"] [data-mv="-1"]').click();
  await page.waitForFunction(() => window.testOrder?.join(",") === "2,1");
  check(await page.locator("#lst tbody tr").first().getAttribute("data-id") === "2", "route arrows persist/reload order in table");
  await page.evaluate(() => go("visit","1"));
  await page.locator("[data-visit]").click();
  check(await page.locator("#v_notes").inputValue() === "Synthetic visit notes", "visit table action loads full saved record");
  await page.evaluate(() => go("quote",{id:"1",qid:1}));
  await page.locator("#qls0").fill("Edited synthetic description");
  check((await page.locator("#qstate").innerText()).includes("Save quote first"), "quote table edits keep dirty-state guard");
  await page.locator("#qadd").click();
  check(await page.locator("#qlines tbody tr").count() === 2, "quote table add line");
  await page.locator('[data-qremove="1"]').click();
  check(await page.locator("#qlines tbody tr").count() === 1, "quote table remove line");
  return evidence;
}

async function runStatementReaderBrowserChecks(page) {
  const base = "http://127.0.0.1:8802", evidence = [];
  const check = (ok, text) => { if (!ok) throw new Error(text); evidence.push(text); };
  const state = () => page.evaluate(() => fetch("/__state").then(r => r.json()));
  async function login(user) {
    await page.goto(base);
    if (await page.locator("#logout").isVisible()) await page.locator("#logout").click();
    await page.locator("#u").fill(user); await page.locator("#p").fill("synthetic");
    await page.locator("#lg").click();
  }
  const pdf = await page.evaluate(() => fetch("/__synthetic.pdf").then(r => r.arrayBuffer()).then(b => Array.from(new Uint8Array(b))));
  const scan = await page.evaluate(() => fetch("/__scan.pdf").then(r => r.arrayBuffer()).then(b => Array.from(new Uint8Array(b))));
  // Integrated-browser transport has no Node Buffer; exercise a real File + change event instead.
  const upload = (bytes = pdf, name = "synthetic.pdf") => page.evaluate(({ bytes, name }) => {
    const data = new DataTransfer();
    data.items.add(new File([new Uint8Array(bytes)], name, { type: "application/pdf" }));
    const input = document.getElementById("sr-file"); input.files = data.files;
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }, { bytes, name });
  await page.goto(base);
  await page.evaluate(async () => { localStorage.clear(); await fetch("/__reset", { method: "POST" }); });
  await page.setViewportSize({ width: 390, height: 844 });
  await login("editor"); await page.locator("#sr-file:not([disabled])").waitFor();
  check(await page.locator('#nav [data-go="reader"]').count() === 1, "reader-only employee default landing and nav");
  await page.locator("#menuBtn").click(); await page.locator('#nav [data-star="reader"]').click();
  check(await page.locator('#quick [data-go="reader"]').count() === 1, "reader quickbar permission-filtered favorite");
  await page.evaluate(() => document.getElementById("drawer").classList.remove("open"));
  await upload(); await page.locator(".reader-row").first().waitFor();
  check(await page.locator(".reader-row").count() === 3, "real PDF.js browser worker shows only three incoming Zelle deposits");
  check((await page.locator("#sr-date-0").inputValue()) === "09/08/2026", "posting date not transfer description date");
  check((await page.locator("#sr-amount-0").inputValue()) === "100", "deposit column read without dollar sign; ending balance excluded");
  check((await page.locator("#sr-year").innerText()).includes("2026"), "server current-year notice shown");
  check(await page.locator('[data-row="0"] [data-field="selected"]').isChecked(), "only ready row selected by default");
  check(!await page.locator('[data-row="1"] [data-field="selected"]').isChecked(), "suspected duplicate unchecked by default");
  check(!await page.locator("#sr-rows").innerText().then(text => text.includes("Zelle To")), "outgoing transfer hidden");
  check(await page.locator('[data-row="2"] [data-field="selected"]').isDisabled(), "review cannot select without valid customer");
  check(await page.locator("#sr-customer-2 option").count() === 2, "review dropdown contains only placeholder and suggested match, not every customer");
  await page.locator('[data-find="2"]').click();
  await page.getByRole("dialog", { name: "Find an accessible customer" }).waitFor();
  await page.locator("#sr-search").fill("Avery");
  await page.locator("#sr-matches [data-match]").first().click();
  check(await page.locator("#sr-customer-2").inputValue() === "2", "shared accessible search picker assigns manually searched customer");
  check(!await page.locator('[data-row="2"] [data-field="selected"]').isChecked(), "manual customer search does not silently select a review row for import");
  check(await page.locator("#sr-customer-2 option").count() === 3, "row dropdown retains only current plus suggestion after manual assignment");
  await page.locator("#sr-customer-2").selectOption("3");
  await page.locator("#sr-date-2").fill("02/30/2026");
  check(await page.locator('[data-row="2"] [data-field="selected"]').isDisabled(), "invalid edited calendar date cannot select");
  await page.locator("#sr-date-2").fill("09/11/2026");
  await page.locator("#sr-amount-2").fill("80.00");
  await page.locator('[data-row="2"] [data-field="selected"]').check();
  await page.locator('[data-row="0"] [data-field="selected"]').uncheck();
  await page.locator('[data-row="1"] [data-field="override_duplicate"]').check();
  await page.locator('[data-row="1"] [data-field="selected"]').check();
  check((await page.locator("#sr-import").innerText()).includes("(2)"), "selection/edit/explicit duplicate override tracked");
  await page.evaluate(() => document.documentElement.style.fontSize = "24px");
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "390px layout has no horizontal overflow at 150% text");
  await page.evaluate(() => fetch("/__fail-next", { method: "POST" }));
  page.once("dialog", d => d.accept()); await page.locator("#sr-import").click();
  await page.locator("#flash .err").waitFor();
  check((await page.locator("#flash").innerText()).includes("preview kept"), "API errors visible");
  check(await page.locator(".reader-row").count() === 3 && await page.locator("#sr-amount-2").inputValue() === "80.00", "API error preserves preview and manual edits");
  check(await page.locator('[data-row="1"] [data-field="override_duplicate"]').isChecked(), "API error preserves explicit override");
  page.once("dialog", d => d.accept()); await page.locator("#sr-import").click();
  await page.waitForFunction(() => document.getElementById("sr-result").textContent.includes("Imported: 2"));
  let s = await state();
  check(s.records.length === 2 && s.records.every(r => r.type === "Zelle Payment" && r.amount < 0), "selected rows only persist as negative Zelle Payment");
  check(s.imports.find(r => r.customer_id === "3").amount === 80, "manual corrected amount reaches import");
  check(s.imports.find(r => r.customer_id === "2").override_duplicate === true, "suspected duplicate override is explicit API boolean");
  check(await page.locator('[data-row="2"] [data-field="selected"]').isDisabled(), "import result statuses persist and lock rows");
  check(await page.locator("#sr-import").isDisabled(), "double submit prevented after selected rows imported");
  check(s.log.filter(l => l.path.includes("/statement-reader/")).every(l => !JSON.stringify(l.body).includes("%PDF") && !("file" in l.body)), "requests contain only hash/extracted rows, never raw PDF");
  const previews = s.log.filter(l => l.path.endsWith("/preview"));
  check(/^[a-f0-9]{64}$/.test(previews[0].body.document_hash), "SHA-256 document identity");
  await upload(pdf, "renamed.pdf"); await page.locator(".reader-row").first().waitFor();
  check((await page.locator('[data-row="1"] [data-status]').innerText()) === "imported" &&
    await page.locator('[data-row="1"] [data-field="override_duplicate"]').count() === 0, "renamed exact PDF/source stays imported, no override");
  check(await page.locator('[data-row="0"] [data-field="selected"]').isChecked(), "new upload resets selection and preview state");
  await upload(scan, "scan.pdf"); await page.locator("#flash .err").waitFor();
  check((await page.locator("#flash").innerText()).includes("Scanned/image PDF needs selectable text"), "scan fails with explicit selectable-text/OCR message");
  check(await page.locator(".reader-row").count() === 0, "failed new upload resets old preview");
  await login("reader"); await page.locator("#sr-file:not([disabled])").waitFor(); await upload();
  await page.locator(".reader-row").first().waitFor();
  check(await page.locator("#sr-import").isDisabled(), "reader-only permission previews but cannot import without Edit statements");
  await page.evaluate(() => fetch("/__delay-preview", { method: "POST" }));
  await upload();
  await page.waitForFunction(() => document.getElementById("sr-progress").textContent.includes("Matching"));
  await page.evaluate(() => { S.account.permissions = {}; go("reader"); });
  await page.waitForTimeout(600);
  check((await page.locator("#app").innerText()).includes("not permitted") && await page.locator(".reader-row").count() === 0, "stale upload/preview cannot overwrite navigation guard");
  await login("noperm"); await page.locator("#q").waitFor();
  check(await page.locator('[data-go="reader"]').count() === 0, "no-permission nav and quickbar hidden");
  const before = (await state()).log.filter(l => l.path.includes("/statement-reader/")).length;
  await page.evaluate(() => go("reader"));
  check((await page.locator("#app").innerText()).includes("not permitted"), "direct reader route blocked");
  check((await state()).log.filter(l => l.path.includes("/statement-reader/")).length === before, "guard sends no reader API request");
  await login("company"); await page.locator("#q").waitFor();
  await page.evaluate(() => go("reader")); await page.locator("#sr-file:not([disabled])").waitFor();
  check((await page.locator("#sr-rights").innerText()).includes("negative"), "COMPANY reader/import allowed");
  return evidence;
}
