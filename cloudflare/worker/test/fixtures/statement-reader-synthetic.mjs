export function item(str, x, y, width = str.length * 5) {
  return { str, transform: [1, 0, 0, 1, x, y], width, height: 10 };
}
export function header(y = 700) {
  return [
    item("Check", 100, y), item("Deposits/", 404, y, 46), item("Withdrawals/", 458, y, 56), item("Ending daily", 525, y, 46),
    item("Date", 61, y - 12), item("Number", 100, y - 12), item("Description", 160, y - 12),
    item("Additions", 404, y - 12, 46), item("Subtractions", 458, y - 12, 56), item("balance", 532, y - 12, 39),
  ];
}
export function syntheticPages() {
  return [
    { items: [
      ...header(),
      item("9/8", 61, 670), item("Zelle From Example Mira on 09/04 Ref # Syn123", 160, 670, 230),
      item("100.00", 420, 670, 30), item("500.00", 541, 670, 30),
      item("Wrapped selectable description", 160, 658),
      item("9/9", 61, 640), item("Zelle To Example Mira Ref # Syn456", 160, 640, 220),
      item("12,345.00", 469, 640, 45), item("700.00", 541, 640, 30),
      item("9/10", 61, 620), item("Zelle From Demo Avery Ref # Syn789", 160, 620, 220),
      item("125.00", 420, 620, 30), item("825.00", 541, 620, 30),
    ] },
    { items: [
      ...header(),
      item("9/11", 61, 670), item("Zelle From Fiction Rowan Ref # Syn012", 160, 670, 220),
      item("75.00", 425, 670, 25), item("900.00", 541, 670, 30),
    ] },
  ];
}
export function makePdf(pages = syntheticPages()) {
  const objects = [];
  const add = text => { objects.push(text); return objects.length; };
  add("<< /Type /Catalog /Pages 2 0 R >>");
  add("");
  const font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
  const kids = [];
  for (const page of pages) {
    const content = (page.items || []).map(t => {
      const text = t.str.replace(/[\\()]/g, "\\$&");
      return `BT /F1 10 Tf 1 0 0 1 ${t.transform[4]} ${t.transform[5]} Tm (${text}) Tj ET`;
    }).join("\n");
    const stream = add(`<< /Length ${Buffer.byteLength(content)} >>\nstream\n${content}\nendstream`);
    kids.push(add(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 ${font} 0 R >> >> /Contents ${stream} 0 R >>`));
  }
  objects[1] = `<< /Type /Pages /Kids [${kids.map(n => `${n} 0 R`).join(" ")}] /Count ${kids.length} >>`;
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  objects.forEach((o, i) => { offsets.push(Buffer.byteLength(pdf)); pdf += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = Buffer.byteLength(pdf);
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  pdf += offsets.slice(1).map(n => String(n).padStart(10, "0") + " 00000 n \n").join("");
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return Buffer.from(pdf);
}
