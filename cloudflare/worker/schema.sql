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

CREATE TABLE IF NOT EXISTS record_types (
    company_id INTEGER NOT NULL,
    type_name TEXT NOT NULL,
    PRIMARY KEY (company_id, type_name),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS pdf_settings (
    company_id INTEGER NOT NULL,
    setting_key TEXT NOT NULL,
    setting_value TEXT NOT NULL,
    PRIMARY KEY (company_id, setting_key),
    FOREIGN KEY (company_id) REFERENCES companies(company_id) ON DELETE CASCADE
);
