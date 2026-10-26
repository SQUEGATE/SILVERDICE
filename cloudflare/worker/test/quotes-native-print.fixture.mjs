// Native Edge/PDFBox pagination validation; existing local tools only, no packages.
// From worker: node test/quotes-native-print.fixture.mjs
// Optional environment: QUOTE_EDGE, QUOTE_PDFBOX_CP (Java classpath).
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";
import assert from "node:assert/strict";

const worker = fileURLToPath(new URL("../", import.meta.url));
const edge = process.env.QUOTE_EDGE || "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const cp = process.env.QUOTE_PDFBOX_CP || path.resolve(worker, "..\\..\\gradle-build\\dist\\Comp Manager\\app\\*");
const out = path.join(worker, "test", "fixtures");
const htmlFile = path.join(out, "quotes-native-long.html");
const pdfFile = path.join(out, "quotes-native-long.pdf");
const profile = path.join(out, "quotes-native-edge-profile");
const html = fs.readFileSync(path.join(worker, "public", "index.html"), "utf8");
const elements = new Map();
function element(id) {
  if (!elements.has(id)) elements.set(id, { innerHTML: "", value: "", classList: { toggle() {}, remove() {} }, querySelectorAll: () => [], addEventListener() {} });
  return elements.get(id);
}
const ctx = vm.createContext({
  localStorage: { getItem: () => null }, document: { getElementById: element, addEventListener() {}, title: "Synthetic print" },
  window: { addEventListener() {}, removeEventListener() {}, print() {}, scrollTo() {} }, setTimeout() {}, clearInterval() {},
});
vm.runInContext(html.match(/<script>([\s\S]*)<\/script>/)[1], ctx);
ctx.S.account = { role: "COMPANY", companyName: "Synthetic Pagination Co" };
const maxText = (start, end) => start + ("Synthetic paragraph. ".repeat(5) + "\n\n").repeat(200).slice(0, 16000 - start.length - end.length) + end;
const notes = maxText("NOTES_BEGIN ", " NOTES_END");
const terms = maxText("TERMS_BEGIN ", " TERMS_END");
assert.equal(notes.length, 16000); assert.equal(terms.length, 16000);
ctx.printQuote({ first_name: "Synthetic", last_name: "Pagination", address: "1 Test Street", city: "Example", state: "ZZ", zip: "00000" }, {
  quote_number: "SQ-009999", quote_date: "10/26/2026", notes, terms,
  lines: Array.from({ length: 200 }, (_, i) => ({ date: "10/26/2026", type: "Service", description: "LINE_" + String(i + 1).padStart(3, "0") + "_END — " + "Synthetic equipment service. ".repeat(5), amount: -1.01 })),
}, { companyName: "Synthetic Pagination Co", companyAddress: "100 Example Ave|Example, ZZ 00000", companyPhone: "5555550100", paymentInfo: "PAYMENT_FOOTER_END", showLogo: "false" });
const print = element("printarea").innerHTML;
fs.writeFileSync(htmlFile, '<!doctype html><html><head><meta charset="utf-8"><title>Synthetic native pagination</title><style>body{margin:0;background:white}*{box-sizing:border-box}table{width:100%;border-collapse:collapse}th{text-align:left}</style></head><body><div id="printarea">' + print + "</div></body></html>");
try {
  const edgeResult = spawnSync(edge, ["--headless", "--disable-gpu", "--no-first-run", "--no-pdf-header-footer",
    "--user-data-dir=" + profile, "--print-to-pdf=" + pdfFile, pathToFileURL(htmlFile).href], { encoding: "utf8", timeout: 120000 });
  if (!fs.existsSync(pdfFile)) throw new Error("Edge did not create PDF: " + edgeResult.stderr);
  const javaPdf = pdfFile.replace(/\\/g, "\\\\");
  const commands = [
    'import org.apache.pdfbox.Loader;',
    'import org.apache.pdfbox.text.PDFTextStripper;',
    'import java.io.File;',
    'var doc = Loader.loadPDF(new File("' + javaPdf + '"));',
    'var stripper = new PDFTextStripper();',
    'var text = stripper.getText(doc);',
    'System.out.println("NATIVE_PAGES=" + doc.getNumberOfPages());',
    'System.out.println("ALL_MARKERS=" + java.util.stream.IntStream.rangeClosed(1,200).allMatch(i -> text.contains(String.format("LINE_%03d_END",i))));',
    'System.out.println("NOTES_TERMS=" + (text.contains("NOTES_BEGIN") && text.contains("NOTES_END") && text.contains("TERMS_BEGIN") && text.contains("TERMS_END")));',
    'System.out.println("SUMMARY=" + (text.contains("SQ-009999") && text.contains("10/26/2026") && text.contains("-$202.00") && text.contains("PAYMENT_FOOTER_END")));',
    'System.out.println("DESCRIPTION_HEADERS=" + (text.split("Description",-1).length-1));',
    'var renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);',
    'javax.imageio.ImageIO.write(renderer.renderImageWithDPI(0,96),"png",new File("' + path.join(out, "quotes-native-long-first.png").replace(/\\/g, "\\\\") + '"));',
    'javax.imageio.ImageIO.write(renderer.renderImageWithDPI(doc.getNumberOfPages()/2,96),"png",new File("' + path.join(out, "quotes-native-long-middle.png").replace(/\\/g, "\\\\") + '"));',
    'javax.imageio.ImageIO.write(renderer.renderImageWithDPI(doc.getNumberOfPages()-1,96),"png",new File("' + path.join(out, "quotes-native-long-last.png").replace(/\\/g, "\\\\") + '"));',
    'doc.close();',
    '/exit',
  ].join("\n");
  const inspected = spawnSync("jshell", ["--class-path", cp, "-"], { input: commands, encoding: "utf8", timeout: 120000 });
  const result = inspected.stdout + inspected.stderr;
  for (const expected of ["ALL_MARKERS=true", "NOTES_TERMS=true", "SUMMARY=true"]) assert.ok(result.includes(expected), result);
  const pages = Number(result.match(/NATIVE_PAGES=(\d+)/)?.[1]);
  const headers = Number(result.match(/DESCRIPTION_HEADERS=(\d+)/)?.[1]);
  assert.ok(pages >= 10 && headers >= 2, result);
  console.log(JSON.stringify({ pdf: pdfFile, bytes: fs.statSync(pdfFile).size, pages, repeatedTableHeaders: headers,
    all200LineMarkers: true, maximumNotesTermsEndMarkers: true, numberDateDescriptionTotalPaymentFooter: true }, null, 2));
} finally {
  fs.rmSync(htmlFile, { force: true });
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
}
