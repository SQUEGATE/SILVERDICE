const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store"
};
const SESSION_TTL_DEFAULT = 86400;

export default {
  async fetch(request, env) {
    try {
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders(request) });
      }
      const url = new URL(request.url);
      if ((url.pathname === "/" || url.pathname === "/v1/health") && request.method === "GET") {
        await turso(env, "SELECT 1 AS ready");
        return json({ ready: true }, 200, request);
      }
      if (url.pathname === "/v1/auth/login" && request.method === "POST") {
        return await login(request, env);
      }

      const session = await authenticateRequest(request, env);
      if (url.pathname === "/v1/balances" && request.method === "GET") {
        requirePermission(session, "canViewStatements");
        const access = customerAccessSql(session);
        const rows = await turso(env, `SELECT c.customer_id, COALESCE(SUM(s.amount), 0) AS balance
          FROM (SELECT customer_id FROM customers WHERE company_id = ?${access.sql}) c
          LEFT JOIN statement_records s ON s.company_id = ? AND s.customer_id = c.customer_id
          GROUP BY c.customer_id ORDER BY c.customer_id`, requiredCompanyId(session), ...access.args, requiredCompanyId(session));
        return json({ rows }, 200, request);
      }
      if (url.pathname === "/v1/statements/monthly" && request.method === "POST") {
        return await createMonthlyStatements(request, env, session);
      }
      if (url.pathname === "/v1/settings/batch" && request.method === "PUT") {
        requirePermission(session, "canEditPdf");
        const body = await readJson(request, 2 * 1024 * 1024);
        if (Object.keys(body).some(key => key !== "settings") || !body.settings
            || typeof body.settings !== "object" || Array.isArray(body.settings)
            || Object.keys(body.settings).length > 200) throw httpError(400, "Invalid settings");
        const statements = Object.entries(body.settings).map(([key, value]) => {
          const settingKey = requiredString(key, "key", 160);
          validateSetting(settingKey, value);
          return settingStatement(requiredCompanyId(session), settingKey, value);
        });
        if (statements.length) await tursoTransaction(env, statements);
        return json({ saved: true }, 200, request);
      }
      const detailMatch = url.pathname.match(/^\/v1\/(quotes|visits)\/(\d+)$/);
      if (detailMatch && request.method === "GET") {
        const quote = detailMatch[1] === "quotes";
        requirePermission(session, quote ? "canAccessSalesQuote" : "canAccessVisits");
        const rows = await turso(env, `SELECT customer_id FROM ${quote ? "sales_quotes" : "customer_visits"}
          WHERE company_id = ? AND ${quote ? "quote_id" : "visit_id"} = ?`, requiredCompanyId(session), Number(detailMatch[2]));
        if (!rows.length) throw httpError(404, "Record not found");
        return quote
          ? await handleQuotes(request, env, session, rows[0].customer_id, Number(detailMatch[2]), false)
          : await getVisit(request, env, session, rows[0].customer_id, Number(detailMatch[2]));
      }
      if (url.pathname === "/v1/expense-keywords" && request.method === "GET") {
        requirePermission(session, "canAccessExpenses");
        const rows = await turso(env, "SELECT setting_value FROM pdf_settings WHERE company_id = ? AND setting_key = 'expenseKeywords'", requiredCompanyId(session));
        const stored = parseJsonArrayOrObject(rows[0]?.setting_value, []);
        return json({ keywords: Array.isArray(stored) ? normalizeExpenseKeywords(stored.filter(k => typeof k === "string").map(k => k.slice(0, 80))) : [] }, 200, request);
      }
      if (url.pathname === "/v1/expense-keywords" && request.method === "PUT") {
        requirePermission(session, "canAccessExpenses");
        const body = await readJson(request);
        const keywords = normalizeExpenseKeywords(body.keywords);
        const statement = settingStatement(requiredCompanyId(session), "expenseKeywords", JSON.stringify(keywords));
        await turso(env, statement.sql, ...statement.args);
        return json({ saved: true, keywords }, 200, request);
      }
      if (url.pathname === "/v1/statement-reader/preview" && request.method === "POST") {
        return await previewReader(request, env, session);
      }
      if (url.pathname === "/v1/statement-reader/import" && request.method === "POST") {
        return await importReader(request, env, session);
      }
      if (url.pathname === "/v1/admin/companies" && request.method === "GET") {
        requireMaster(session);
        const query = (url.searchParams.get("q") || "").trim().toLowerCase();
        const rows = query
          ? await turso(env, "SELECT company_id, company_name, phone, email, address, username FROM companies WHERE lower(company_name) LIKE ? OR lower(username) LIKE ? OR lower(phone) LIKE ? OR lower(email) LIKE ? OR lower(address) LIKE ? ORDER BY company_id", ...Array(5).fill(`%${query}%`))
          : await turso(env, "SELECT company_id, company_name, phone, email, address, username FROM companies ORDER BY company_id");
        return json(rows, 200, request);
      }
      if (url.pathname === "/v1/admin/companies" && request.method === "POST") {
        requireMaster(session);
        return await saveCompany(request, env);
      }
      const companyMatch = url.pathname.match(/^\/v1\/admin\/companies\/(\d+)(?:\/session)?$/);
      if (companyMatch && request.method === "GET") {
        requireMaster(session);
        const rows = await turso(env, "SELECT company_id, company_name, phone, email, address, username FROM companies WHERE company_id = ?", Number(companyMatch[1]));
        if (!rows.length) throw httpError(404, "Company not found");
        return json(rows[0], 200, request);
      }
      if (companyMatch && request.method === "POST" && url.pathname.endsWith("/session")) {
        requireMaster(session);
        const companyId = Number(companyMatch[1]);
        const rows = await turso(env, "SELECT company_id, company_name, phone, email, username FROM companies WHERE company_id = ?", companyId);
        if (!rows.length) throw httpError(404, "Company not found");
        const company = rows[0];
        const ttl = positiveInteger(env.SESSION_TTL_SECONDS, SESSION_TTL_DEFAULT);
        const previewSession = {
          sub: `master-preview-${session.sub}`,
          username: session.username,
          role: "COMPANY",
          companyId,
          companyName: company.company_name,
          phone: company.phone,
          email: company.email,
          permissions: {},
          allowedCustomerIds: [],
          allowedDays: [],
          preview: true,
          exp: Math.floor(Date.now() / 1000) + ttl
        };
        return json({ token: await signSession(previewSession, env), expiresIn: ttl }, 200, request);
      }
      if (companyMatch && request.method === "DELETE") {
        requireMaster(session);
        return await deleteCompany(request, env, Number(companyMatch[1]));
      }
      const employeesMatch = url.pathname.match(/^\/v1\/admin\/companies\/(\d+)\/employees$/);
      if (employeesMatch && request.method === "GET") {
        const companyId = Number(employeesMatch[1]);
        requireCompanyManager(session, companyId);
        const rows = await turso(env, "SELECT employee_id, company_id, first_name, last_name, phone, email, username, permissions_json, allowed_customer_ids_json, allowed_days_json FROM accounts WHERE role = 'EMPLOYEE' AND company_id = ? ORDER BY employee_id", companyId);
        return json(rows.map(employeeResponse), 200, request);
      }
      if (employeesMatch && request.method === "POST") {
        const companyId = Number(employeesMatch[1]);
        requireCompanyManager(session, companyId);
        return await saveEmployee(request, env, companyId);
      }
      const employeeMatch = url.pathname.match(/^\/v1\/admin\/employees\/(\d+)$/);
      if (employeeMatch && request.method === "DELETE") {
        const employeeRows = await turso(env, "SELECT company_id FROM accounts WHERE employee_id = ? AND role = 'EMPLOYEE'", Number(employeeMatch[1]));
        if (!employeeRows.length) throw httpError(404, "Employee not found");
        requireCompanyManager(session, Number(employeeRows[0].company_id));
        await turso(env, "DELETE FROM accounts WHERE employee_id = ? AND role = 'EMPLOYEE'", Number(employeeMatch[1]));
        return json({ deleted: true }, 200, request);
      }
      const employeePreviewMatch = url.pathname.match(/^\/v1\/companies\/employees\/(\d+)\/session$/);
      if (employeePreviewMatch && request.method === "POST") {
        if (session.role !== "COMPANY") throw httpError(403, "Company account required");
        return await createEmployeePreview(request, env, session, Number(employeePreviewMatch[1]));
      }
      if (url.pathname === "/v1/customers" && request.method === "GET") {
        return await listCustomers(request, env, session);
      }
      if (url.pathname === "/v1/customers/next-id" && request.method === "GET") {
        requirePermission(session, "canViewCustomers", "canViewCustomerDetails");
        const rows = await turso(env, "SELECT MAX(CAST(customer_id AS INTEGER)) AS max_id FROM customers WHERE company_id = ? AND customer_id GLOB '[0-9]*'", requiredCompanyId(session));
        return json({ customer_id: String(Number(rows[0]?.max_id || 0) + 1) }, 200, request);
      }
      if (url.pathname === "/v1/customers/reorder" && request.method === "POST") {
        return await reorderCustomers(request, env, session);
      }
      if (url.pathname === "/v1/customers" && request.method === "POST") {
        return await createCustomer(request, env, session);
      }
      if (url.pathname === "/v1/settings" && request.method === "GET") {
        requirePermission(session, "canViewPdf", "canAccessSalesQuote");
        const rows = await turso(env, "SELECT setting_key, setting_value FROM pdf_settings WHERE company_id = ? ORDER BY setting_key", requiredCompanyId(session));
        return json(Object.fromEntries(rows.map(row => [row.setting_key, row.setting_value])), 200, request);
      }
      if (url.pathname === "/v1/settings" && request.method === "PUT") {
        requirePermission(session, "canEditPdf");
        const body = await readJson(request, 700 * 1024);
        const settingKey = requiredString(body.key, "key", 160);
        validateSetting(settingKey, body.value);
        await turso(env, "INSERT INTO pdf_settings (company_id, setting_key, setting_value) VALUES (?, ?, ?) ON CONFLICT(company_id, setting_key) DO UPDATE SET setting_value = excluded.setting_value", requiredCompanyId(session), settingKey, body.value);
        return json({ saved: true }, 200, request);
      }
      if (url.pathname === "/v1/record-types" && request.method === "GET") {
        requirePermission(session, "canViewStatements", "canAccessSalesQuote");
        const rows = await turso(env, "SELECT type_name FROM record_types WHERE company_id = ? ORDER BY type_name", requiredCompanyId(session));
        return json(rows.map(row => row.type_name), 200, request);
      }
      if (url.pathname === "/v1/record-types" && request.method === "POST") {
        requirePermission(session, "canEditStatements");
        const body = await readJson(request);
        const typeName = requiredString(body.type_name, "type_name", 128);
        await turso(env, "INSERT INTO record_types (company_id, type_name) VALUES (?, ?) ON CONFLICT(company_id, type_name) DO NOTHING", requiredCompanyId(session), typeName);
        return json({ saved: true }, 201, request);
      }
      if (url.pathname === "/v1/record-types" && request.method === "PUT") {
        requirePermission(session, "canEditStatements");
        const body = await readJson(request);
        const oldName = requiredString(body.old_name, "old_name", 128);
        const newName = requiredString(body.new_name, "new_name", 128);
        await turso(env, "UPDATE statement_records SET type = ? WHERE company_id = ? AND type = ?", newName, requiredCompanyId(session), oldName);
        await turso(env, "DELETE FROM record_types WHERE company_id = ? AND type_name = ?", requiredCompanyId(session), oldName);
        await turso(env, "INSERT INTO record_types (company_id, type_name) VALUES (?, ?) ON CONFLICT(company_id, type_name) DO NOTHING", requiredCompanyId(session), newName);
        return json({ saved: true }, 200, request);
      }
      if (url.pathname === "/v1/record-types" && request.method === "DELETE") {
        requirePermission(session, "canEditStatements");
        const body = await readJson(request);
        const typeName = requiredString(body.type_name, "type_name", 128);
        await turso(env, "DELETE FROM record_types WHERE company_id = ? AND type_name = ?", requiredCompanyId(session), typeName);
        return json({ deleted: true }, 200, request);
      }
      const recordTypeMatch = url.pathname.match(/^\/v1\/record-types\/([^/]+)$/);
      if (recordTypeMatch && request.method === "DELETE") {
        requirePermission(session, "canEditStatements");
        await turso(env, "DELETE FROM record_types WHERE company_id = ? AND type_name = ?", requiredCompanyId(session), decodeURIComponent(recordTypeMatch[1]));
        return json({ deleted: true }, 200, request);
      }
      if (url.pathname === "/v1/statement-types" && request.method === "GET") {
        requirePermission(session, "canViewStatements");
        const rows = await turso(env, "SELECT DISTINCT type FROM statement_records WHERE company_id = ? ORDER BY type", requiredCompanyId(session));
        return json(rows.map(row => row.type).filter(Boolean), 200, request);
      }
      if (url.pathname === "/v1/statement-types/normalize" && request.method === "POST") {
        requirePermission(session, "canEditStatements");
        const body = await readJson(request);
        const type = requiredString(body.type, "type", 128);
        const debit = body.debit === true;
        await turso(env, debit
          ? "UPDATE statement_records SET amount = -ABS(amount) WHERE company_id = ? AND type = ?"
          : "UPDATE statement_records SET amount = ABS(amount) WHERE company_id = ? AND type = ?",
        requiredCompanyId(session), type);
        return json({ normalized: true }, 200, request);
      }
      if (url.pathname === "/v1/revenue" && request.method === "GET") {
        requirePermission(session, "canViewRevenueSummary");
        const rows = await turso(env, "SELECT service_day, SUM(amount_charged) AS total FROM customers WHERE company_id = ? AND status <> 'Inactive' GROUP BY service_day", requiredCompanyId(session));
        return json({ by_day: rows, total: rows.reduce((sum, row) => sum + Number(row.total || 0), 0) }, 200, request);
      }
      if (url.pathname === "/v1/backup" && request.method === "GET") {
        requireCompanyManager(session, requiredCompanyId(session));
        return json(await exportCompanyBackup(env, requiredCompanyId(session)), 200, request);
      }
      if (url.pathname === "/v1/backup/restore" && request.method === "POST") {
        requireCompanyManager(session, requiredCompanyId(session));
        return json(await restoreCompanyBackup(request, env, requiredCompanyId(session)), 200, request);
      }
      const quotesMatch = url.pathname.match(/^\/v1\/customers\/([^/]+)\/quotes(?:\/(\d+)(\/convert)?)?$/);
      if (quotesMatch) {
        return await handleQuotes(request, env, session, decodeURIComponent(quotesMatch[1]),
          quotesMatch[2] === undefined ? null : Number(quotesMatch[2]), Boolean(quotesMatch[3]));
      }
      const visitsMatch = url.pathname.match(/^\/v1\/customers\/([^/]+)\/visits$/);
      if (visitsMatch && request.method === "GET") {
        return await listVisits(request, env, session, decodeURIComponent(visitsMatch[1]));
      }
      if (visitsMatch && request.method === "POST") {
        return await createVisit(request, env, session, decodeURIComponent(visitsMatch[1]));
      }
      const visitMatch = url.pathname.match(/^\/v1\/customers\/([^/]+)\/visits\/(\d+)$/);
      if (visitMatch && request.method === "GET") {
        return await getVisit(request, env, session, decodeURIComponent(visitMatch[1]), Number(visitMatch[2]));
      }
      if (visitMatch && request.method === "PUT") {
        return await updateVisit(request, env, session, decodeURIComponent(visitMatch[1]), Number(visitMatch[2]));
      }
      if (visitMatch && request.method === "DELETE") {
        requirePermission(session, "canAccessVisits");
        const customerId = decodeURIComponent(visitMatch[1]);
        await findAccessibleCustomer(env, session, customerId);
        const visitId = Number(visitMatch[2]);
        if (!Number.isSafeInteger(visitId) || visitId < 1) throw httpError(404, "Visit not found");
        const rows = await turso(env,
          "DELETE FROM customer_visits WHERE company_id = ? AND customer_id = ? AND visit_id = ? RETURNING visit_id",
          requiredCompanyId(session), customerId, visitId);
        if (!rows.length) throw httpError(404, "Visit not found");
        return json({ deleted: true }, 200, request);
      }
      const customerMatch = url.pathname.match(/^\/v1\/customers\/([^/]+)$/);
      if (customerMatch && request.method === "GET") {
        return await getCustomer(request, env, session, decodeURIComponent(customerMatch[1]));
      }
      const balanceMatch = url.pathname.match(/^\/v1\/customers\/([^/]+)\/balance$/);
      if (balanceMatch && request.method === "GET") {
        requirePermission(session, "canViewStatements");
        await findAccessibleCustomer(env, session, decodeURIComponent(balanceMatch[1]));
        const rows = await turso(env, "SELECT COALESCE(SUM(amount), 0) AS total FROM statement_records WHERE company_id = ? AND customer_id = ?", requiredCompanyId(session), decodeURIComponent(balanceMatch[1]));
        return json({ balance: Number(rows[0]?.total || 0) }, 200, request);
      }
      if (customerMatch && request.method === "PUT") {
        return await updateCustomer(request, env, session, decodeURIComponent(customerMatch[1]));
      }
      if (customerMatch && request.method === "DELETE") {
        return await deleteCustomer(request, env, session, decodeURIComponent(customerMatch[1]));
      }
      const statementsMatch = url.pathname.match(/^\/v1\/customers\/([^/]+)\/statements$/);
      if (statementsMatch && request.method === "GET") {
        return await listStatements(request, env, session, decodeURIComponent(statementsMatch[1]));
      }
      if (statementsMatch && request.method === "POST") {
        return await createStatement(request, env, session, decodeURIComponent(statementsMatch[1]));
      }
      const statementMatch = url.pathname.match(/^\/v1\/statements\/(\d+)$/);
      if (statementMatch && request.method === "DELETE") {
        return await deleteStatement(request, env, session, Number(statementMatch[1]));
      }
      return json({ error: "Not found" }, 404, request);
    } catch (error) {
      const status = error.status || 500;
      if (status >= 500) console.error("Comp Manager API request failed", error);
      return json({ error: status >= 500 ? "Server error" : error.message }, status, request);
    }
  }
};

async function saveCompany(request, env) {
  const body = await readJson(request);
  const name = requiredString(body.company_name, "company_name", 200);
  const username = requiredString(body.username, "username", 128);
  const id = Number(body.company_id || 0);
  if (id > 0) {
    await turso(env, "UPDATE companies SET company_name = ?, phone = ?, email = ?, address = ?, username = ? WHERE company_id = ?",
      name, body.phone || "", body.email || "", body.address || "", username, id);
    if (body.password) await turso(env, "UPDATE accounts SET username = ?, password_hash = ?, phone = ?, email = ? WHERE company_id = ? AND role = 'COMPANY'", username, await hashPassword(body.password), body.phone || "", body.email || "", id);
    else await turso(env, "UPDATE accounts SET username = ?, phone = ?, email = ? WHERE company_id = ? AND role = 'COMPANY'", username, body.phone || "", body.email || "", id);
    return json({ saved: true, company_id: id }, 200, request);
  }
  const password = requiredString(body.password, "password", 1024);
  const rows = await turso(env,
    "INSERT INTO companies (company_name, phone, email, address, username) VALUES (?, ?, ?, ?, ?) RETURNING company_id",
    name, body.phone || "", body.email || "", body.address || "", username);
  const companyId = Number(rows[0]?.company_id);
  if (!Number.isSafeInteger(companyId)) throw new Error("Turso did not return the new company ID");
  await turso(env, "INSERT INTO accounts (username, password_hash, role, company_id, phone, email) VALUES (?, ?, 'COMPANY', ?, ?, ?)",
    username, await hashPassword(password), companyId, body.phone || "", body.email || "");
  return json({ saved: true, company_id: companyId }, 201, request);
}

async function deleteCompany(request, env, companyId) {
  await tursoTransaction(env, ["reader_imports", "accounts", "monthly_statement_requests", "sales_quotes",
    "sales_quote_sequences", "customer_visits", "statement_records", "customers", "record_types", "pdf_settings", "companies"]
    .map(table => ({ sql: `DELETE FROM ${table} WHERE company_id = ?`, args: [companyId] })));
  return json({ deleted: true }, 200, request);
}

async function createEmployeePreview(request, env, companySession, employeeId) {
  const rows = await turso(env,
    "SELECT a.employee_id, a.company_id, a.username, a.first_name, a.last_name, a.phone, a.email, a.permissions_json, a.allowed_customer_ids_json, a.allowed_days_json, c.company_name FROM accounts a JOIN companies c ON c.company_id = a.company_id WHERE a.employee_id = ? AND a.company_id = ? AND a.role = 'EMPLOYEE'",
    employeeId, requiredCompanyId(companySession));
  if (!rows.length) throw httpError(404, "Employee not found");
  const employee = rows[0];
  const ttl = positiveInteger(env.SESSION_TTL_SECONDS, SESSION_TTL_DEFAULT);
  const previewSession = {
    sub: String(employee.employee_id),
    username: employee.username,
    role: "EMPLOYEE",
    companyId: Number(employee.company_id),
    employeeId: Number(employee.employee_id),
    companyName: employee.company_name,
    firstName: employee.first_name,
    lastName: employee.last_name,
    phone: employee.phone,
    email: employee.email,
    permissions: normalizeEmployeePermissions(parseJsonArrayOrObject(employee.permissions_json, {})),
    allowedCustomerIds: parseJsonArrayOrObject(employee.allowed_customer_ids_json, []),
    allowedDays: parseJsonArrayOrObject(employee.allowed_days_json, []),
    preview: true,
    exp: Math.floor(Date.now() / 1000) + ttl
  };
  return json({ token: await signSession(previewSession, env), expiresIn: ttl }, 200, request);
}

async function saveEmployee(request, env, companyId) {
  const body = await readJson(request);
  const username = requiredString(body.username, "username", 128);
  const firstName = requiredString(body.first_name, "first_name", 120);
  const lastName = requiredString(body.last_name, "last_name", 120);
  const permissions = normalizeEmployeePermissions(body.permissions);
  const allowedCustomerIds = Array.isArray(body.allowed_customer_ids) ? body.allowed_customer_ids : [];
  const allowedDays = Array.isArray(body.allowed_days) ? body.allowed_days : [];
  const employeeId = Number(body.employee_id || 0);
  if (employeeId > 0) {
    const passwordHash = body.password ? await hashPassword(body.password) : null;
    await turso(env,
      "UPDATE accounts SET username = ?, first_name = ?, last_name = ?, phone = ?, email = ?, permissions_json = ?, allowed_customer_ids_json = ?, allowed_days_json = ? WHERE employee_id = ? AND company_id = ? AND role = 'EMPLOYEE'",
      username, firstName, lastName, body.phone || "", body.email || "", JSON.stringify(permissions),
      JSON.stringify(allowedCustomerIds), JSON.stringify(allowedDays), employeeId, companyId);
    if (passwordHash) await turso(env, "UPDATE accounts SET password_hash = ? WHERE employee_id = ? AND company_id = ? AND role = 'EMPLOYEE'", passwordHash, employeeId, companyId);
    return json({ saved: true, employee_id: employeeId }, 200, request);
  }
  const password = requiredString(body.password, "password", 1024);
  const rows = await turso(env,
    "INSERT INTO accounts (username, password_hash, role, company_id, employee_id, first_name, last_name, phone, email, permissions_json, allowed_customer_ids_json, allowed_days_json) " +
    "VALUES (?, ?, 'EMPLOYEE', ?, (SELECT COALESCE(MAX(employee_id), 0) + 1 FROM accounts WHERE company_id = ? AND role = 'EMPLOYEE'), ?, ?, ?, ?, ?, ?, ?) RETURNING employee_id",
    username, await hashPassword(password), companyId, companyId, firstName, lastName, body.phone || "", body.email || "",
    JSON.stringify(permissions), JSON.stringify(allowedCustomerIds), JSON.stringify(allowedDays));
  return json({ saved: true, employee_id: Number(rows[0]?.employee_id) }, 201, request);
}

function employeeResponse(row) {
  return {
    employee_id: row.employee_id,
    company_id: row.company_id,
    first_name: row.first_name,
    last_name: row.last_name,
    phone: row.phone,
    email: row.email,
    username: row.username,
    permissions: normalizeEmployeePermissions(parseJsonArrayOrObject(row.permissions_json, {})),
    allowed_customer_ids: parseJsonArrayOrObject(row.allowed_customer_ids_json, []),
    allowed_days: parseJsonArrayOrObject(row.allowed_days_json, [])
  };
}

function normalizeEmployeePermissions(value) {
  const permissions = value && typeof value === "object" && !Array.isArray(value) ? value : {};
  return { ...permissions, canAccessVisits: permissions.canAccessVisits === true,
    canAccessSalesQuote: permissions.canAccessSalesQuote === true,
    canAccessStatementReader: permissions.canAccessStatementReader === true,
    canAccessExpenses: permissions.canAccessExpenses === true };
}

export function normalizeExpenseKeywords(value) {
  if (!Array.isArray(value)) throw httpError(400, "keywords must be an array");
  const seen = new Set(), keywords = [];
  for (const raw of value) {
    if (typeof raw !== "string") throw httpError(400, "keywords must be strings");
    const keyword = raw.trim().replace(/\s+/g, " ");
    if (!keyword) continue;
    if (keyword.length > 80) throw httpError(400, "Each keyword must be 80 characters or fewer");
    if (seen.has(keyword.toLowerCase())) continue;
    seen.add(keyword.toLowerCase());
    keywords.push(keyword);
  }
  if (keywords.length > 100) throw httpError(400, "No more than 100 keywords can be saved");
  return keywords;
}

async function login(request, env) {
  const body = await readJson(request);
  const username = requiredString(body.username, "username", 128);
  const password = requiredString(body.password, "password", 1024);
  const rows = await turso(env,
    "SELECT a.account_id, a.username, a.password_hash, a.role, a.company_id, a.employee_id, a.first_name, a.last_name, a.phone, a.email, a.permissions_json, a.allowed_customer_ids_json, a.allowed_days_json, c.company_name FROM accounts a LEFT JOIN companies c ON c.company_id = a.company_id WHERE a.username = ? LIMIT 1",
    username);
  if (!rows.length || !(await verifyPassword(password, rows[0].password_hash))) {
    throw httpError(401, "Invalid username or password");
  }
  const account = rows[0];
  const ttl = positiveInteger(env.SESSION_TTL_SECONDS, SESSION_TTL_DEFAULT);
  const session = {
    sub: String(account.account_id),
    username: account.username,
    role: account.role,
    companyId: account.company_id == null ? null : Number(account.company_id),
    companyName: account.company_name || "",
    employeeId: account.employee_id == null ? null : Number(account.employee_id),
    firstName: account.first_name,
    lastName: account.last_name,
    phone: account.phone,
    email: account.email,
    permissions: account.role === "EMPLOYEE"
      ? normalizeEmployeePermissions(parseJsonArrayOrObject(account.permissions_json, {}))
      : parseJsonArrayOrObject(account.permissions_json, {}),
    allowedCustomerIds: parseJsonArrayOrObject(account.allowed_customer_ids_json, []),
    allowedDays: parseJsonArrayOrObject(account.allowed_days_json, []),
    exp: Math.floor(Date.now() / 1000) + ttl
  };
  return json({ token: await signSession(session, env), expiresIn: ttl, account: session }, 200, request);
}

async function listCustomers(request, env, session) {
  requirePermission(session, "canViewCustomers", "canViewCustomerDetails", "canAccessVisits", "canAccessSalesQuote", "canAccessStatementReader");
  const url = new URL(request.url);
  const query = (url.searchParams.get("q") || "").trim().toLowerCase();
  const orderById = url.searchParams.get("order") === "id";
  const filters = customerAccessSql(session);
  let sql = "SELECT customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes, starting_date, status, route_order FROM customers WHERE company_id = ?";
  const args = [requiredCompanyId(session)];
  sql += filters.sql;
  args.push(...filters.args);
  const serviceDay = (url.searchParams.get("service_day") || "").trim();
  if (serviceDay) {
    sql += " AND service_day = ?";
    args.push(serviceDay);
  }
  if (query) {
    sql += " AND (lower(first_name) LIKE ? OR lower(last_name) LIKE ? OR lower(address) LIKE ? OR lower(city) LIKE ? OR lower(state) LIKE ? OR lower(zip) LIKE ? OR lower(email) LIKE ? OR lower(customer_id) LIKE ? OR lower(phone) LIKE ? OR lower(first_name || ' ' || last_name) LIKE ?)";
    const pattern = `%${query}%`;
    args.push(...Array(10).fill(pattern));
  }
  sql += orderById
    ? " ORDER BY CASE WHEN customer_id GLOB '[0-9]*' THEN CAST(customer_id AS INTEGER) ELSE 999999999 END, customer_id"
    : " ORDER BY service_day, CASE WHEN route_order > 0 THEN 0 ELSE 1 END, route_order, last_name, first_name, customer_id";
  const result = await listResult(request, env, sql, args);
  if (Array.isArray(result) && !orderById && !query && !filters.sql) await healRouteOrders(env, requiredCompanyId(session), result);
  return json(result, 200, request);
}

function pagination(request) {
  const params = new URL(request.url).searchParams;
  if (!params.has("limit") && !params.has("offset")) return null;
  const limit = params.get("limit") ?? "50", offset = params.get("offset") ?? "0";
  if (!/^\d+$/.test(limit) || !/^\d+$/.test(offset) || !Number.isSafeInteger(Number(offset))
      || Number(limit) < 1 || Number(limit) > 500) throw httpError(400, "limit must be 1-500 and offset a nonnegative integer");
  return { limit: Number(limit), offset: Number(offset) };
}

async function listResult(request, env, sql, args, transform = row => row) {
  const page = pagination(request);
  if (!page) return (await turso(env, sql, ...args)).map(transform);
  const results = await tursoTransaction(env, [
    { sql: `SELECT COUNT(*) AS total FROM (${sql})`, args },
    { sql: `${sql} LIMIT ? OFFSET ?`, args: [...args, page.limit, page.offset] }
  ]);
  return { rows: results[1].map(transform), total: Number(results[0][0].total) };
}

function validateSetting(key, value) {
  if (requiredString(key, "key", 160) !== key) throw httpError(400, "Invalid setting key");
  const max = key === "logoData" ? 600000 : 16000;
  if (typeof value !== "string" || value.length > max) throw httpError(400, `value must be a string no longer than ${max} characters`);
  if (key === "logoData" && value && !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(value)) throw httpError(400, "logoData must be a PNG or JPEG data URL");
}

function settingStatement(companyId, key, value) {
  return { sql: "INSERT INTO pdf_settings (company_id, setting_key, setting_value) VALUES (?, ?, ?) ON CONFLICT(company_id, setting_key) DO UPDATE SET setting_value = excluded.setting_value",
    args: [companyId, key, value] };
}

async function healRouteOrders(env, companyId, rows) {
  const byDay = new Map();
  for (const r of rows) if (r.service_day) { if (!byDay.has(r.service_day)) byDay.set(r.service_day, []); byDay.get(r.service_day).push(r); }
  const statements = [];
  for (const [day, list] of byDay) {
    if (list.every((r, i) => Number(r.route_order) === i + 1)) continue;
    list.forEach((r, i) => {
      if (Number(r.route_order) !== i + 1) {
        r.route_order = i + 1;
        statements.push({ sql: "UPDATE customers SET route_order = ? WHERE company_id = ? AND customer_id = ?", args: [i + 1, companyId, r.customer_id] });
      }
    });
  }
  if (statements.length) { try { await tursoTransaction(env, statements); } catch (e) { /* display order still correct */ } }
}

async function placeInRoute(env, companyId, day, customerId, desired) {
  if (!day) return;
  const rows = await turso(env, "SELECT customer_id FROM customers WHERE company_id = ? AND service_day = ? ORDER BY CASE WHEN route_order > 0 THEN 0 ELSE 1 END, route_order, last_name, first_name", companyId, day);
  const ids = rows.map(r => String(r.customer_id)).filter(id => id !== String(customerId));
  const current = rows.findIndex(r => String(r.customer_id) === String(customerId));
  let index = desired > 0 ? desired - 1 : (current >= 0 ? current : ids.length);
  index = Math.max(0, Math.min(index, ids.length));
  if (customerId !== "") ids.splice(index, 0, String(customerId));
  await tursoTransaction(env, ids.map((id, i) => ({ sql: "UPDATE customers SET route_order = ? WHERE company_id = ? AND customer_id = ?", args: [i + 1, companyId, id] })));
}

async function getCustomer(request, env, session, customerId) {
  requirePermission(session, "canViewCustomerDetails", "canViewCustomers", "canAccessVisits", "canAccessSalesQuote", "canAccessStatementReader");
  const customer = await findAccessibleCustomer(env, session, customerId);
  return json(customer, 200, request);
}

function readerString(value, key, max, empty = true) {
  if (typeof value !== "string" || value.length > max || (!empty && !value.trim())) {
    throw httpError(400, `Invalid ${key}`);
  }
  return value;
}

function readerReference(value) {
  const reference = readerString(value, "reference", 160).trim().toUpperCase();
  // Only bank identifiers, never freeform payer names. Empty references are not identities.
  if (reference && (!/\d/.test(reference) || !/^[A-Z0-9 ._:/#-]+$/.test(reference))) {
    throw httpError(400, "reference must be an empty string or a bank identifier containing a digit");
  }
  return reference;
}

function readerDate(value, year, full = false) {
  if (typeof value !== "string") return "";
  const match = (full ? /^(\d{2})\/(\d{2})\/(\d{4})$/ : /^(\d{1,2})\/(\d{1,2})(?:\/(\d{4}))?$/).exec(value);
  if (!match || (full && !match[3])) return "";
  const result = `${match[1].padStart(2, "0")}/${match[2].padStart(2, "0")}/${match[3] || year}`;
  try { return quoteDate(result); } catch { return ""; }
}

function readerMoney(value) {
  if (typeof value !== "string" || !/^\$?(?:\d+|\d{1,3}(?:,\d{3})+)(?:\.\d{1,2})?$/.test(value)) return null;
  const amount = Number(value.replace(/[$,]/g, ""));
  return amount > 0 && amount <= 999999999.99 ? amount : null;
}

function readerAmount(value) {
  return typeof value === "number" && Number.isFinite(value) && value > 0 && value <= 999999999.99
    && Math.round(value * 100) / 100 === value;
}

async function readerBody(request, importing) {
  const body = await readJson(request, 4 * 1024 * 1024);
  if (Object.keys(body).some(key => !["document_hash", "rows"].includes(key))
      || !/^[a-f0-9]{64}$/.test(body.document_hash) || typeof body.document_hash !== "string"
      || !Array.isArray(body.rows) || body.rows.length > 1000) throw httpError(400, "Invalid reader document");
  const ids = new Set();
  const positions = new Set();
  for (const row of body.rows) {
    const keys = ["source_id", "page", "line", "text", "reference",
      ...(importing ? ["date", "amount", "customer_id", "override_duplicate"] : ["date_text", "deposit_text", "direction"])];
    if (!row || typeof row !== "object" || Object.keys(row).some(key => !keys.includes(key))) throw httpError(400, "Invalid reader row");
    readerString(row.source_id, "source_id", 128, false);
    readerString(row.text, "text", 4000);
    if (!Number.isSafeInteger(row.page) || row.page < 1 || !Number.isSafeInteger(row.line) || row.line < 1
        || ids.has(row.source_id) || positions.has(`${row.page}:${row.line}`)) throw httpError(400, "Invalid or duplicate source position");
    ids.add(row.source_id);
    positions.add(`${row.page}:${row.line}`);
    row.reference = readerReference(row.reference);
    if (importing) {
      readerString(row.customer_id, "customer_id", 80, false);
      if (!readerDate(row.date, 0, true) || !readerAmount(row.amount)
          || typeof row.override_duplicate !== "boolean") throw httpError(400, "Invalid selected payment");
    } else {
      readerString(row.date_text, "date_text", 32);
      readerString(row.deposit_text, "deposit_text", 80);
      if (!["deposit", "withdrawal", "unknown"].includes(row.direction)) throw httpError(400, "Invalid direction");
    }
  }
  return body;
}

function readerWords(value) {
  return value.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().match(/[\p{L}\p{N}]+/gu) || [];
}

function readerTextTokens(text) {
  const words = readerWords(text);
  const compact = new Set();
  for (let start = 0; start < words.length; start++) {
    for (let size = 1; size <= 12 && start + size <= words.length; size++) {
      const window = words.slice(start, start + size);
      compact.add(window.join(""));
    }
  }
  return { words: new Set(words), counts: readerWordCounts(words), compact };
}

function readerWordCounts(words) {
  const counts = new Map();
  for (const word of words) counts.set(word, (counts.get(word) || 0) + 1);
  return counts;
}

function readerNameTokens(customer) {
  const first = readerWords(customer.first_name), last = readerWords(customer.last_name);
  const name = [...first, ...last];
  return { valid: first.length > 0 && last.length > 0 && name.length <= 12, name,
    variants: [first, [first.join("")]].flatMap(given =>
      [last, [last.join("")]].map(surname => readerWordCounts([...given, ...surname]))),
    compact: [first.join("") + last.join(""), last.join("") + first.join("")] };
}

function readerMatch(text, name) {
  if (!name.valid) return { full: false, partial: false };
  return { full: name.compact.some(value => text.compact.has(value))
      || name.variants.some(counts => [...counts].every(([word, count]) => (text.counts.get(word) || 0) >= count)),
    partial: name.name.some(word => text.words.has(word)) };
}

async function previewReader(request, env, session) {
  requirePermission(session, "canAccessStatementReader");
  const body = await readerBody(request, false);
  const companyId = requiredCompanyId(session), filters = customerAccessSql(session);
  const customers = await turso(env, "SELECT customer_id, first_name, last_name FROM customers WHERE company_id = ?" + filters.sql,
    companyId, ...filters.args);
  const payments = await turso(env, "SELECT customer_id, record_date, amount FROM statement_records WHERE company_id = ? AND amount < 0 AND customer_id IN (SELECT customer_id FROM customers WHERE company_id = ?" + filters.sql + ")",
    companyId, companyId, ...filters.args);
  const audit = await turso(env, "SELECT document_hash, source_id, reference FROM reader_imports WHERE company_id = ?", companyId);
  const names = customers.map(customer => ({ customer, tokens: readerNameTokens(customer) }));
  const sourceIds = new Set(audit.filter(item => item.document_hash === body.document_hash).map(item => item.source_id));
  const references = new Set(audit.filter(item => item.reference).map(item => item.reference.toUpperCase()));
  const paymentFingerprints = new Set(payments.map(payment =>
    JSON.stringify([String(payment.customer_id), payment.record_date, Math.round(-Number(payment.amount) * 100)])));
  const year = new Date().getUTCFullYear();
  const fingerprints = new Map();
  const rows = body.rows.map(row => {
    const date = readerDate(row.date_text, year), amount = readerMoney(row.deposit_text);
    const result = { source_id: row.source_id, page: row.page, line: row.line, text: row.text,
      date, amount, customer_id: "", candidate_ids: [], status: "ignored", reason: "Not a dated deposit", reference: row.reference };
    if (sourceIds.has(row.source_id) || (row.reference && references.has(row.reference))) {
      return { ...result, status: "imported", reason: "Source or bank reference already imported" };
    }
    if (row.direction !== "deposit" || !date || amount === null) return result;
    const textTokens = readerTextTokens(row.text);
    const matches = names.map(({ customer, tokens }) => ({ customer, ...readerMatch(textTokens, tokens) }));
    const full = matches.filter(match => match.full);
    result.candidate_ids = matches.filter(match => match.full || match.partial).map(match => String(match.customer.customer_id));
    result.status = "review";
    result.reason = full.length > 1 ? "Ambiguous full name" : "Partial or unmatched name";
    if (full.length !== 1) return result;
    result.customer_id = String(full[0].customer.customer_id);
    result.candidate_ids = [result.customer_id];
    result.status = "ready";
    result.reason = "Unique full name";
    const fingerprint = JSON.stringify([result.customer_id, date, amount]);
    if (paymentFingerprints.has(JSON.stringify([result.customer_id, date, Math.round(amount * 100)]))) {
      result.status = "duplicate";
      result.reason = "Existing payment fingerprint";
    }
    if (!fingerprints.has(fingerprint)) fingerprints.set(fingerprint, []);
    fingerprints.get(fingerprint).push(result);
    return result;
  });
  for (const group of fingerprints.values()) if (group.length > 1) {
    for (const row of group) { row.status = "duplicate"; row.reason = "Repeated deposit fingerprint in document"; }
  }
  const referenced = new Set(rows.flatMap(row => [row.customer_id, ...row.candidate_ids]).filter(Boolean));
  return json({ rows, year, customers: customers.filter(customer => referenced.has(String(customer.customer_id))) }, 200, request);
}

async function importReader(request, env, session) {
  requirePermission(session, "canAccessStatementReader");
  requirePermission(session, "canEditStatements");
  if (session.preview) throw httpError(403, "Preview sessions cannot import payments");
  const body = await readerBody(request, true), companyId = requiredCompanyId(session);
  for (const id of new Set(body.rows.map(row => row.customer_id))) await findAccessibleCustomer(env, session, id);
  if (!body.rows.length) return json({ imported: 0, skipped: 0, rows: [] }, 200, request);
  const statements = [], filters = customerAccessSql(session);
  const now = new Date().toISOString();
  for (const row of body.rows) {
    const amount = -Math.round(row.amount * 100) / 100;
    statements.push({
      sql: `INSERT INTO reader_imports (company_id, document_hash, source_id, customer_id, record_id, record_date, amount, reference, source_text, imported_at)
        SELECT ?, ?, ?, ?, (SELECT MAX(id) + 1 FROM (SELECT COALESCE(MAX(record_id),0) AS id FROM statement_records WHERE company_id = ? UNION ALL SELECT COALESCE(MAX(record_id),0) FROM reader_imports WHERE company_id = ?)), ?, ?, NULLIF(?, ''), ?, ?
        WHERE EXISTS (SELECT 1 FROM customers WHERE company_id = ? AND customer_id = ?${filters.sql})
        AND NOT EXISTS (SELECT 1 FROM reader_imports WHERE company_id = ? AND ((document_hash = ? AND source_id = ?) OR (? <> '' AND reference = ? COLLATE NOCASE)))
        AND (? = 1 OR NOT EXISTS (SELECT 1 FROM statement_records WHERE company_id = ? AND customer_id = ? AND record_date = ? AND amount = ?))
        RETURNING record_id`,
      args: [companyId, body.document_hash, row.source_id, row.customer_id, companyId, companyId, row.date, amount, row.reference, row.text, now,
        companyId, row.customer_id, ...filters.args, companyId, body.document_hash, row.source_id, row.reference, row.reference,
        row.override_duplicate ? 1 : 0, companyId, row.customer_id, row.date, amount]
    });
    statements.push({
      sql: "INSERT INTO statement_records (company_id, record_id, customer_id, record_date, type, amount) SELECT company_id, record_id, customer_id, record_date, 'Zelle Payment', amount FROM reader_imports WHERE company_id = ? AND document_hash = ? AND source_id = ? AND changes() = 1",
      args: [companyId, body.document_hash, row.source_id]
    });
  }
  let results;
  try { results = await tursoTransaction(env, statements); }
  catch { throw httpError(409, "Import transaction failed; retry the same document and source IDs safely"); }
  const rows = body.rows.map((row, index) => results[index * 2].length
    ? { source_id: row.source_id, status: "imported", reason: "Payment imported", record_id: results[index * 2][0].record_id }
    : { source_id: row.source_id, status: "skipped", reason: "Duplicate source, bank reference, payment fingerprint, or customer no longer accessible" });
  const imported = rows.filter(row => row.status === "imported").length;
  return json({ imported, skipped: rows.length - imported, rows }, 200, request);
}

const QUOTE_COLUMNS = "quote_id, quote_number, customer_id, quote_date, notes, terms, lines_json, converted_at, created_at, updated_at";

function quoteResponse(row) {
  const { lines_json, ...quote } = row;
  return { ...quote, lines: JSON.parse(lines_json) };
}

function quoteNumber(id) {
  return `SQ-${String(id).padStart(6, "0")}`;
}

function quoteDate(value) {
  const match = typeof value === "string" && /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value);
  const date = match && new Date(`${match[3]}-${match[1]}-${match[2]}T00:00:00.000Z`);
  if (!date || !Number.isFinite(date.getTime()) || date.getUTCFullYear() < 1
      || date.getUTCMonth() + 1 !== Number(match[1]) || date.getUTCDate() !== Number(match[2])) {
    throw httpError(400, "Quote dates must be valid MM/DD/YYYY dates");
  }
  return value;
}

function quoteText(value, name, limit) {
  if (typeof value !== "string" || value.length > limit) throw httpError(400, `${name} must be a string of at most ${limit} characters`);
  return value;
}

function validateQuote(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)
      || Object.keys(body).some(key => !["quote_date", "notes", "terms", "lines"].includes(key))) {
    throw httpError(400, "Invalid quote fields");
  }
  const quote_date = quoteDate(body.quote_date);
  const notes = quoteText(body.notes, "notes", 16000);
  const terms = quoteText(body.terms, "terms", 16000);
  if (!Array.isArray(body.lines) || body.lines.length < 1 || body.lines.length > 200) throw httpError(400, "A quote requires 1 to 200 lines");
  let totalCents = 0;
  const lines = body.lines.map(line => {
    if (!line || typeof line !== "object" || Array.isArray(line)
        || Object.keys(line).some(key => !["date", "type", "description", "amount"].includes(key))) throw httpError(400, "Invalid quote line");
    const date = quoteDate(line.date);
    const type = requiredString(line.type, "type", 128);
    const description = quoteText(line.description, "description", 2000);
    const amount = line.amount;
    const cents = Math.round(amount * 100);
    if (typeof amount !== "number" || !Number.isFinite(amount) || amount === 0
        || Math.abs(amount) > 999999999.99 || Math.abs(amount * 100 - cents) > 0.00002
        || Number(amount.toFixed(2)) !== amount) throw httpError(400, "Quote amounts must be nonzero money with at most 2 decimals and magnitude at most 999999999.99");
    totalCents += cents;
    return { date, type, description, amount: cents / 100 };
  });
  if (!Number.isSafeInteger(totalCents)) throw httpError(400, "Quote total is too large");
  return { quote_date, notes, terms, lines };
}

async function handleQuotes(request, env, session, customerId, quoteId, convert) {
  requirePermission(session, "canAccessSalesQuote");
  if (convert) requirePermission(session, "canEditStatements");
  await findAccessibleCustomer(env, session, customerId);
  const companyId = requiredCompanyId(session);
  const scope = [companyId, customerId, quoteId];
  if (quoteId === null && request.method === "GET") {
    const summary = new URL(request.url).searchParams.get("summary") === "1";
    const columns = summary ? "quote_id, quote_number, customer_id, quote_date, converted_at, created_at, updated_at, (SELECT COALESCE(SUM(json_extract(value, '$.amount')), 0) FROM json_each(lines_json)) AS total" : QUOTE_COLUMNS;
    return json(await listResult(request, env, `SELECT ${columns} FROM sales_quotes WHERE company_id = ? AND customer_id = ? ORDER BY quote_id DESC`,
      [companyId, customerId], summary ? row => row : quoteResponse), 200, request);
  }
  if (quoteId === null && request.method === "POST") {
    const quote = validateQuote(await readJson(request, 2 * 1024 * 1024));
    const now = new Date().toISOString();
    const results = await tursoTransaction(env, [
      { sql: "INSERT INTO sales_quote_sequences (company_id, next_id) VALUES (?, 2) ON CONFLICT(company_id) DO UPDATE SET next_id = next_id + 1", args: [companyId] },
      { sql: `INSERT INTO sales_quotes (company_id, quote_id, quote_number, customer_id, quote_date, notes, terms, lines_json, created_at, updated_at)
          SELECT ?, next_id - 1, printf('SQ-%06d', next_id - 1), ?, ?, ?, ?, ?, ?, ? FROM sales_quote_sequences WHERE company_id = ? RETURNING ${QUOTE_COLUMNS}`,
        args: [companyId, customerId, quote.quote_date, quote.notes, quote.terms, JSON.stringify(quote.lines), now, now, companyId] }
    ]);
    return json(quoteResponse(results[1][0]), 201, request);
  }
  if (!Number.isSafeInteger(quoteId) || quoteId < 1) throw httpError(404, "Quote not found");
  const existing = await turso(env, `SELECT ${QUOTE_COLUMNS} FROM sales_quotes WHERE company_id = ? AND customer_id = ? AND quote_id = ?`, ...scope);
  if (!existing.length) throw httpError(404, "Quote not found");
  if (!convert && request.method === "GET") return json(quoteResponse(existing[0]), 200, request);
  if (existing[0].converted_at !== null) throw httpError(409, "Converted quotes are read-only");
  if (convert && request.method === "POST") {
    const now = new Date().toISOString();
    const access = customerAccessSql(session);
    // Read the saved lines and claim the conversion within one serialized transaction.
    const results = await tursoTransaction(env, [
      { sql: `WITH base AS MATERIALIZED (SELECT COALESCE(MAX(record_id), 0) AS last_id FROM statement_records WHERE company_id = ?)
          INSERT INTO statement_records (company_id, record_id, customer_id, record_date, type, amount)
          SELECT q.company_id, base.last_id + CAST(line.key AS INTEGER) + 1,
            q.customer_id, json_extract(line.value, '$.date'),
            CASE WHEN json_extract(line.value, '$.description') = '' OR json_extract(line.value, '$.description') = json_extract(line.value, '$.type')
              THEN json_extract(line.value, '$.type')
              ELSE json_extract(line.value, '$.type') || ': ' || json_extract(line.value, '$.description') END,
            json_extract(line.value, '$.amount')
          FROM sales_quotes q, json_each(q.lines_json) line, base
          WHERE q.company_id = ? AND q.customer_id = ? AND q.quote_id = ? AND q.converted_at IS NULL
            AND EXISTS (SELECT 1 FROM customers WHERE company_id = q.company_id AND customer_id = q.customer_id${access.sql})`,
        args: [companyId, ...scope, ...access.args] },
      { sql: `UPDATE sales_quotes SET converted_at = ?, updated_at = ? WHERE company_id = ? AND customer_id = ? AND quote_id = ? AND converted_at IS NULL AND changes() > 0 RETURNING ${QUOTE_COLUMNS}`,
        args: [now, now, ...scope] }
    ]);
    if (!results[1].length) throw httpError(409, "Quote is already converted or no longer accessible");
    return json(quoteResponse(results[1][0]), 200, request);
  }
  if (!convert && request.method === "PUT") {
    const quote = validateQuote(await readJson(request, 2 * 1024 * 1024));
    const rows = await turso(env, `UPDATE sales_quotes SET quote_date = ?, notes = ?, terms = ?, lines_json = ?, updated_at = ? WHERE company_id = ? AND customer_id = ? AND quote_id = ? AND converted_at IS NULL RETURNING ${QUOTE_COLUMNS}`,
      quote.quote_date, quote.notes, quote.terms, JSON.stringify(quote.lines), new Date().toISOString(), ...scope);
    if (!rows.length) throw httpError(409, "Converted quotes are read-only");
    return json(quoteResponse(rows[0]), 200, request);
  }
  if (!convert && request.method === "DELETE") {
    const rows = await turso(env, "DELETE FROM sales_quotes WHERE company_id = ? AND customer_id = ? AND quote_id = ? AND converted_at IS NULL RETURNING quote_id", ...scope);
    if (!rows.length) throw httpError(409, "Converted quotes are read-only");
    return json({ deleted: true }, 200, request);
  }
  throw httpError(405, "Method not allowed");
}

async function listVisits(request, env, session, customerId) {
  requirePermission(session, "canAccessVisits");
  await findAccessibleCustomer(env, session, customerId);
  const summary = new URL(request.url).searchParams.get("summary") === "1";
  return json(await listResult(request, env,
    `SELECT visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes, ${summary ? "(photo_data <> '') AS has_photo" : "photo_data"} FROM customer_visits WHERE company_id = ? AND customer_id = ? ORDER BY visited_at DESC, visit_id DESC`,
    [requiredCompanyId(session), customerId], summary ? row => ({ ...row, has_photo: Boolean(row.has_photo) }) : row => row), 200, request);
}

async function getVisit(request, env, session, customerId, visitId) {
  requirePermission(session, "canAccessVisits");
  await findAccessibleCustomer(env, session, customerId);
  const rows = await turso(env, "SELECT visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes, photo_data FROM customer_visits WHERE company_id = ? AND customer_id = ? AND visit_id = ?",
    requiredCompanyId(session), customerId, visitId);
  if (!rows.length) throw httpError(404, "Visit not found");
  return json(rows[0], 200, request);
}
async function createVisit(request, env, session, customerId) {
  requirePermission(session, "canAccessVisits");
  await findAccessibleCustomer(env, session, customerId);
  const visit = validateVisit(await readJson(request, 700 * 1024));
  const companyId = requiredCompanyId(session);
  const rows = await turso(env,
    "INSERT INTO customer_visits (company_id, visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes, photo_data) VALUES (?, (SELECT COALESCE(MAX(visit_id), 0) + 1 FROM customer_visits WHERE company_id = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes, photo_data",
    companyId, companyId, customerId, new Date().toISOString(), visit.ph, visit.chlo, visit.stb, visit.alk, visit.cal, visit.notes, visit.photo_data);
  if (!rows.length) throw new Error("Turso did not return the new visit");
  return json(rows[0], 201, request);
}

async function updateVisit(request, env, session, customerId, visitId) {
  requirePermission(session, "canAccessVisits");
  await findAccessibleCustomer(env, session, customerId);
  if (!Number.isSafeInteger(visitId) || visitId < 1) throw httpError(404, "Visit not found");
  const body = await readJson(request, 700 * 1024);
  const visit = validateVisit(body);
  const rows = await turso(env,
    "UPDATE customer_visits SET ph = ?, chlo = ?, stb = ?, alk = ?, cal = ?, notes = ?, photo_data = COALESCE(?, photo_data) WHERE company_id = ? AND customer_id = ? AND visit_id = ? RETURNING visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes, photo_data",
    visit.ph, visit.chlo, visit.stb, visit.alk, visit.cal, visit.notes,
    body.photo_data === undefined ? null : visit.photo_data,
    requiredCompanyId(session), customerId, visitId);
  if (!rows.length) throw httpError(404, "Visit not found");
  return json(rows[0], 200, request);
}

async function createCustomer(request, env, session) {
  requirePermission(session, "canEditCustomers");
  const customer = await readJson(request);
  validateCustomer(customer);
  if (session.role === "EMPLOYEE" && session.allowedDays?.length
      && !session.allowedDays.includes(customer.service_day)) {
    throw httpError(403, "You are not allowed to create customers for that service day");
  }
  if (session.role === "EMPLOYEE" && session.allowedCustomerIds?.length && !session.allowedDays?.length) {
    throw httpError(403, "This employee is restricted to assigned customers and cannot create a new customer");
  }
  const companyId = requiredCompanyId(session);
  await turso(env,
    "INSERT INTO customers (company_id, customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes, starting_date, status, route_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    companyId, customer.customer_id, customer.first_name, customer.last_name, customer.address || "", customer.city || "",
    customer.state || "", customer.zip || "", customer.phone || "", customer.email || "", customer.service_day || "",
    Number(customer.amount_charged || 0), customer.notes || "", customer.starting_date || "", customer.status === "Inactive" ? "Inactive" : "Active", routeOrderFor(customer));
  await turso(env, "SELECT 1");
  await placeInRoute(env, companyId, customer.service_day || "", customer.customer_id, routeOrderFor(customer));
  return json({ saved: true, customer_id: customer.customer_id }, 201, request);
}

async function updateCustomer(request, env, session, customerId) {
  requirePermission(session, "canEditCustomers");
  const existing = await findAccessibleCustomer(env, session, customerId);
  const customer = await readJson(request);
  validateCustomer({ ...existing, ...customer, customer_id: customerId });
  await turso(env,
    "UPDATE customers SET first_name = ?, last_name = ?, address = ?, city = ?, state = ?, zip = ?, phone = ?, email = ?, service_day = ?, amount_charged = ?, notes = ?, starting_date = ?, status = ?, route_order = ? WHERE company_id = ? AND customer_id = ?",
    customer.first_name, customer.last_name, customer.address || "", customer.city || "", customer.state || "",
    customer.zip || "", customer.phone || "", customer.email || "", customer.service_day || "",
    Number(customer.amount_charged || 0), customer.notes || "", customer.starting_date || "", customer.status === "Inactive" ? "Inactive" : "Active", routeOrderFor({ ...customer, route_order: customer.route_order ?? existing.route_order }), requiredCompanyId(session), customerId);
  const companyId = requiredCompanyId(session);
  const newDay = customer.service_day || "";
  const sameDay = newDay === (existing.service_day || "");
  await placeInRoute(env, companyId, newDay, customerId, sameDay ? Number(customer.route_order ?? existing.route_order) : Number(customer.route_order || 0));
  if (!sameDay) await placeInRoute(env, companyId, existing.service_day || "", "", 0).catch(() => {});
  return json({ saved: true }, 200, request);
}

function routeOrderFor(customer) {
  const n = Math.floor(Number(customer.route_order || 0));
  return customer.service_day && Number.isFinite(n) && n > 0 ? Math.min(n, 1000000) : 0;
}

async function reorderCustomers(request, env, session) {
  requirePermission(session, "canEditCustomers");
  const body = await readJson(request);
  const day = requiredString(body.service_day, "service_day", 20);
  if (!Array.isArray(body.customer_ids) || body.customer_ids.length > 5000) throw httpError(400, "customer_ids must be an array");
  await turso(env, "SELECT 1");
  const companyId = requiredCompanyId(session);
  const statements = [];
  let position = 1;
  for (const id of body.customer_ids) {
    statements.push({ sql: "UPDATE customers SET route_order = ? WHERE company_id = ? AND customer_id = ? AND service_day = ?", args: [position++, companyId, String(id), day] });
  }
  if (statements.length) await tursoTransaction(env, statements);
  return json({ saved: true }, 200, request);
}

async function deleteCustomer(request, env, session, customerId) {
  requirePermission(session, "canEditCustomers");
  await findAccessibleCustomer(env, session, customerId);
  await tursoTransaction(env, ["reader_imports", "sales_quotes", "customer_visits", "statement_records", "customers"]
    .map(table => ({ sql: `DELETE FROM ${table} WHERE company_id = ? AND customer_id = ?`, args: [requiredCompanyId(session), customerId] })));
  return json({ deleted: true }, 200, request);
}

async function listStatements(request, env, session, customerId) {
  requirePermission(session, "canViewStatements");
  await findAccessibleCustomer(env, session, customerId);
  const page = pagination(request), args = [requiredCompanyId(session), customerId];
  if (!page) return json(await turso(env, "SELECT record_id, customer_id, record_date, type, amount FROM statement_records WHERE company_id = ? AND customer_id = ? ORDER BY record_id", ...args), 200, request);
  const tail = "substr(trim(record_date), instr(trim(record_date), '/') + 1)";
  const month = "substr(trim(record_date), 1, instr(trim(record_date), '/') - 1)";
  const day = `substr(${tail}, 1, instr(${tail}, '/') - 1)`;
  const year = `substr(${tail}, instr(${tail}, '/') + 1)`;
  const isoDate = `printf('%04d-%02d-%02d', CAST(${year} AS INTEGER), CAST(${month} AS INTEGER), CAST(${day} AS INTEGER))`;
  const chronological = `CASE WHEN length(${month}) BETWEEN 1 AND 2 AND ${month} NOT GLOB '*[^0-9]*'
    AND length(${day}) BETWEEN 1 AND 2 AND ${day} NOT GLOB '*[^0-9]*'
    AND length(${year}) BETWEEN 1 AND 4 AND ${year} NOT GLOB '*[^0-9]*' AND CAST(${year} AS INTEGER) > 0
    AND date(${isoDate}, '+0 days') = ${isoDate}
    THEN ${isoDate} ELSE '9999-99-99' END`;
  const results = await tursoTransaction(env, [
    { sql: "SELECT COUNT(*) AS total, COALESCE(SUM(amount), 0) AS balance FROM statement_records WHERE company_id = ? AND customer_id = ?", args },
    { sql: `SELECT record_id, customer_id, record_date, type, amount,
        SUM(amount) OVER (ORDER BY ${chronological}, CASE WHEN amount >= 0 THEN 0 ELSE 1 END, record_id ROWS UNBOUNDED PRECEDING) AS run
        FROM statement_records WHERE company_id = ? AND customer_id = ?
        ORDER BY ${chronological} DESC, CASE WHEN amount >= 0 THEN 0 ELSE 1 END DESC, record_id DESC LIMIT ? OFFSET ?`,
      args: [...args, page.limit, page.offset] }
  ]);
  return json({ rows: results[1], total: Number(results[0][0].total), balance: Number(results[0][0].balance) }, 200, request);
}

async function createMonthlyStatements(request, env, session) {
  requirePermission(session, "canEditStatements");
  requirePermission(session, "canViewStatements");
  const body = await readJson(request);
  if (Object.keys(body).some(key => !["request_id", "date", "type"].includes(key))
      || typeof body.request_id !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.request_id)) {
    throw httpError(400, "request_id must be a UUID");
  }
  const date = quoteDate(body.date), type = requiredString(body.type, "type", 128);
  const requestId = body.request_id.toLowerCase(), companyId = requiredCompanyId(session);
  const previous = await turso(env, "SELECT record_date, type, added FROM monthly_statement_requests WHERE company_id = ? AND request_id = ?", companyId, requestId);
  const response = saved => {
    if (saved.record_date !== date || saved.type !== type) throw httpError(409, "request_id already used with a different request body");
    return json({ added: saved.added, request_id: requestId }, 200, request);
  };
  if (previous.length) return response(previous[0]);
  const types = await turso(env, `SELECT type_name,
      (SELECT setting_value FROM pdf_settings WHERE company_id = ? AND setting_key = 'recordTypeCreditKeywords') AS credit_keywords
      FROM record_types WHERE company_id = ? AND type_name = ?`, companyId, companyId, type);
  const keywords = String(types[0]?.credit_keywords || "payment, paid, credit, refund, discount, adjustment, rebate, deposit, return, transfer in, check, cheque")
    .split(/[,\n]/).map(value => value.trim().toLowerCase()).filter(Boolean);
  if (!types.length || !keywords.some(keyword => type.toLowerCase().includes(keyword))) {
    throw httpError(400, "Monthly statements require an existing credit record type");
  }
  const access = customerAccessSql(session), claim = crypto.randomUUID();
  const results = await tursoTransaction(env, [
    { sql: "INSERT INTO monthly_statement_requests (company_id, request_id, record_date, type, added, claim) VALUES (?, ?, ?, ?, 0, ?) ON CONFLICT(company_id, request_id) DO NOTHING",
      args: [companyId, requestId, date, type, claim] },
    { sql: `WITH base AS MATERIALIZED (SELECT COALESCE(MAX(record_id), 0) AS last_id FROM statement_records WHERE company_id = ?),
        eligible AS MATERIALIZED (SELECT customer_id, amount_charged FROM customers WHERE company_id = ? AND status <> 'Inactive'${access.sql})
        INSERT INTO statement_records (company_id, record_id, customer_id, record_date, type, amount)
        SELECT ?, base.last_id + ROW_NUMBER() OVER (ORDER BY eligible.customer_id), eligible.customer_id, ?, ?, eligible.amount_charged
        FROM eligible, base WHERE EXISTS (SELECT 1 FROM monthly_statement_requests WHERE company_id = ? AND request_id = ? AND claim = ?)`,
      args: [companyId, companyId, ...access.args, companyId, date, type, companyId, requestId, claim] },
    { sql: "UPDATE monthly_statement_requests SET added = changes() WHERE company_id = ? AND request_id = ? AND claim = ?",
      args: [companyId, requestId, claim] },
    { sql: "SELECT record_date, type, added FROM monthly_statement_requests WHERE company_id = ? AND request_id = ?", args: [companyId, requestId] }
  ]);
  return response(results[3][0]);
}
async function createStatement(request, env, session, customerId) {
  requirePermission(session, "canEditStatements");
  await findAccessibleCustomer(env, session, customerId);
  const body = await readJson(request);
  const date = requiredString(body.record_date, "record_date", 32);
  const type = requiredString(body.type, "type", 128);
  const amount = Number(body.amount);
  if (!Number.isFinite(amount)) throw httpError(400, "amount must be numeric");
  const companyId = requiredCompanyId(session);
  const rows = await turso(env,
    "INSERT INTO statement_records (company_id, record_id, customer_id, record_date, type, amount) VALUES (?, (SELECT COALESCE(MAX(record_id), 0) + 1 FROM statement_records WHERE company_id = ?), ?, ?, ?, ?) RETURNING record_id",
    companyId, companyId, customerId, date, type, amount);
  return json({ saved: true, record_id: rows[0]?.record_id ?? null }, 201, request);
}

async function deleteStatement(request, env, session, recordId) {
  requirePermission(session, "canEditStatements");
  const rows = await turso(env,
    "DELETE FROM statement_records WHERE company_id = ? AND record_id = ? RETURNING record_id",
    requiredCompanyId(session), recordId);
  if (!rows.length) throw httpError(404, "Statement record not found");
  return json({ deleted: true }, 200, request);
}

async function findAccessibleCustomer(env, session, customerId) {
  const filters = customerAccessSql(session);
  const rows = await turso(env,
    "SELECT customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes, starting_date, status, route_order FROM customers WHERE company_id = ? AND customer_id = ?" + filters.sql + " LIMIT 1",
    requiredCompanyId(session), customerId, ...filters.args);
  if (!rows.length) throw httpError(404, "Customer not found or not assigned to this employee");
  return rows[0];
}

function customerAccessSql(session) {
  if (session.role !== "EMPLOYEE") return { sql: "", args: [] };
  const ids = Array.isArray(session.allowedCustomerIds) ? session.allowedCustomerIds.filter(isSimpleValue) : [];
  const days = Array.isArray(session.allowedDays) ? session.allowedDays.filter(isSimpleValue) : [];
  if (!ids.length && !days.length) return { sql: "", args: [] };
  const clauses = [];
  const args = [];
  if (ids.length) {
    clauses.push(`customer_id IN (${ids.map(() => "?").join(",")})`);
    args.push(...ids);
  }
  if (days.length) {
    clauses.push(`service_day IN (${days.map(() => "?").join(",")})`);
    args.push(...days);
  }
  return { sql: ` AND (${clauses.join(" OR ")})`, args };
}

function requirePermission(session, ...permissions) {
  if (session.role === "MASTER") throw httpError(403, "Master accounts do not access company records through this endpoint");
  if (session.role === "COMPANY") return;
  if (!permissions.some(permission => session.permissions?.[permission] === true)) {
    throw httpError(403, "Permission denied");
  }
}

function requireMaster(session) {
  if (session.role !== "MASTER") throw httpError(403, "Master access required");
}

function requireCompanyManager(session, companyId) {
  if (session.role === "MASTER") return;
  if (session.role === "COMPANY" && Number(session.companyId) === companyId) return;
  throw httpError(403, "Company management access required");
}

function requiredCompanyId(session) {
  const companyId = Number(session.companyId);
  if (!Number.isSafeInteger(companyId) || companyId < 1) throw httpError(403, "Company scope is missing");
  return companyId;
}

async function authenticateRequest(request, env) {
  const authorization = request.headers.get("Authorization") || "";
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  if (!match) throw httpError(401, "Bearer session token required");
  const session = await verifySession(match[1], env);
  if (!session || session.exp <= Math.floor(Date.now() / 1000)) throw httpError(401, "Session expired");
  let activeRows;
  if (session.preview && session.role === "EMPLOYEE") {
    activeRows = await turso(env, "SELECT 1 AS active FROM accounts WHERE employee_id = ? AND company_id = ? AND role = 'EMPLOYEE' LIMIT 1", session.employeeId, session.companyId);
  } else if (session.preview && session.role === "COMPANY") {
    activeRows = await turso(env, "SELECT 1 AS active FROM companies WHERE company_id = ? LIMIT 1", session.companyId);
  } else {
    activeRows = await turso(env, "SELECT 1 AS active FROM accounts WHERE account_id = ? AND username = ? AND role = ? LIMIT 1", session.sub, session.username, session.role);
  }
  if (!activeRows.length) throw httpError(401, "Account is no longer active");
  return session;
}

async function signSession(payload, env) {
  const secret = requiredSecret(env.SESSION_SIGNING_SECRET, "SESSION_SIGNING_SECRET");
  const header = base64UrlEncode(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = base64UrlEncode(JSON.stringify(payload));
  const data = `${header}.${body}`;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return `${data}.${base64UrlEncodeBytes(new Uint8Array(signature))}`;
}

async function verifySession(token, env) {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const secret = requiredSecret(env.SESSION_SIGNING_SECRET, "SESSION_SIGNING_SECRET");
  const data = `${parts[0]}.${parts[1]}`;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  let signature;
  let payload;
  try {
    signature = base64UrlDecodeBytes(parts[2]);
    payload = JSON.parse(new TextDecoder().decode(base64UrlDecodeBytes(parts[1])));
  } catch {
    return null;
  }
  const valid = await crypto.subtle.verify("HMAC", key, signature, new TextEncoder().encode(data));
  return valid ? payload : null;
}

async function verifyPassword(password, stored) {
  if (typeof stored !== "string") return false;
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 10000 || iterations > 1000000) return false;
  try {
    const keyMaterial = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: base64UrlDecodeBytes(parts[2]), iterations, hash: "SHA-256" }, keyMaterial, 256);
    return constantTimeEqual(base64UrlEncodeBytes(new Uint8Array(bits)), parts[3]);
  } catch {
    return false;
  }
}

async function hashPassword(password) {
  if (typeof password !== "string" || password.length < 1 || password.length > 1024) throw httpError(400, "Invalid password");
  const iterations = 100000;
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const keyMaterial = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations, hash: "SHA-256" }, keyMaterial, 256);
  return `pbkdf2-sha256$${iterations}$${base64UrlEncodeBytes(salt)}$${base64UrlEncodeBytes(new Uint8Array(bits))}`;
}

const BACKUP_TABLES = {
  customers: ["customer_id", "first_name", "last_name", "address", "city", "state", "zip", "phone", "email", "service_day", "amount_charged", "notes", "starting_date", "status", "route_order"],
  customer_visits: ["visit_id", "customer_id", "visited_at", "ph", "chlo", "stb", "alk", "cal", "notes", "photo_data"],
  statement_records: ["record_id", "customer_id", "record_date", "type", "amount"],
  reader_imports: ["document_hash", "source_id", "customer_id", "record_id", "record_date", "amount", "reference", "source_text", "imported_at"],
  monthly_statement_requests: ["request_id", "record_date", "type", "added", "claim"],
  sales_quotes: QUOTE_COLUMNS.split(", "),
  sales_quote_sequences: ["next_id"],
  record_types: ["type_name"],
  pdf_settings: ["setting_key", "setting_value"]
};

const VISIT_ENUMS = {
  ph: ["7.0", "7.2", "7.4", "7.6", "7.8", "8.0"],
  chlo: ["1-2", "2-4", "3-6", "5-10", "7.5-15", "10-20"],
  stb: ["100", "90", "80", "70", "60", "50", "40", "30"]
};

function validateVisit(body) {
  for (const [field, values] of Object.entries(VISIT_ENUMS)) {
    if (typeof body[field] !== "string" || !values.includes(body[field])) {
      throw httpError(400, `${field} must be one of the allowed values`);
    }
  }
  for (const field of ["alk", "cal"]) {
    if (body[field] == null || (typeof body[field] === "string" && !body[field].trim())) continue;
    if (!Number.isSafeInteger(body[field]) || body[field] < 1 || body[field] > 2147483647) {
      throw httpError(400, `${field} must be a positive whole number no greater than 2147483647`);
    }
  }
  if (typeof body.notes !== "string" || body.notes.length > 16000) {
    throw httpError(400, "notes must be a string no longer than 16000 characters");
  }
  const photo = body.photo_data === undefined ? "" : body.photo_data;
  if (typeof photo !== "string" || photo.length > 600000
      || (photo && !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(photo))) {
    throw httpError(400, "photo_data must be a PNG or JPEG data URL no longer than 600000 characters, or an empty string");
  }
  return {
    ph: body.ph, chlo: body.chlo, stb: body.stb,
    alk: typeof body.alk === "number" ? body.alk : null,
    cal: typeof body.cal === "number" ? body.cal : null,
    notes: body.notes, photo_data: photo
  };
}

function validateBackupVisits(visits, customerIds) {
  const visitIds = new Set();
  for (const row of visits) {
    if (!row || typeof row !== "object" || Array.isArray(row)
        || !Number.isSafeInteger(row.visit_id) || row.visit_id < 1
        || !customerIds.has(String(row.customer_id))
        || typeof row.visited_at !== "string"
        || !Number.isFinite(Date.parse(row.visited_at))
        || new Date(row.visited_at).toISOString() !== row.visited_at) {
      throw httpError(400, "Backup has an invalid visit");
    }
    if (visitIds.has(row.visit_id)) throw httpError(400, "Backup has duplicate visit IDs");
    visitIds.add(row.visit_id);
    Object.assign(row, validateVisit(row));
  }
}

async function exportCompanyBackup(env, companyId) {
  const backup = { format: "comp-manager-backup", version: 1, company_id: companyId, created_at: new Date().toISOString() };
  const tables = Object.entries(BACKUP_TABLES);
  const results = await tursoTransaction(env, tables.map(([table, columns]) =>
    ({ sql: `SELECT ${columns.join(", ")} FROM ${table} WHERE company_id = ?`, args: [companyId] })));
  tables.forEach(([table], index) => { backup[table] = results[index]; });
  return backup;
}

function validateBackupQuotes(backup, customerIds) {
  const ids = new Set();
  for (const row of backup.sales_quotes) {
    if (!row || !Number.isSafeInteger(row.quote_id) || row.quote_id < 1 || row.quote_id >= Number.MAX_SAFE_INTEGER
        || ids.has(row.quote_id) || row.quote_number !== quoteNumber(row.quote_id)
        || typeof row.customer_id !== "string" || !customerIds.has(row.customer_id)) throw httpError(400, "Backup has invalid or duplicate quote IDs");
    ids.add(row.quote_id);
    let lines;
    try { lines = JSON.parse(row.lines_json); } catch { throw httpError(400, "Backup has invalid quote lines"); }
    const quote = validateQuote({ quote_date: row.quote_date, notes: row.notes, terms: row.terms, lines });
    row.lines_json = JSON.stringify(quote.lines);
    const validIso = value => typeof value === "string" && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
    if (!validIso(row.created_at) || !validIso(row.updated_at) || row.updated_at < row.created_at
        || (row.converted_at !== null && (!validIso(row.converted_at) || row.converted_at < row.created_at || row.converted_at > row.updated_at))) {
      throw httpError(400, "Backup has invalid quote conversion state or timestamps");
    }
  }
  if (backup.sales_quote_sequences.length > 1 || backup.sales_quote_sequences.some(row =>
    !row || !Number.isSafeInteger(row.next_id) || row.next_id < 1
      || [...ids].some(id => id >= row.next_id))) throw httpError(400, "Backup has an invalid quote sequence");
}

async function restoreCompanyBackup(request, env, companyId) {
  const contentLength = Number(request.headers.get("Content-Length") || 0);
  if (contentLength > 50 * 1024 * 1024) throw httpError(413, "Backup is too large");
  let backup;
  try { backup = await request.json(); } catch { throw httpError(400, "Backup file is not valid JSON"); }
  if (!backup || backup.format !== "comp-manager-backup" || backup.version !== 1) throw httpError(400, "This is not a Comp Manager cloud backup");
  if (Number(backup.company_id) !== companyId) throw httpError(400, "This backup belongs to a different company");
  for (const table of Object.keys(BACKUP_TABLES)) {
    if (["customer_visits", "sales_quotes", "sales_quote_sequences", "reader_imports", "monthly_statement_requests"].includes(table) && backup[table] === undefined) backup[table] = [];
    if (!Array.isArray(backup[table])) throw httpError(400, `Backup is missing ${table}`);
  }
  const customerIds = new Set(backup.customers.map(row => String(row.customer_id)));
  validateBackupVisits(backup.customer_visits, customerIds);
  validateBackupQuotes(backup, customerIds);
  const monthlyIds = new Set();
  for (const row of backup.monthly_statement_requests) {
    if (!row || typeof row.request_id !== "string"
        || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(row.request_id)
        || monthlyIds.has(row.request_id) || !readerDate(row.record_date, 0, true)
        || !Number.isSafeInteger(row.added) || row.added < 0
        || typeof row.claim !== "string" || !row.claim || row.claim.length > 80) throw httpError(400, "Invalid monthly request audit");
    requiredString(row.type, "type", 128);
    monthlyIds.add(row.request_id);
  }
  const auditIds = new Set(), auditRefs = new Set();
  for (const row of backup.reader_imports) {
    if (!row || typeof row.document_hash !== "string" || !/^[a-f0-9]{64}$/.test(row.document_hash)
        || !Number.isSafeInteger(row.record_id) || row.record_id < 1
        || !readerDate(row.record_date, 0, true) || !readerAmount(-row.amount)
        || typeof row.amount !== "number" || !Number.isFinite(Date.parse(row.imported_at))
        || new Date(row.imported_at).toISOString() !== row.imported_at) throw httpError(400, "Invalid reader audit");
    readerString(row.source_id, "audit source_id", 128, false);
    readerString(row.customer_id, "audit customer_id", 80, false);
    readerString(row.source_text, "audit source_text", 4000);
    row.reference = readerReference(row.reference === null ? "" : row.reference) || null;
    const key = `${row.document_hash}:${row.source_id}`;
    if (auditIds.has(key) || (row.reference && auditRefs.has(row.reference))) throw httpError(400, "Duplicate reader audit identity");
    auditIds.add(key);
    if (row.reference) auditRefs.add(row.reference);
  }
  for (const row of backup.statement_records) {
    if (!customerIds.has(String(row.customer_id))) throw httpError(400, "Backup has statements for unknown customers");
  }
  const statements = [];
  for (const row of backup.monthly_statement_requests) {
    statements.push({
      sql: `INSERT INTO monthly_statement_requests (company_id, request_id, record_date, type, added, claim) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(company_id, request_id) DO UPDATE SET added =
          CASE WHEN monthly_statement_requests.record_date = excluded.record_date
            AND monthly_statement_requests.type = excluded.type AND monthly_statement_requests.added = excluded.added
          THEN monthly_statement_requests.added ELSE -1 END`,
      args: [companyId, row.request_id, row.record_date, row.type, row.added, row.claim]
    });
  }
  // Restore is an audit UNION, never a replacement. Historical record IDs need not
  // exist in the restored statements. Tombstones for omitted customers also survive;
  // explicit customer/company deletion is the privacy cleanup boundary.
  for (const row of backup.reader_imports) {
    const columns = BACKUP_TABLES.reader_imports;
    statements.push({
      sql: `INSERT INTO reader_imports (company_id, ${columns.join(", ")}) VALUES (${Array(columns.length + 1).fill("?").join(",")})
        ON CONFLICT(company_id, document_hash, source_id) DO UPDATE SET amount =
        CASE WHEN reader_imports.customer_id = excluded.customer_id AND reader_imports.record_date = excluded.record_date
          AND reader_imports.amount = excluded.amount AND COALESCE(reader_imports.reference,'') = COALESCE(excluded.reference,'')
          AND reader_imports.source_text = excluded.source_text THEN reader_imports.amount ELSE 0 END`,
      args: [companyId, ...columns.map(column => row[column])]
    });
  }
  // Preserve the live high watermark even when replacing data with an older backup.
  statements.push({ sql: "INSERT INTO sales_quote_sequences (company_id, next_id) SELECT ?, COALESCE(MAX(quote_id), 0) + 1 FROM sales_quotes WHERE company_id = ? ON CONFLICT(company_id) DO UPDATE SET next_id = MAX(next_id, excluded.next_id)", args: [companyId, companyId] });
  for (const table of ["sales_quotes", "customer_visits", "statement_records", "customers", "record_types", "pdf_settings"]) {
    statements.push({ sql: `DELETE FROM ${table} WHERE company_id = ?`, args: [companyId] });
  }
  for (const table of ["customers", "statement_records", "customer_visits", "sales_quotes", "record_types", "pdf_settings"]) {
    const columns = BACKUP_TABLES[table];
    const sql = `INSERT INTO ${table} (company_id, ${columns.join(", ")}) VALUES (${["?", ...columns.map(() => "?")].join(", ")})`;
    for (const row of backup[table]) {
      statements.push({ sql, args: [companyId, ...columns.map(column => row[column] ?? (column === "converted_at" || (table === "customer_visits" && ["alk", "cal"].includes(column)) ? null : column === "status" ? "Active" : column.endsWith("amount") || column === "amount_charged" || column === "route_order" ? 0 : ""))] });
    }
    const nextId = backup.sales_quotes.reduce((next, row) => Math.max(next, row.quote_id + 1), backup.sales_quote_sequences[0]?.next_id || 1);
    statements.push({ sql: "INSERT INTO sales_quote_sequences (company_id, next_id) VALUES (?, ?) ON CONFLICT(company_id) DO UPDATE SET next_id = MAX(next_id, excluded.next_id)", args: [companyId, nextId] });
  }
  try { await tursoTransaction(env, statements); }
  catch { throw httpError(409, "Restore transaction failed; conflicting audit identities cannot be replaced"); }
  return { restored: true, customers: backup.customers.length, statements: backup.statement_records.length, visits: backup.customer_visits.length, quotes: backup.sales_quotes.length };
}

async function tursoTransaction(env, statements) {
  const configuredUrl = env.SILVERDICE_URL || env.TURSO_HTTP_URL;
  const httpUrl = requiredSecret(configuredUrl, "SILVERDICE_URL")
    .replace(/^(libsql|turso):\/\//, "https://")
    .replace(/\/$/, "");
  const token = requiredSecret(env.SILVERDICE_AUTH_TOKEN || env.TURSO_AUTH_TOKEN, "SILVERDICE_AUTH_TOKEN");
  const steps = [{ stmt: { sql: "BEGIN IMMEDIATE" } }];
  for (const statement of statements) {
    steps.push({
      stmt: { sql: statement.sql, args: statement.args.map(toTursoArg) },
      condition: { type: "ok", step: steps.length - 1 }
    });
  }
  const commitIndex = steps.length;
  steps.push({ stmt: { sql: "COMMIT" }, condition: { type: "ok", step: commitIndex - 1 } });
  steps.push({ stmt: { sql: "ROLLBACK" }, condition: { type: "not", cond: { type: "ok", step: commitIndex } } });
  const response = await fetch(`${httpUrl}/v2/pipeline`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ requests: [{ type: "batch", batch: { steps } }, { type: "close" }] })
  });
  if (!response.ok) throw new Error(`Turso request failed (${response.status}): ${(await response.text()).slice(0, 300)}`);
  const payload = await response.json();
  const result = payload.results?.[0]?.response?.result;
  if (!result || result.step_errors?.[commitIndex] || !result.step_results?.[commitIndex]) {
    throw new Error("Database transaction failed and was rolled back");
  }
  return result.step_results.slice(1, commitIndex).map(query =>
    (query?.rows || []).map(row => Object.fromEntries(query.cols.map((column, index) => [column.name, decodeTursoValue(row[index])]))));
}
const QUOTES_TABLE_SQL = "CREATE TABLE IF NOT EXISTS sales_quotes (company_id INTEGER NOT NULL, quote_id INTEGER NOT NULL CHECK (quote_id > 0), quote_number TEXT NOT NULL, customer_id TEXT NOT NULL, quote_date TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '', terms TEXT NOT NULL DEFAULT '', lines_json TEXT NOT NULL CHECK (json_valid(lines_json)), converted_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY (company_id, quote_id), UNIQUE (company_id, quote_number), FOREIGN KEY (company_id, customer_id) REFERENCES customers(company_id, customer_id) ON DELETE CASCADE)";

async function migrateQuotes(env) {
  await tursoCore(env, "CREATE TABLE IF NOT EXISTS sales_quote_sequences (company_id INTEGER PRIMARY KEY, next_id INTEGER NOT NULL CHECK (next_id > 0), FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE)");
  await tursoCore(env, QUOTES_TABLE_SQL);
  await tursoCore(env, "CREATE INDEX IF NOT EXISTS sales_quotes_customer_idx ON sales_quotes(company_id, customer_id, quote_id)");
  await tursoCore(env, "INSERT INTO sales_quote_sequences (company_id, next_id) SELECT company_id, MAX(quote_id) + 1 FROM sales_quotes WHERE 1 GROUP BY company_id ON CONFLICT(company_id) DO UPDATE SET next_id = MAX(next_id, excluded.next_id)");
}
const VISITS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS customer_visits (company_id INTEGER NOT NULL, visit_id INTEGER NOT NULL, customer_id TEXT NOT NULL, visited_at TEXT NOT NULL, ph TEXT NOT NULL CHECK (ph IN ('7.0','7.2','7.4','7.6','7.8','8.0')), chlo TEXT NOT NULL CHECK (chlo IN ('1-2','2-4','3-6','5-10','7.5-15','10-20')), stb TEXT NOT NULL CHECK (stb IN ('100','90','80','70','60','50','40','30')), alk INTEGER CHECK (alk IS NULL OR (typeof(alk) = 'integer' AND alk BETWEEN 1 AND 2147483647)), cal INTEGER CHECK (cal IS NULL OR (typeof(cal) = 'integer' AND cal BETWEEN 1 AND 2147483647)), notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 16000), photo_data TEXT NOT NULL DEFAULT '', PRIMARY KEY (company_id, visit_id), FOREIGN KEY (company_id, customer_id) REFERENCES customers(company_id, customer_id) ON DELETE CASCADE)";

async function migrateVisits(env) {
  await tursoCore(env, VISITS_TABLE_SQL);
  const columns = await tursoCore(env, "PRAGMA table_info(customer_visits)");
  if (columns.some(column => ["alk", "cal"].includes(column.name) && Number(column.notnull) === 1)) {
    const indexes = await tursoCore(env, "SELECT sql FROM sqlite_master WHERE type IN ('index', 'trigger') AND tbl_name = 'customer_visits' AND sql IS NOT NULL");
    const names = ["company_id", ...BACKUP_TABLES.customer_visits];
    const photoExists = columns.some(column => column.name === "photo_data");
    await tursoTransaction(env, [
      { sql: VISITS_TABLE_SQL.replace("customer_visits (", "customer_visits_migrating ("), args: [] },
      {
        sql: `INSERT INTO customer_visits_migrating (${names.join(", ")}) SELECT ${names.map(name => name === "photo_data" && !photoExists ? "''" : name).join(", ")} FROM customer_visits`,
        args: []
      },
      { sql: "DROP TABLE customer_visits", args: [] },
      { sql: "ALTER TABLE customer_visits_migrating RENAME TO customer_visits", args: [] },
      ...indexes.map(index => ({ sql: index.sql, args: [] }))
    ]);
  } else if (columns.length && !columns.some(column => column.name === "photo_data")) {
    await tursoCore(env, "ALTER TABLE customer_visits ADD COLUMN photo_data TEXT NOT NULL DEFAULT ''");
  }
  await tursoCore(env, "CREATE INDEX IF NOT EXISTS customer_visits_customer_idx ON customer_visits(company_id, customer_id, visited_at)");
}

const schemaReady = new Map();
async function turso(env, sql, ...args) {
  const key = env.SILVERDICE_URL || env.TURSO_HTTP_URL;
  if (!schemaReady.has(key)) schemaReady.set(key, initializeSchema(env).catch(error => { schemaReady.delete(key); throw error; }));
  await schemaReady.get(key);
  return tursoCore(env, sql, ...args);
}

async function initializeSchema(env) {
  const exists = await tursoCore(env, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'");
  if (!exists.length) await tursoCore(env, "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
  const versions = new Set((await tursoCore(env, "SELECT version FROM schema_migrations")).map(row => Number(row.version)));
  if (!versions.has(1)) {
    const columns = await tursoCore(env, "PRAGMA table_info(customers)");
    for (const [name, definition] of [
      ["starting_date", "TEXT NOT NULL DEFAULT ''"],
      ["status", "TEXT NOT NULL DEFAULT 'Active'"],
      ["route_order", "INTEGER NOT NULL DEFAULT 0"]
    ]) if (!columns.some(column => column.name === name)) {
      try { await tursoCore(env, `ALTER TABLE customers ADD COLUMN ${name} ${definition}`); }
      catch (error) { if (!error.message.includes(`duplicate column name: ${name}`)) throw error; }
    }
    await migrateVisits(env);
    await migrateQuotes(env);
    await tursoCore(env, READER_TABLE_SQL);
    await tursoCore(env, "CREATE UNIQUE INDEX IF NOT EXISTS reader_imports_reference_idx ON reader_imports(company_id, reference COLLATE NOCASE) WHERE reference IS NOT NULL AND reference <> ''");
    await tursoCore(env, "INSERT INTO schema_migrations (version, applied_at) VALUES (1, ?) ON CONFLICT(version) DO NOTHING", new Date().toISOString());
  }
  if (!versions.has(2)) {
    await tursoTransaction(env, [
      { sql: MONTHLY_TABLE_SQL, args: [] },
      { sql: "INSERT INTO schema_migrations (version, applied_at) VALUES (2, ?) ON CONFLICT(version) DO NOTHING", args: [new Date().toISOString()] }
    ]);
  }
}

const MONTHLY_TABLE_SQL = "CREATE TABLE IF NOT EXISTS monthly_statement_requests (company_id INTEGER NOT NULL, request_id TEXT NOT NULL, record_date TEXT NOT NULL, type TEXT NOT NULL, added INTEGER NOT NULL CHECK (typeof(added) = 'integer' AND added >= 0), claim TEXT NOT NULL, PRIMARY KEY (company_id, request_id), FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE)";

const READER_TABLE_SQL = "CREATE TABLE IF NOT EXISTS reader_imports (company_id INTEGER NOT NULL, document_hash TEXT NOT NULL, source_id TEXT NOT NULL, customer_id TEXT NOT NULL, record_id INTEGER NOT NULL CHECK (record_id > 0), record_date TEXT NOT NULL, amount REAL NOT NULL CHECK (amount < 0 AND amount >= -999999999.99), reference TEXT, source_text TEXT NOT NULL, imported_at TEXT NOT NULL, PRIMARY KEY (company_id, document_hash, source_id), FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE)";

async function tursoCore(env, sql, ...args) {
  const configuredUrl = env.SILVERDICE_URL || env.TURSO_HTTP_URL;
  const httpUrl = requiredSecret(configuredUrl, "SILVERDICE_URL")
    .replace(/^(libsql|turso):\/\//, "https://")
    .replace(/\/$/, "");
  const token = requiredSecret(env.SILVERDICE_AUTH_TOKEN || env.TURSO_AUTH_TOKEN, "SILVERDICE_AUTH_TOKEN");
  const stmt = { sql };
  if (args.length) stmt.args = args.map(toTursoArg);
  const response = await fetch(`${httpUrl}/v2/pipeline`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ requests: [{ type: "execute", stmt }, { type: "close" }] })
  });
  if (!response.ok) throw new Error(`Turso request failed (${response.status}): ${(await response.text()).slice(0, 300)}`);
  const payload = await response.json();
  const result = payload.results?.[0];
  if (result?.type !== "ok") throw new Error(`Turso rejected the database operation: ${result?.error?.message || "unknown error"}`);
  const queryResult = result.response?.result;
  if (!queryResult) return [];
  const columns = queryResult.cols || [];
  return (queryResult.rows || []).map(row => Object.fromEntries(columns.map((column, index) => [column.name, decodeTursoValue(row[index])] )));
}

function toTursoArg(value) {
  if (value === null || value === undefined) return { type: "null" };
  if (typeof value === "number" && Number.isInteger(value)) return { type: "integer", value: String(value) };
  if (typeof value === "number") return { type: "float", value };
  return { type: "text", value: String(value) };
}

function decodeTursoValue(cell) {
  if (!cell || cell.type === "null") return null;
  if (cell.type === "integer") return Number(cell.value);
  if (cell.type === "float") return Number(cell.value);
  return cell.value;
}

async function readJson(request, maxBytes = 64 * 1024) {
  const contentLength = Number(request.headers.get("Content-Length") || 0);
  if (contentLength > maxBytes) throw httpError(413, "Request body is too large");
  const text = await request.text();
  if (new TextEncoder().encode(text).length > maxBytes) throw httpError(413, "Request body is too large");
  try {
    const body = JSON.parse(text);
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error();
    return body;
  } catch {
    throw httpError(400, "Expected a JSON object");
  }
}

function validateCustomer(customer) {
  requiredString(customer.customer_id, "customer_id", 80);
  requiredString(customer.first_name, "first_name", 120);
  requiredString(customer.last_name, "last_name", 120);
  const amount = Number(customer.amount_charged || 0);
  if (!Number.isFinite(amount) || Math.abs(amount) > 1e12) throw httpError(400, "Invalid amount_charged");
  if (customer.starting_date) {
    const m = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(customer.starting_date);
    const d = m && new Date(Date.UTC(+m[3], +m[1] - 1, +m[2]));
    if (!d || d.getUTCDate() !== +m[2] || d.getUTCMonth() !== +m[1] - 1) throw httpError(400, "starting_date must be MM/DD/YYYY");
  }
  if (customer.status && !["Active", "Inactive"].includes(customer.status)) throw httpError(400, "status must be Active or Inactive");
}

function requiredString(value, name, maxLength) {
  if (typeof value !== "string" || !value.trim() || value.length > maxLength) {
    throw httpError(400, `${name} is required and must be at most ${maxLength} characters`);
  }
  return value.trim();
}

function parseJsonArrayOrObject(value, fallback) {
  try {
    const parsed = JSON.parse(value || "");
    if (Array.isArray(fallback) ? Array.isArray(parsed) : parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
  } catch {
    return fallback;
  }
  return fallback;
}

function isSimpleValue(value) {
  return typeof value === "string" && value.length > 0 && value.length <= 200;
}

function positiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? Math.min(parsed, 604800) : fallback;
}

function requiredSecret(value, name) {
  if (typeof value !== "string" || value.trim().length < 16) throw new Error(`Worker secret ${name} is not configured`);
  return value.trim();
}

function constantTimeEqual(left, right) {
  if (left.length !== right.length) return false;
  let result = 0;
  for (let index = 0; index < left.length; index++) result |= left.charCodeAt(index) ^ right.charCodeAt(index);
  return result === 0;
}

function base64UrlEncode(value) {
  return base64UrlEncodeBytes(new TextEncoder().encode(value));
}

function base64UrlEncodeBytes(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlDecodeBytes(value) {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(base64 + "=".repeat((4 - base64.length % 4) % 4));
  return Uint8Array.from(binary, character => character.charCodeAt(0));
}

function corsHeaders(request) {
  const origin = request.headers.get("Origin");
  const headers = new Headers({
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin"
  });
  if (origin && (origin === "null" || origin.startsWith("http://localhost"))) {
    headers.set("Access-Control-Allow-Origin", origin);
  }
  return headers;
}

function json(value, status, request) {
  const headers = new Headers(JSON_HEADERS);
  for (const [key, value] of corsHeaders(request)) headers.set(key, value);
  return new Response(JSON.stringify(value), { status, headers });
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}
