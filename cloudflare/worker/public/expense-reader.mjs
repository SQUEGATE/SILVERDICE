import { groupTextLines, readPdfPages } from "./statement-reader.mjs";

const moneyPattern = /\((?:\$\s*)?(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{2}\)|(?:\$\s*)?-?(?:\d{1,3}(?:,\d{3})+|\d+)\.\d{2}(?!\d)/;

export function normalizeKeywords(list) {
  const seen = new Set(), out = [];
  for (const raw of Array.isArray(list) ? list : []) {
    const keyword = String(raw ?? "").trim().replace(/\s+/g, " ");
    if (!keyword) continue;
    if (keyword.length > 80) throw new Error("Each keyword must be 80 characters or fewer.");
    if (seen.has(keyword.toLowerCase())) continue;
    seen.add(keyword.toLowerCase());
    out.push(keyword);
  }
  if (out.length > 100) throw new Error("No more than 100 keywords can be saved.");
  return out;
}

export function keywordPattern(keyword) {
  const body = keyword.split(" ").map(part => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("\\s+");
  return new RegExp(`(?<![A-Za-z0-9])${body}(?![A-Za-z0-9])`, "i");
}

/** First monetary amount on the line, as absolute cents, or null. */
export function lineAmount(text) {
  const match = moneyPattern.exec(text);
  if (!match) return null;
  const cents = Math.round(Number(match[0].replace(/[^0-9.]/g, "")) * 100);
  return { text: match[0], cents };
}

/** lines: [{page, line, text}] — each line counts once, for the first matching keyword. */
export function matchExpenseLines(lines, keywords) {
  const patterns = normalizeKeywords(keywords).map(keyword => ({ keyword, re: keywordPattern(keyword) }));
  const matches = [];
  for (const line of lines) {
    const hit = patterns.find(p => p.re.test(line.text));
    if (!hit) continue;
    const amount = lineAmount(line.text);
    matches.push({ page: line.page, line: line.line, text: line.text, keyword: hit.keyword,
      amount_text: amount ? amount.text : "", cents: amount ? amount.cents : null });
  }
  return matches;
}

export function expenseTotals(matches, keywords) {
  const byKeyword = new Map(normalizeKeywords(keywords).map(k => [k, { keyword: k, count: 0, cents: 0 }]));
  let cents = 0, count = 0;
  for (const m of matches) {
    if (m.included === false || m.cents == null) continue;
    const entry = byKeyword.get(m.keyword) || byKeyword.set(m.keyword, { keyword: m.keyword, count: 0, cents: 0 }).get(m.keyword);
    entry.count++; entry.cents += m.cents; count++; cents += m.cents;
  }
  return { keywords: [...byKeyword.values()], count, cents };
}

export function pdfLines(pages) {
  const lines = [];
  pages.forEach((page, i) => {
    for (const line of groupTextLines(page.items || page)) lines.push({ page: i + 1, line: line.line, text: line.text });
  });
  if (!lines.length) throw new Error("Scanned/image PDF needs selectable text. OCR is not supported.");
  return lines;
}

export async function parseExpensePdf(file, options = {}) {
  const { pages } = await readPdfPages(file, options);
  return pdfLines(pages);
}
