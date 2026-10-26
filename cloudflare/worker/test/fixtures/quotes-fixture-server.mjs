// Synthetic quotation API. No real credentials/data. No packages required.
// node test/fixtures/quotes-fixture-server.mjs [port=8801]
// Log in as quoteonly, editor, boss, or noperm; any password.
import http from "node:http";
import fs from "node:fs";

export function createQuoteFixture() {
  const customers = [
    { customer_id: "1", first_name: "Alice", last_name: "Alpha", address: "1 Synthetic St", city: "Testville", state: "ZZ", zip: "00000", service_day: "Monday" },
    { customer_id: "2", first_name: "Bob", last_name: "Beta", address: "2 Synthetic St", city: "Testville", state: "ZZ", zip: "00000", service_day: "Tuesday" },
  ];
  const accounts = Object.fromEntries(["quoteonly", "editor", "boss", "noperm"].map(username => [username, {
    username, role: username === "boss" ? "COMPANY" : "EMPLOYEE", companyId: 1, companyName: "Synthetic Pool Co",
    permissions: { canAccessSalesQuote: username !== "noperm", canEditStatements: username === "editor", canViewCustomers: username === "noperm" },
  }]));
  const settings = { companyName: "Synthetic Pool Co", companyAddress: "100 Example Ave|Testville, ZZ 00000", companyPhone: "5555550100",
    paymentInfo: "ZELLE: synthetic@example.invalid|Pay Check: Synthetic Pool Co", showLogo: "true",
    logoData: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEgAAABICAYAAABV7bNHAAAAhElEQVR4nO3QIREAIADAQGpQl2BUgwRoJl7M737MtY/ejd8D9QABAgQIUDhAgAABAhQOECBAgACFAwQIECBA4QABAgQIUDhAgAABAhQOECBAgACFAwQIECBA4QABAgQIUDhAgAABAhQOECBAgACFAwQIECBA4QABAgQIUDhAgAABArS6XUkNeMI5rnnkAAAAAElFTkSuQmCC",
    recordTypeCreditKeywords: "payment,discount", recordTypeDebitKeywords: "service,parts" };
  let nextId = 1;
  const quotes = [], statements = [], log = [];
  const send = (res, status, body) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://fixture");
      if (url.pathname === "/__browser-tests.js") {
        res.writeHead(200, { "Content-Type": "text/javascript" });
        return res.end(fs.readFileSync(new URL("../quotes-browser.fixture.js", import.meta.url)));
      }
      if (url.pathname === "/" || url.pathname === "/index.html") {
        res.writeHead(200, { "Content-Type": "text/html" });
        return res.end(fs.readFileSync(new URL("../../public/index.html", import.meta.url)));
      }
      if (url.pathname === "/__state") return send(res, 200, { quotes, statements, log });
      if (url.pathname === "/__reset" && req.method === "POST") {
        quotes.length = statements.length = log.length = 0; nextId = 1;
        return send(res, 200, {});
      }
      let raw = ""; for await (const c of req) raw += c;
      const body = raw ? JSON.parse(raw) : {};
      log.push({ method: req.method, path: url.pathname, body });
      if (url.pathname === "/v1/auth/login") {
        const account = accounts[body.username];
        return send(res, account ? 200 : 401, account ? { token: body.username, account } : { error: "Bad login" });
      }
      const account = accounts[(req.headers.authorization || "").replace("Bearer ", "")];
      if (!account) return send(res, 401, { error: "Unauthorized" });
      const access = account.role === "COMPANY" || account.permissions.canAccessSalesQuote;
      if (!access && url.pathname !== "/v1/customers") return send(res, 403, { error: "Forbidden" });
      if (url.pathname === "/v1/settings") return send(res, 200, settings);
      if (url.pathname === "/v1/record-types") return send(res, 200, ["Service", "Parts", "Payment", "Discount"]);
      if (url.pathname === "/v1/customers") return send(res, 200, customers);
      const m = url.pathname.match(/^\/v1\/customers\/(\d+)(?:\/quotes(?:\/(\d+)(\/convert)?)?)?$/);
      if (!m) return send(res, 404, { error: "Not found" });
      const [, cid, qid, convert] = m;
      const customer = customers.find(c => c.customer_id === cid);
      if (!customer) return send(res, 404, { error: "Customer not found" });
      if (!url.pathname.includes("/quotes")) return send(res, 200, customer);
      if (!access) return send(res, 403, { error: "Forbidden" });
      if (req.method === "GET") return send(res, 200, quotes.filter(q => q.customer_id === cid));
      const q = quotes.find(q => String(q.quote_id) === qid && q.customer_id === cid);
      if (qid && !q) return send(res, 404, { error: "Quote not found" });
      if (q?.converted_at) return send(res, 409, { error: "Converted quote is locked" });
      if (convert) {
        if (req.method !== "POST") return send(res, 405, { error: "Method" });
        if (account.role !== "COMPANY" && !account.permissions.canEditStatements) return send(res, 403, { error: "Forbidden" });
        // This in-memory fixture changes the record and ledger synchronously, once only.
        statements.push(...q.lines.map(l => ({ customer_id: cid, ...l })));
        q.converted_at = q.updated_at = new Date().toISOString();
        return send(res, 200, q);
      }
      if (req.method === "DELETE" && q) { quotes.splice(quotes.indexOf(q), 1); return send(res, 200, { deleted: true }); }
      if (!["POST", "PUT"].includes(req.method)) return send(res, 405, { error: "Method" });
      if (!Array.isArray(body.lines) || !body.lines.length || body.lines.length > 200 || body.lines.some(l => !Number.isFinite(l.amount) || !l.amount || Math.abs(l.amount) > 999999999.99) ||
          typeof body.notes !== "string" || body.notes.length > 16000 || typeof body.terms !== "string" || body.terms.length > 16000)
        return send(res, 400, { error: "Invalid quotation" });
      const now = new Date().toISOString();
      if (q) { Object.assign(q, body, { updated_at: now }); return send(res, 200, q); }
      const quote_id = nextId++;
      const row = { ...body, quote_id, customer_id: cid, quote_number: "SQ-" + String(quote_id).padStart(6, "0"), converted_at: null, created_at: now, updated_at: now };
      quotes.push(row); send(res, 200, row);
    } catch (e) { send(res, 500, { error: e.message }); }
  });
}
if (process.argv[1] && import.meta.url === new URL("file:///" + process.argv[1].replace(/\\/g, "/")).href) {
  const port = Number(process.argv[2] || 8801);
  createQuoteFixture().listen(port, "127.0.0.1", () => console.log("quotes fixture http://127.0.0.1:" + port));
}
