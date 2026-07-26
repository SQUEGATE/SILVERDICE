-- database.sql
-- SQL schema for the Pool Service Customer database
-- Use this script to create the customers table and add initial records.

BEGIN TRANSACTION;

CREATE TABLE IF NOT EXISTS customers (
    customer_id TEXT PRIMARY KEY,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    address TEXT,
    phone TEXT,
    email TEXT,
    service_day TEXT,
    amount_charged REAL,
    notes TEXT
);

-- Example customer records (replace or extend these as needed):
INSERT INTO customers (customer_id, first_name, last_name, address, phone, email, service_day, amount_charged, notes) VALUES
('C0000001', 'John', 'Doe', '123 Poolside Lane', '555-1234', 'john.doe@example.com', 'Monday', 120.00, 'Weekly chlorine check'),
('C0000002', 'Jane', 'Smith', '456 Aqua Avenue', '555-5678', 'jane.smith@example.com', 'Wednesday', 145.50, 'Filter replacement');

COMMIT;
