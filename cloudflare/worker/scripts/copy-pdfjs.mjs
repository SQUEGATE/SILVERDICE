import { mkdir, copyFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = join(root, "node_modules", "pdfjs-dist");
const target = join(root, "public", "vendor", "pdfjs");
await mkdir(target, { recursive: true });
for (const [from, to] of [
  ["legacy/build/pdf.mjs", "pdf.mjs"],
  ["legacy/build/pdf.worker.mjs", "pdf.worker.mjs"],
  ["LICENSE", "LICENSE"],
]) {
  await copyFile(join(source, from), join(target, to));
}
console.log("Copied PDF.js local parser, worker, and Apache-2.0 license.");
const exportTarget = join(root, "public", "vendor", "pdf-export");
await mkdir(exportTarget, { recursive: true });
for (const [packageName, files] of [
  ["jspdf", ["dist/jspdf.umd.min.js", "LICENSE"]],
  ["jspdf-autotable", ["dist/jspdf.plugin.autotable.min.js", "LICENSE.txt"]],
]) {
  for (const file of files) {
    await copyFile(join(root, "node_modules", packageName, file),
      join(exportTarget, file.startsWith("LICENSE") ? packageName + "-LICENSE" : file.split("/").pop()));
  }
}
console.log("Copied local statement PDF generation libraries and licenses.");
