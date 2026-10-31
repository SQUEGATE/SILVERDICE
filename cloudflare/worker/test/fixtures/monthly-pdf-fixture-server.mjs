// Synthetic, in-memory monthly billing fixture; never connects to a real database.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../public");
const customers = [
  { customer_id: "1", first_name: "Alice", last_name: "Example", status: "Active" },
  { customer_id: "2", first_name: "Bob", last_name: "Example", status: "Active" },
  { customer_id: "3", first_name: "Inactive", last_name: "Example", status: "Inactive" },
];
const records = Object.fromEntries(customers.map(c => [c.customer_id, [
  { record_id: 1, record_date: "10/30/2026", type: "Bill", amount: 25 },
]]));
const requests = new Set(), log = [];
const staticFiles = {
  "/": "index.html",
  "/statement-export.mjs": "statement-export.mjs",
  "/vendor/pdf-export/jspdf.umd.min.js": "vendor/pdf-export/jspdf.umd.min.js",
  "/vendor/pdf-export/jspdf.plugin.autotable.min.js": "vendor/pdf-export/jspdf.plugin.autotable.min.js",
};
const send = (res, status, body) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://fixture");
  if (staticFiles[url.pathname]) {
    res.writeHead(200, { "Content-Type": url.pathname === "/" ? "text/html" : "text/javascript" });
    return res.end(fs.readFileSync(path.resolve(root, staticFiles[url.pathname])));
  }
  if (url.pathname === "/__log") return send(res, 200, log);
  if (req.headers.authorization !== "Bearer boss") return send(res, 401, { error: "Unauthorized" });
  log.push(req.method + " " + url.pathname);
  if (url.pathname === "/v1/settings") return send(res, 200, { companyName: "Synthetic Pools",
    showLogo: "false", showGenerationDate: "false", recordTypeCreditKeywords: "service" });
  if (url.pathname === "/v1/record-types") return send(res, 200, ["Service"]);
  if (url.pathname === "/v1/customers") return send(res, 200, { rows: customers, total: customers.length });
  const match = url.pathname.match(/^\/v1\/customers\/([123])\/statements$/);
  if (match) return send(res, 200, records[match[1]]);
  if (url.pathname === "/v1/statements/monthly" && req.method === "POST") {
    let text = ""; for await (const chunk of req) text += chunk;
    const body = JSON.parse(text);
    if (!requests.has(body.request_id)) {
      requests.add(body.request_id);
      for (const c of customers.filter(c => c.status === "Active")) records[c.customer_id].push({
        record_id: records[c.customer_id].length + 1, record_date: body.date, type: body.type, amount: 100,
      });
    }
    return send(res, 200, { added: 2, request_id: body.request_id });
  }
  send(res, 404, { error: "Not found" });
}).listen(Number(process.argv[2] || 8803), "127.0.0.1", () => console.log("Monthly PDF fixture ready"));
