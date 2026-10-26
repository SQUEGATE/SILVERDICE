// Synthetic UI contract server, not a replacement for backend integration tests.
// node test/fixtures/statement-reader-fixture-server.mjs [port=8802]
import http from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { makePdf } from "./statement-reader-synthetic.mjs";

export function createStatementReaderFixture() {
  const customers = [
    { customer_id: "1", first_name: "Mira", last_name: "Example" },
    { customer_id: "2", first_name: "Avery", last_name: "Demo" },
    { customer_id: "3", first_name: "Rowan", last_name: "Fiction" },
  ];
  const accounts = Object.fromEntries(["reader", "editor", "company", "noperm"].map(username => [username, {
    username, role: username === "company" ? "COMPANY" : "EMPLOYEE", companyId: 1, companyName: "Synthetic Company",
    permissions: { canAccessStatementReader: username !== "noperm", canEditStatements: username === "editor", canViewCustomers: username === "noperm" },
  }]));
  const log = [], imports = [], records = [];
  let failNext = false, previewDelay = 0;
  const send = (res, status, body) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
  return http.createServer(async (req, res) => {
    try {
      const path = new URL(req.url, "http://fixture").pathname;
      const asset = path === "/" ? "../../public/index.html"
        : path === "/statement-reader.mjs" ? "../../public/statement-reader.mjs"
        : path === "/__browser-tests.js" ? "../statement-reader-browser.fixture.js"
        : /^\/vendor\/pdfjs\/(?:pdf(?:\.worker)?\.mjs|LICENSE)$/.test(path) ? "../../public" + path : null;
      if (asset) {
        res.writeHead(200, { "Content-Type": path === "/" ? "text/html" : "text/javascript" });
        return res.end(await readFile(new URL(asset, import.meta.url)));
      }
      if (path === "/__synthetic.pdf" || path === "/__scan.pdf") {
        res.writeHead(200, { "Content-Type": "application/pdf" });
        return res.end(path === "/__scan.pdf" ? makePdf([{ items: [] }]) : makePdf());
      }
      if (path === "/__state") return send(res, 200, { log, imports, records });
      if (path === "/__reset") { log.length = imports.length = records.length = 0; failNext = false; previewDelay = 0; return send(res, 200, {}); }
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
      if (path === "/v1/customers") return send(res, 200, customers);
      const allowed = account.role === "COMPANY" || account.permissions.canAccessStatementReader;
      if (!allowed) return send(res, 403, { error: "Statement Reader is not permitted." });
      if (path === "/v1/statement-reader/preview") {
        if (Object.keys(body).some(k => !["document_hash", "rows"].includes(k)) || !/^[a-f0-9]{64}$/.test(body.document_hash)) return send(res, 400, { error: "Unexpected payload." });
        const rows = body.rows.map((r, i) => {
          const seen = imports.some(x => x.document_hash === body.document_hash && x.source_id === r.source_id);
          const status = seen ? "imported" : r.direction === "withdrawal" ? "ignored" : i === 2 ? "duplicate" : i === 3 ? "review" : "ready";
          return {
            source_id: r.source_id, page: r.page, line: r.line, text: r.text,
            date: `09/${String(Number(r.date_text.split("/")[1])).padStart(2, "0")}/2026`,
            amount: r.deposit_text ? Number(r.deposit_text.replace(/[$,]/g, "")) : null,
            customer_id: i === 3 ? "" : i === 2 ? "2" : "1", candidate_ids: i === 3 ? ["3"] : [],
            status, reason: seen ? "Source or bank reference already imported" : status === "duplicate" ? "Existing payment fingerprint" : status === "review" ? "Customer requires review" : status === "ignored" ? "Withdrawal" : "",
            reference: r.reference,
          };
        });
        if (previewDelay) await new Promise(resolve => setTimeout(resolve, previewDelay));
        return send(res, 200, { year: 2026, rows });
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
