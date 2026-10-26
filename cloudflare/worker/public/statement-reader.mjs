export const LIMITS = Object.freeze({ bytes: 25 * 1024 * 1024, pages: 100, rows: 1000 });

const dateStart = /^(\d{1,2}\/\d{1,2}(?:\/\d{4})?)(?=\s|$)/;
const outgoing = /\bzelle\s+to\b|\bwithdrawals?\b|\bsubtractions?\b|\bpurchase\b|\bdebit\b|\bpayment\s+to\b/i;
const incoming = /\bzelle\s+from\b|\bdeposits?\b|\bincoming\b|\bcredit\b/i;
const moneyPattern = /(?:\$\s*)?-?(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{2}(?!\d)/g;

function tokens(item) {
  const text = String(item.str || "").trim();
  if (!text) return [];
  const x = item.transform?.[4] || 0;
  const y = item.transform?.[5] || 0;
  // PDF producers sometimes combine several cells in one text item.
  return [...text.matchAll(/\S+/g)].map(m => ({
    str: m[0], x: x + (item.width || 0) * m.index / text.length,
    width: (item.width || 0) * m[0].length / text.length, y,
  }));
}

export function groupTextLines(items) {
  const lines = [];
  for (const item of items.flatMap(tokens).sort((a, b) => b.y - a.y || a.x - b.x)) {
    let line = lines.find(l => Math.abs(l.y - item.y) <= 2.5);
    if (!line) lines.push(line = { y: item.y, items: [] });
    line.items.push(item);
  }
  return lines.sort((a, b) => b.y - a.y).map((line, i) => {
    line.items.sort((a, b) => a.x - b.x);
    return { ...line, line: i + 1, text: line.items.map(t => t.str).join(" ") };
  });
}

function headerColumns(lines) {
  const headers = [];
  const isHeader = line => line.items.every(t => /^(?:check|deposits?\/?|withdrawals?\/?|ending|daily|date|number|description|additions|subtractions|balance)$/i.test(t.str));
  for (let i = 0; i < lines.length; i++) {
    if (!/\bdate\b/i.test(lines[i].text) || !/\bdescription\b/i.test(lines[i].text)) continue;
    const nearby = lines.slice(Math.max(0, i - 2), i + 3).filter(l => Math.abs(l.y - lines[i].y) < 40 && isHeader(l));
    const cells = nearby.flatMap(l => l.items);
    const find = re => cells.filter(t => re.test(t.str)).sort((a, b) => a.x - b.x)[0];
    const deposit = find(/^(?:deposits?\/?|additions)$/i);
    const withdrawal = find(/^(?:withdrawals?\/?|subtractions)$/i);
    const balance = find(/^(?:ending|balance)$/i);
    if (!deposit || !withdrawal || !balance || !(deposit.x < withdrawal.x && withdrawal.x < balance.x)) continue;
    const edge = re => Math.max(...cells.filter(t => re.test(t.str)).map(t => t.x + t.width));
    const depositEdge = edge(/^(?:deposits?\/?|additions)$/i);
    const withdrawalEdge = edge(/^(?:withdrawals?\/?|subtractions)$/i);
    const balanceEdge = edge(/^(?:ending|balance)$/i);
    headers.push({
      start: i,
      end: Math.max(...nearby.map(l => lines.indexOf(l))),
      deposit, withdrawal, balance,
      dateX: find(/^date$/i).x,
      // Compare numeric right edges: a large withdrawal may start inside the deposit column.
      left: deposit.x - 12,
      split: (depositEdge + withdrawalEdge) / 2,
      right: (withdrawalEdge + balanceEdge) / 2,
    });
  }
  return headers;
}

function amountCells(items) {
  const cells = [];
  for (let i = 0; i < items.length; i++) {
    const t = items[i];
    for (const m of t.str.matchAll(moneyPattern)) {
      cells.push({ text: (i > 0 && items[i - 1].str === "$" ? "$" : "") + m[0], x: t.x + t.width * (m.index + m[0].length) / t.str.length });
    }
  }
  return cells;
}

function extractReference(text) {
  return (/\bref(?:erence)?\.?\s*(?:#|:)?\s*([A-Za-z0-9][A-Za-z0-9_-]{1,159})/i.exec(text)?.[1] || "").slice(0, 160);
}

export function extractStatementRows(pages, { currentYear = new Date().getFullYear() } = {}) {
  if (pages.length > LIMITS.pages) throw new Error("PDF exceeds the 100-page limit.");
  if (!Number.isInteger(currentYear) || currentYear < 1900 || currentYear > 9999) throw new Error("Invalid current year.");
  const rows = [];
  let textSeen = false;
  for (let pi = 0; pi < pages.length; pi++) {
    const lines = groupTextLines(pages[pi].items || pages[pi]);
    if (lines.length) textSeen = true;
    const headers = headerColumns(lines);
    let active = null, layout = null;
    const finish = () => {
      if (!active) return;
      const { parts, cells, firstCells, ...row } = active;
      row.text = parts.join(" ").slice(0, 4000);
      row.reference = extractReference(row.text);
      const amounts = cells;
      const isOutgoing = outgoing.test(row.text);
      if (layout) {
        const additions = amounts.filter(a => a.x >= layout.left && a.x < layout.split);
        const subtractions = amounts.filter(a => a.x >= layout.split && a.x < layout.right);
        if (isOutgoing || subtractions.length) {
          row.direction = "withdrawal";
        } else if (additions.length === 1 && !/^-/.test(additions[0].text.replace("$", "").trim())) {
          row.direction = "deposit"; row.deposit_text = additions[0].text;
        }
      } else if (isOutgoing) {
        row.direction = "withdrawal";
      } else if (incoming.test(row.text) || amounts.some(a => /\$/.test(a.text))) {
        row.direction = "deposit";
        if (firstCells.length === 1 && amounts.length === 1 && !/^-/.test(firstCells[0].text.replace("$", "").trim())) row.deposit_text = firstCells[0].text;
      }
      rows.push(row);
      if (rows.length > LIMITS.rows) throw new Error("PDF exceeds the 1,000-transaction limit.");
      active = null;
    };
    for (let li = 0; li < lines.length; li++) {
      const line = lines[li];
      const header = headers.find(h => li >= h.start && li <= h.end);
      if (header) {
        finish(); layout = header; li = header.end; continue;
      }
      const date = (!layout || Math.abs(line.items[0].x - layout.dateX) < 22) && dateStart.exec(line.text);
      if (date) {
        finish();
        active = {
          source_id: `${pi + 1}:${line.line}`, page: pi + 1, line: line.line,
          text: "", date_text: date[1], deposit_text: "", direction: "unknown", reference: "",
          parts: [line.text], cells: amountCells(line.items), firstCells: amountCells(line.items),
        };
      } else if (active && /\b(?:ending balance|totals?|transaction history|page \d|continued|account summary)\b/i.test(line.text)) {
        finish();
      } else if (active) {
        active.parts.push(line.text); active.cells.push(...amountCells(line.items));
      }
    }
    // Never join unrelated page headers/account data into a previous page's transaction.
    finish();
  }
  if (!textSeen) throw new Error("Scanned/image PDF needs selectable text. OCR is not supported.");
  return { rows, currentYear };
}

export async function parseStatementPdf(file, { currentYear = new Date().getFullYear(), pdfjs, isCurrent = () => true } = {}) {
  if (!file || file.size === 0) throw new Error("Choose a non-empty PDF.");
  if (file.size > LIMITS.bytes) throw new Error("PDF exceeds the 25 MB limit.");
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (!new TextDecoder().decode(bytes.subarray(0, 1024)).includes("%PDF-")) throw new Error("File is not a PDF.");
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  const document_hash = [...new Uint8Array(hash)].map(n => n.toString(16).padStart(2, "0")).join("");
  if (!isCurrent()) throw new Error("Upload cancelled.");
  pdfjs ||= await import("./vendor/pdfjs/pdf.mjs");
  pdfjs.GlobalWorkerOptions.workerSrc = new URL("./vendor/pdfjs/pdf.worker.mjs", import.meta.url).href;
  const loading = pdfjs.getDocument({ data: bytes, isEvalSupported: false, useSystemFonts: true });
  let pdf;
  try {
    pdf = await loading.promise;
    if (pdf.numPages > LIMITS.pages) throw new Error("PDF exceeds the 100-page limit.");
    const pages = [];
    for (let n = 1; n <= pdf.numPages; n++) {
      if (!isCurrent()) throw new Error("Upload cancelled.");
      const page = await pdf.getPage(n);
      pages.push(await page.getTextContent());
      page.cleanup();
    }
    return { document_hash, ...extractStatementRows(pages, { currentYear }) };
  } catch (e) {
    if (/password/i.test(e.name || "")) throw new Error("Password-protected PDF: save an unlocked selectable-text copy first.");
    throw e;
  } finally {
    await loading.destroy();
  }
}
