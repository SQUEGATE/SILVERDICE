// Synthetic UI contract server, not a replacement for backend integration tests.
// node test/fixtures/statement-reader-fixture-server.mjs [port=8802]
import http from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { makePdf, item } from "./statement-reader-synthetic.mjs";

export function createStatementReaderFixture() {
  const customers = [
    { customer_id: "1", first_name: "Mira", last_name: "Example" },
    { customer_id: "2", first_name: "Avery", last_name: "Demo" },
    { customer_id: "3", first_name: "Rowan", last_name: "Fiction" },
  ];
  const accounts = Object.fromEntries(["reader", "editor", "company", "noperm"].map(username => [username, {
    username, role: username === "company" ? "COMPANY" : "EMPLOYEE", companyId: 1, companyName: "Synthetic Company",
    permissions: { canAccessStatementReader: username !== "noperm", canEditStatements: username === "editor", canViewCustomers: username === "noperm", canAccessExpenses: username !== "noperm" },
  }]));
  const log = [], imports = [], records = [];
  let expenseKeywords = [], failNext = false, previewDelay = 0, failMonthly = false;
  const monthlyRequests = new Map();
  const send = (res, status, body) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
  return http.createServer(async (req, res) => {
    try {
      const path = new URL(req.url, "http://fixture").pathname;
      const asset = path === "/" ? "../../public/index.html"
        : path === "/statement-reader.mjs" ? "../../public/statement-reader.mjs"
        : path === "/expense-reader.mjs" ? "../../public/expense-reader.mjs"
        : path === "/__browser-tests.js" ? "../statement-reader-browser.fixture.js"
        : path === "/__optimization-tests.js" ? "../web-optimizations-browser.fixture.js"
        : /^\/vendor\/pdfjs\/(?:pdf(?:\.worker)?\.mjs|LICENSE)$/.test(path) ? "../../public" + path : null;
      if (asset) {
        res.writeHead(200, { "Content-Type": path === "/" ? "text/html" : "text/javascript" });
        return res.end(await readFile(new URL(asset, import.meta.url)));
      }
      if (path === "/__expense.pdf") {
        res.writeHead(200, { "Content-Type": "application/pdf" });
        return res.end(makePdf([{ items: [item("01/03 HOME DEPOT #123 Tampa", 60, 700, 150), item("$45.10", 300, 700, 30), item("1,000.00", 400, 700, 40), item("01/04 Pool Supplies Inc chlorine", 60, 680, 160), item("(1,200.50)", 300, 680, 50), item("01/05 Depotting service 9.99", 60, 660, 160), item("01/06 Home Depot return pending", 60, 640, 160)] }, { items: [item("02/01 home   depot online 12.40", 60, 700, 170)] }]));
      }
      if (path === "/__synthetic.pdf" || path === "/__scan.pdf") {
        res.writeHead(200, { "Content-Type": "application/pdf" });
        return res.end(path === "/__scan.pdf" ? makePdf([{ items: [] }]) : makePdf());
      }
      if (path === "/__state") return send(res, 200, { log, imports, records, monthlyRequests: [...monthlyRequests.values()] });
      if (path === "/__reset") { expenseKeywords = []; log.length = imports.length = records.length = 0; failNext = false; previewDelay = 0; failMonthly = false; monthlyRequests.clear(); return send(res, 200, {}); }
      if (path === "/__fail-monthly") { failMonthly = true; return send(res, 200, {}); }
      if (path === "/__fail-next") { failNext = true; return send(res, 200, {}); }
      if (path === "/__delay-preview") { previewDelay = 400; return send(res, 200, {}); }
      let raw = ""; for await (const chunk of req) raw += chunk;
      const body = raw ? JSON.parse(raw) : {};
      log.push({ path, method: req.method, body });
      if (path === "/v1/auth/login") {
        const account = accounts[body.username];
        return send(res, account ? 200 : 401, account ? { token: body.username, account } : { error: "Bad login" });
      }
      const account = accounts[(req.headers.authorization || "").replace("Bearer ", "")];
      if (!account) return send(res, 401, { error: "Unauthorized" });
      if (path === "/v1/settings") return send(res, 200, {});
      if (path === "/v1/expense-keywords") {
        if (account.role !== "COMPANY" && !account.permissions.canAccessExpenses) return send(res, 403, { error: "Permission denied" });
        if (req.method === "PUT") expenseKeywords = body.keywords;
        return send(res, 200, req.method === "PUT" ? { saved: true, keywords: expenseKeywords } : { keywords: expenseKeywords });
      }
      if (path === "/v1/record-types") return send(res, 200, ["Payment"]);
      if (path === "/v1/statements/monthly") {
        if (account.role !== "COMPANY") return send(res, 403, { error: "Edit statements permission required." });
        if (!monthlyRequests.has(body.request_id)) monthlyRequests.set(body.request_id, { request_id: body.request_id, added: customers.length });
        if (failMonthly) { failMonthly = false; return send(res, 503, { error: "Synthetic response lost after commit" }); }
        return send(res, 200, monthlyRequests.get(body.request_id));
      }
      if (path === "/v1/customers") {
        const query = new URL(req.url, "http://fixture").searchParams;
        const rows = customers.filter(c => !query.get("q") || Object.values(c).join(" ").toLowerCase().includes(query.get("q").toLowerCase()));
        return send(res, 200, query.has("limit") ? { rows: rows.slice(Number(query.get("offset") || 0), Number(query.get("offset") || 0) + Number(query.get("limit"))), total: rows.length } : rows);
      }
      if (/^\/v1\/customers\/[^/]+$/.test(path)) {
        const customer = customers.find(c => c.customer_id === path.split("/").pop());
        return send(res, customer ? 200 : 404, customer || { error: "Customer not found" });
      }
      const allowed = account.role === "COMPANY" || account.permissions.canAccessStatementReader;
      if (!allowed) return send(res, 403, { error: "Statement Reader is not permitted." });
      if (path === "/v1/statement-reader/preview") {
        if (Object.keys(body).some(k => !["document_hash", "rows"].includes(k)) || !/^[a-f0-9]{64}$/.test(body.document_hash)) return send(res, 400, { error: "Unexpected payload." });
        const rows = body.rows.map((r, i) => {
          const seen = imports.some(x => x.document_hash === body.document_hash && x.source_id === r.source_id);
          const duplicate = r.reference === "Syn789", review = r.reference === "Syn012";
          const status = seen ? "imported" : r.direction === "withdrawal" ? "ignored" : duplicate ? "duplicate" : review ? "review" : "ready";
          return {
            source_id: r.source_id, page: r.page, line: r.line, text: r.text,
            date: `09/${String(Number(r.date_text.split("/")[1])).padStart(2, "0")}/2026`,
            amount: r.deposit_text ? Number(r.deposit_text.replace(/[$,]/g, "")) : null,
            customer_id: review ? "" : duplicate ? "2" : "1", candidate_ids: review ? ["3"] : [],
            status, reason: seen ? "Source or bank reference already imported" : status === "duplicate" ? "Existing payment fingerprint" : status === "review" ? "Customer requires review" : status === "ignored" ? "Withdrawal" : "",
            reference: r.reference,
          };
        });
        if (previewDelay) await new Promise(resolve => setTimeout(resolve, previewDelay));
        const ids = new Set(rows.flatMap(r => [r.customer_id, ...(r.candidate_ids || [])]));
        return send(res, 200, { year: 2026, rows, customers: customers.filter(c => ids.has(c.customer_id)) });
      }
      if (path === "/v1/statement-reader/import") {
        if (account.role !== "COMPANY" && !account.permissions.canEditStatements) return send(res, 403, { error: "Edit statements permission required." });
        if (failNext) { failNext = false; return send(res, 503, { error: "Synthetic retryable error; preview kept." }); }
        if (body.rows.some(r => typeof r.override_duplicate !== "boolean" || !(r.amount > 0))) return send(res, 400, { error: "Invalid import payload." });
        const rows = body.rows.map(r => {
          const seen = imports.some(x => x.document_hash === body.document_hash && x.source_id === r.source_id);
          if (seen) return { source_id: r.source_id, status: "skipped", reason: "Source already imported" };
          imports.push({ document_hash: body.document_hash, ...r });
          const record_id = records.length + 1;
          records.push({ record_id, record_date: r.date, type: "Zelle Payment", amount: -r.amount, customer_id: r.customer_id });
          return { source_id: r.source_id, status: "imported", reason: "Imported", record_id };
        });
        return send(res, 200, { imported: rows.filter(r => r.status === "imported").length, skipped: rows.filter(r => r.status === "skipped").length, rows });
      }
      return send(res, 404, { error: "Not found" });
    } catch (e) { send(res, 500, { error: e.message }); }
  });
}
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const port = Number(process.argv[2] || 8802);
  createStatementReaderFixture().listen(port, "127.0.0.1", () => console.log(`statement reader fixture http://127.0.0.1:${port}`));
}
