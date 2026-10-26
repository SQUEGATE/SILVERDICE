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
