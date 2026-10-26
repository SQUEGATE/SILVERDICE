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
        const maxValueLength = settingKey === "logoData" ? 600000 : 16000;
        if (typeof body.value !== "string" || body.value.length > maxValueLength) throw httpError(400, `value must be a string no longer than ${maxValueLength} characters`);
        if (settingKey === "logoData" && body.value && !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(body.value)) throw httpError(400, "logoData must be a PNG or JPEG data URL");
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
  await turso(env, "DELETE FROM accounts WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM sales_quotes WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM sales_quote_sequences WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM customer_visits WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM statement_records WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM customers WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM record_types WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM pdf_settings WHERE company_id = ?", companyId);
  await turso(env, "DELETE FROM companies WHERE company_id = ?", companyId);
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
    canAccessSalesQuote: permissions.canAccessSalesQuote === true };
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
  requirePermission(session, "canViewCustomers", "canViewCustomerDetails", "canAccessVisits", "canAccessSalesQuote");
  const url = new URL(request.url);
  const query = (url.searchParams.get("q") || "").trim().toLowerCase();
  const orderById = url.searchParams.get("order") === "id";
  const filters = customerAccessSql(session);
  let sql = "SELECT customer_id, first_name, last_name, address, city, state, zip, phone, email, service_day, amount_charged, notes, starting_date, status, route_order FROM customers WHERE company_id = ?";
  const args = [requiredCompanyId(session)];
  sql += filters.sql;
  args.push(...filters.args);
  if (query) {
    sql += " AND (lower(first_name) LIKE ? OR lower(last_name) LIKE ? OR lower(address) LIKE ? OR lower(city) LIKE ? OR lower(state) LIKE ? OR lower(zip) LIKE ? OR lower(email) LIKE ?)";
    const pattern = `%${query}%`;
    args.push(pattern, pattern, pattern, pattern, pattern, pattern, pattern);
  }
  sql += orderById
    ? " ORDER BY CASE WHEN customer_id GLOB '[0-9]*' THEN CAST(customer_id AS INTEGER) ELSE 999999999 END, customer_id"
    : " ORDER BY service_day, CASE WHEN route_order > 0 THEN 0 ELSE 1 END, route_order, last_name, first_name";
  const rows = await turso(env, sql, ...args);
  if (!orderById && !query && !filters.sql) await healRouteOrders(env, requiredCompanyId(session), rows);
  return json(rows, 200, request);
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
  requirePermission(session, "canViewCustomerDetails", "canViewCustomers", "canAccessVisits", "canAccessSalesQuote");
  const customer = await findAccessibleCustomer(env, session, customerId);
  return json(customer, 200, request);
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
    const rows = await turso(env, `SELECT ${QUOTE_COLUMNS} FROM sales_quotes WHERE company_id = ? AND customer_id = ? ORDER BY quote_id DESC`, companyId, customerId);
    return json(rows.map(quoteResponse), 200, request);
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
  const rows = await turso(env,
    "SELECT visit_id, customer_id, visited_at, ph, chlo, stb, alk, cal, notes, photo_data FROM customer_visits WHERE company_id = ? AND customer_id = ? ORDER BY visited_at DESC, visit_id DESC",
    requiredCompanyId(session), customerId);
  return json(rows, 200, request);
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
  await turso(env, "DELETE FROM sales_quotes WHERE company_id = ? AND customer_id = ?", requiredCompanyId(session), customerId);
  await turso(env, "DELETE FROM customer_visits WHERE company_id = ? AND customer_id = ?", requiredCompanyId(session), customerId);
  await turso(env, "DELETE FROM statement_records WHERE company_id = ? AND customer_id = ?", requiredCompanyId(session), customerId);
  await turso(env, "DELETE FROM customers WHERE company_id = ? AND customer_id = ?", requiredCompanyId(session), customerId);
  return json({ deleted: true }, 200, request);
}

async function listStatements(request, env, session, customerId) {
  requirePermission(session, "canViewStatements");
  await findAccessibleCustomer(env, session, customerId);
  const rows = await turso(env,
    "SELECT record_id, customer_id, record_date, type, amount FROM statement_records WHERE company_id = ? AND customer_id = ? ORDER BY record_id",
    requiredCompanyId(session), customerId);
  return json(rows, 200, request);
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
  for (const [table, columns] of Object.entries(BACKUP_TABLES)) {
    backup[table] = await turso(env, `SELECT ${columns.join(", ")} FROM ${table} WHERE company_id = ?`, companyId);
  }
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
    if (["customer_visits", "sales_quotes", "sales_quote_sequences"].includes(table) && backup[table] === undefined) backup[table] = [];
    if (!Array.isArray(backup[table])) throw httpError(400, `Backup is missing ${table}`);
  }
  const customerIds = new Set(backup.customers.map(row => String(row.customer_id)));
  validateBackupVisits(backup.customer_visits, customerIds);
  validateBackupQuotes(backup, customerIds);
  for (const row of backup.statement_records) {
    if (!customerIds.has(String(row.customer_id))) throw httpError(400, "Backup has statements for unknown customers");
  }
  const statements = [];
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
  await tursoTransaction(env, statements);
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

let schemaReady;
async function turso(env, sql, ...args) {
  schemaReady ??= (async () => {
    for (const ddl of [
      "ALTER TABLE customers ADD COLUMN starting_date TEXT NOT NULL DEFAULT ''",
      "ALTER TABLE customers ADD COLUMN status TEXT NOT NULL DEFAULT 'Active'",
      "ALTER TABLE customers ADD COLUMN route_order INTEGER NOT NULL DEFAULT 0"
    ]) {
      try { await tursoCore(env, ddl); } catch { /* column already exists */ }
    }
    await migrateVisits(env);
    await migrateQuotes(env);
  })().catch(error => { schemaReady = undefined; throw error; });
  await schemaReady;
  return tursoCore(env, sql, ...args);
}

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
  if (result?.type !== "ok") throw new Error("Turso rejected the database operation");
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
