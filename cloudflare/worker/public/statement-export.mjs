let libraries;
function loadScript(src) {
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = src;
    script.onload = resolve;
    script.onerror = () => { script.remove(); reject(new Error("Unable to load the local PDF generator. Refresh and retry PDF export only.")); };
    document.head.appendChild(script);
  });
}
export async function loadStatementExporter() {
  if (!libraries) libraries = (async () => {
    await loadScript("/vendor/pdf-export/jspdf.umd.min.js");
    await loadScript("/vendor/pdf-export/jspdf.plugin.autotable.min.js");
    return window.jspdf.jsPDF;
  })().catch(error => { libraries = null; throw error; });
  return libraries;
}
function amount(value) {
  if (!Number.isFinite(Number(value))) throw new Error("Statement amount is unavailable.");
  const text = "$" + Math.abs(Number(value)).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return Number(value) < 0 ? "(" + text + ")" : text;
}
export function statementFilename(customer) {
  const last = String(customer.last_name || "").trim(), first = String(customer.first_name || "").trim();
  const name = (last && first ? last + "_" + first : (first + " " + last).trim()) + "_POOL_STATEMENT";
  return name.toUpperCase().replace(/[^A-Z0-9_]/g, "_").replace(/_+/g, "_").replace(/^_|_$/g, "") + ".pdf";
}
export function createStatementPdf(JsPDF, customer, records, settings, companyName) {
  const doc = new JsPDF({ unit: "pt", format: settings.pageSize === "A4" ? "a4" : "letter",
    orientation: String(settings.landscape) === "true" ? "landscape" : "portrait", compress: true });
  const width = doc.internal.pageSize.getWidth(), height = doc.internal.pageSize.getHeight();
  const left = 40, right = width - 40, blue = "#1f5fa0", grey = "#595959";
  const text = (value, x, y, size = 10, color = "#000000", align = "left") => {
    doc.setFontSize(size); doc.setTextColor(color);
    doc.text(Array.isArray(value) ? value : String(value || ""), x, y, { align });
  };
  const wrap = (value, size, maxWidth) => {
    doc.setFontSize(size);
    return doc.splitTextToSize(String(value || ""), maxWidth);
  };
  let y = 58, logoBottom = 40;
  if (String(settings.showLogo) !== "false" && settings.logoData) {
    if (!/^data:image\/(png|jpeg);base64,/.test(settings.logoData)) throw new Error("The statement logo is not a supported PNG/JPEG image.");
    const image = doc.getImageProperties(settings.logoData);
    const scale = Math.min(Math.max(40, Number(settings.logoWidth) || 150) / image.width,
      Math.max(30, Number(settings.logoHeight) || 90) / image.height, (right - left) * 0.3 / image.width);
    doc.addImage(settings.logoData, right - image.width * scale, 40, image.width * scale, image.height * scale);
    logoBottom += image.height * scale;
  }
  const company = String(settings.companyName || companyName || "").toUpperCase();
  for (const line of wrap(company, 22, (right - left) * 0.62)) {
    text(line, left, y, 22, "#7f7f7f"); y += 25;
  }
  const contact = String(settings.companyAddress || "").split(/\||\r?\n/).map(s => s.trim()).filter(Boolean);
  if (settings.companyPhone) contact.push(settings.companyPhone);
  if (settings.companyEmail && !String(settings.paymentInfo || "").toLowerCase().includes(settings.companyEmail.toLowerCase())) contact.push(settings.companyEmail);
  for (const value of contact) for (const line of wrap(value, 10, (right - left) * 0.62)) {
    text(line, left, y); y += 13;
  }
  const balance = records.reduce((total, record) => {
    if (record.amount == null || !Number.isFinite(Number(record.amount))) throw new Error("Statement amount is unavailable.");
    return total + Number(record.amount);
  }, 0);
  y = Math.max(y + 12, logoBottom + 26);
  text("STATEMENT", width / 2, y, 22, blue, "center"); y += 20;
  text("Balance: " + amount(balance), width / 2, y, 12, blue, "center"); y += 14;
  const runTable = options => {
    doc.autoTable({ margin: { left, right: 40, top: 40, bottom: 64 },
      styles: { font: "helvetica", fontSize: 10, textColor: "#000000", cellPadding: 6, overflow: "linebreak" },
      ...options });
    y = doc.lastAutoTable.finalY + 10;
  };
  const address = [(customer.first_name || "") + " " + (customer.last_name || ""), customer.address,
    [customer.city, customer.state, customer.zip].filter(Boolean).join(" ")].filter(Boolean).join("\n");
  const serviceDay = (Number(customer.route_order) > 0 ? customer.route_order + " " : "") + String(customer.service_day || "").toUpperCase();
  const info = [["Account No.:", customer.customer_id], ["Phone:", customer.phone],
    ["Service Day:", serviceDay], ["Starting Date:", customer.starting_date]];
  runTable({ startY: y, theme: "grid", body: [[address, info.map(row => row[0] + " " + (row[1] || "")).join("\n")]],
    columnStyles: { 0: { cellWidth: (right - left) * 0.52 } } });
  runTable({ startY: y, theme: "grid", body: [["Note: " + (customer.notes || "")]] });
  if (settings.paymentInfo) runTable({ startY: y, theme: "grid",
    body: [[String(settings.paymentInfo).split("|").map(s => s.trim()).filter(Boolean).join("    ")]],
    bodyStyles: { textColor: blue, halign: "center" } });
  runTable({ startY: y, theme: "grid", head: [["Date", "Description", "Price/Credit"]],
    body: records.length ? records.map(record => [record.record_date, String(record.type || "").toUpperCase(), amount(record.amount)]) : [["", "No records", ""]],
    headStyles: { fillColor: "#ffffff", textColor: grey },
    alternateRowStyles: { fillColor: "#f2f2f2" },
    columnStyles: { 0: { cellWidth: (right - left) * 0.2, halign: "center" }, 2: { cellWidth: (right - left) * 0.24, halign: "right" } } });
  if (y + 24 > height - 64) { doc.addPage(); y = 40; }
  text("Total:", right - 140, y + 14, 11, grey);
  text(amount(balance), right - 6, y + 14, 12, "#000000", "right");
  const pages = doc.getNumberOfPages();
  const generated = String(settings.showGenerationDate) === "false" ? "" : new Date().toLocaleDateString("en-US");
  for (let page = 1; page <= pages; page++) {
    doc.setPage(page);
    text(wrap(settings.footerText, 8, (right - left) * 0.55).slice(0, 3), left, height - 45, 8, grey);
    text(generated, right, height - 45, 8, grey, "right");
    text("Page " + page + " of " + pages, right, height - 28, 8, grey, "right");
  }
  return doc.output("blob");
}

export async function exportStatements({ customers, getRecords, settings, companyName, folder, download, progress }) {
  const JsPDF = await loadStatementExporter();
  const failures = [];
  const filenames = new Set();
  let saved = 0;
  for (const [index, customer] of customers.entries()) {
    const base = statementFilename(customer).slice(0, -4);
    let filename = base + ".pdf", duplicate = 2;
    while (filenames.has(filename)) filename = base + "_" + duplicate++ + ".pdf";
    filenames.add(filename);
    progress(index + 1, customers.length, customer);
    try {
      const records = await getRecords(customer);
      const blob = createStatementPdf(JsPDF, customer, records, settings, companyName);
      if (folder) {
        const file = await folder.getFileHandle(filename, { create: true });
        const writer = await file.createWritable();
        try { await writer.write(blob); await writer.close(); }
        catch (error) { await writer.abort(); throw error; }
      } else await download(blob, filename);
      saved++;
    } catch (error) {
      failures.push({ customer: String(customer.customer_id), filename, error: error.message });
    }
  }
  return { saved, total: customers.length, failures };
}
