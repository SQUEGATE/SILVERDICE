CREATE TABLE IF NOT EXISTS companies (
    company_id INTEGER PRIMARY KEY,
    company_name TEXT NOT NULL,
    phone TEXT NOT NULL DEFAULT '',
    email TEXT NOT NULL DEFAULT '',
    address TEXT NOT NULL DEFAULT '',
    username TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS accounts (
    account_id INTEGER PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('MASTER', 'COMPANY', 'EMPLOYEE')),
    company_id INTEGER,
    employee_id INTEGER,
    first_name TEXT NOT NULL DEFAULT '',
    last_name TEXT NOT NULL DEFAULT '',
    phone TEXT NOT NULL DEFAULT '',
    email TEXT NOT NULL DEFAULT '',
    permissions_json TEXT NOT NULL DEFAULT '{}',
    allowed_customer_ids_json TEXT NOT NULL DEFAULT '[]',
    allowed_days_json TEXT NOT NULL DEFAULT '[]',
    FOREIGN KEY (company_id) REFERENCES companies(company_id)
);

CREATE TABLE IF NOT EXISTS customers (
    company_id INTEGER NOT NULL,
    customer_id TEXT NOT NULL,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    address TEXT NOT NULL DEFAULT '',
    city TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL DEFAULT '',
    zip TEXT NOT NULL DEFAULT '',
    phone TEXT NOT NULL DEFAULT '',
    email TEXT NOT NULL DEFAULT '',
    service_day TEXT NOT NULL DEFAULT '',
    amount_charged REAL NOT NULL DEFAULT 0,
    notes TEXT NOT NULL DEFAULT '',
    starting_date TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'Active',
    route_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (company_id, customer_id),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS customers_company_name_idx
    ON customers(company_id, last_name, first_name);
CREATE INDEX IF NOT EXISTS customers_company_day_idx
    ON customers(company_id, service_day);

CREATE TABLE IF NOT EXISTS statement_records (
    record_id INTEGER NOT NULL,
    company_id INTEGER NOT NULL,
    customer_id TEXT NOT NULL,
    record_date TEXT NOT NULL,
    type TEXT NOT NULL,
    amount REAL NOT NULL,
    PRIMARY KEY (company_id, record_id),
    FOREIGN KEY (company_id, customer_id)
        REFERENCES customers(company_id, customer_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS statement_records_customer_idx
    ON statement_records(company_id, customer_id, record_id);

-- Audit identities deliberately have no statement FK: deleting a payment must not permit reimport.
CREATE TABLE IF NOT EXISTS reader_imports (
    company_id INTEGER NOT NULL,
    document_hash TEXT NOT NULL,
    source_id TEXT NOT NULL,
    customer_id TEXT NOT NULL,
    record_id INTEGER NOT NULL CHECK (record_id > 0),
    record_date TEXT NOT NULL,
    amount REAL NOT NULL CHECK (amount < 0 AND amount >= -999999999.99),
    reference TEXT,
    source_text TEXT NOT NULL,
    imported_at TEXT NOT NULL,
    PRIMARY KEY (company_id, document_hash, source_id),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS reader_imports_reference_idx
    ON reader_imports(company_id, reference COLLATE NOCASE)
    WHERE reference IS NOT NULL AND reference <> '';

CREATE TABLE IF NOT EXISTS customer_visits (
    company_id INTEGER NOT NULL,
    visit_id INTEGER NOT NULL,
    customer_id TEXT NOT NULL,
    visited_at TEXT NOT NULL,
    ph TEXT NOT NULL CHECK (ph IN ('7.0', '7.2', '7.4', '7.6', '7.8', '8.0')),
    chlo TEXT NOT NULL CHECK (chlo IN ('1-2', '2-4', '3-6', '5-10', '7.5-15', '10-20')),
    stb TEXT NOT NULL CHECK (stb IN ('100', '90', '80', '70', '60', '50', '40', '30')),
    alk INTEGER CHECK (alk IS NULL OR (typeof(alk) = 'integer' AND alk BETWEEN 1 AND 2147483647)),
    cal INTEGER CHECK (cal IS NULL OR (typeof(cal) = 'integer' AND cal BETWEEN 1 AND 2147483647)),
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 16000),
    photo_data TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (company_id, visit_id),
    FOREIGN KEY (company_id, customer_id)
        REFERENCES customers(company_id, customer_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS customer_visits_customer_idx
    ON customer_visits(company_id, customer_id, visited_at);

CREATE TABLE IF NOT EXISTS record_types (
    company_id INTEGER NOT NULL,
    type_name TEXT NOT NULL,
    PRIMARY KEY (company_id, type_name),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sales_quote_sequences (
    company_id INTEGER PRIMARY KEY,
    next_id INTEGER NOT NULL CHECK (next_id > 0),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sales_quotes (
    company_id INTEGER NOT NULL,
    quote_id INTEGER NOT NULL CHECK (quote_id > 0),
    quote_number TEXT NOT NULL,
    customer_id TEXT NOT NULL,
    quote_date TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    terms TEXT NOT NULL DEFAULT '',
    lines_json TEXT NOT NULL CHECK (json_valid(lines_json)),
    converted_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (company_id, quote_id),
    UNIQUE (company_id, quote_number),
    FOREIGN KEY (company_id, customer_id)
        REFERENCES customers(company_id, customer_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS sales_quotes_customer_idx
    ON sales_quotes(company_id, customer_id, quote_id);

CREATE TABLE IF NOT EXISTS pdf_settings (
    company_id INTEGER NOT NULL,
    setting_key TEXT NOT NULL,
    setting_value TEXT NOT NULL,
    PRIMARY KEY (company_id, setting_key),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);

-- Request identities survive statement/customer deletion and are unioned on restore.
CREATE TABLE IF NOT EXISTS monthly_statement_requests (
    company_id INTEGER NOT NULL,
    request_id TEXT NOT NULL,
    record_date TEXT NOT NULL,
    type TEXT NOT NULL,
    added INTEGER NOT NULL CHECK (typeof(added) = 'integer' AND added >= 0),
    claim TEXT NOT NULL,
    PRIMARY KEY (company_id, request_id),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);
