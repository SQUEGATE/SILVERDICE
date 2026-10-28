// Run: node --test test/visits-web.test.js   (no dependencies; extracts real functions from public/index.html)
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const html = fs.readFileSync(new URL("../public/index.html", import.meta.url), "utf8");
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

function extractFn(name) {
  const start = script.indexOf("function " + name + "(");
  assert.ok(start >= 0, "missing function " + name);
  let i = script.indexOf("{", start), depth = 0, inStr = null, inRe = false;
  for (; i < script.length; i++) {
    const ch = script[i];
    if (inStr) { if (ch === "\\") i++; else if (ch === inStr) inStr = null; continue; }
    if (ch === '"' || ch === "'") { inStr = ch; continue; }
    if (ch === "{") depth++;
    else if (ch === "}" && --depth === 0) return script.slice(start, i + 1);
  }
  throw new Error("unterminated " + name);
}
const consts = script.match(/var V_PH=[^\n]*\r?\nvar V_SALT_NONE=[^\n]*\r?\nvar V_COLORS=[^\n]*\r?\nfunction visitSaltError[^\n]*\r?\nvar V_MAX_INT[^\n]*\r?\nvar V_PHOTO_RE[^\n]*/)[0];
const dflt = script.match(/var V_DEFAULT=[^\n]*/)[0];
const ctx = vm.createContext({});
vm.runInContext([consts, dflt, ...["visitInt", "visitValidate", "visitRowError", "visitSort", "visitPhotoError", "visitScale", "visitFitPhoto"].map(extractFn),
  "this.api={visitInt,visitValidate,visitRowError,visitSort,V_PH,V_CHLO,V_STB,V_SALT,V_SALT_NONE,V_DEFAULT,V_MAX_NOTES,visitPhotoError,visitScale,visitFitPhoto,V_MAX_PHOTO};"].join("\n"), ctx);
const A = ctx.api;
const good = { ph: "7.4", chlo: "3-6", stb: "50", alk: "80", cal: "300", notes: "x" };

test("choice lists are exact", () => {
  assert.deepEqual([...A.V_PH], ["7.0", "7.2", "7.4", "7.6", "7.8", "8.0"]);
  assert.deepEqual([...A.V_CHLO], ["1-2", "2-4", "3-6", "5-10", "7.5-15", "10-20"]);
  assert.deepEqual([...A.V_STB], ["100", "90", "80", "70", "60", "50", "40", "30"]);
  assert.deepEqual([...A.V_SALT], ["Not recorded", "420", "510", "600", "700", "800", "920", "1040", "1180", "1320", "1470", "1630", "1800",
    "1990", "2180", "2390", "2610", "2840", "3080", "3350", "3630", "3920", "4240", "4580", "4950"]);
  assert.deepEqual({ ...A.V_DEFAULT }, { ph: "7.4", chlo: "3-6", stb: "50" });
});
test("SALT is optional and limited to the listed readings", () => {
  assert.equal(A.visitValidate(good).body.salt, null);
  assert.equal(A.visitValidate({ ...good, salt: A.V_SALT_NONE }).body.salt, null);
  assert.equal(A.visitValidate({ ...good, salt: "4950" }).body.salt, 4950);
  assert.equal(A.visitValidate({ ...good, salt: "420" }).body.salt, 420);
  assert.match(A.visitValidate({ ...good, salt: "421" }).error, /SALT/);
  const row = { visit_id: 1, visited_at: "2026-10-26T14:00:00.000Z", ph: "7.4", chlo: "3-6", stb: "50", alk: null, cal: null, notes: "", photo_data: "" };
  assert.equal(A.visitRowError(row), "");
  assert.equal(A.visitRowError({ ...row, salt: null }), "");
  assert.equal(A.visitRowError({ ...row, salt: 3080 }), "");
  assert.match(A.visitRowError({ ...row, salt: 3081 }), /SALT/);
});
test("ALK/CAL accept positive whole ints up to 2147483647", () => {
  for (const v of ["1", " 80 ", "007", "2147483647"]) assert.ok(A.visitInt(v, "ALK").value > 0, v);
  assert.equal(A.visitInt("2147483647", "ALK").value, 2147483647);
});
test("ALK/CAL reject explicitly", () => {
  const bad = { "1.5": /whole number/, "5.0": /whole number/, "-1": /negative/, "-0": /negative/, "0": /greater than zero/, "000": /greater than zero/, abc: /must be a number/, "1e3": /whole number/, "+5": /./, "2147483648": /at most/, "99999999999999999999": /at most/, "1 2": /number/ };
  for (const [v, re] of Object.entries(bad)) { const r = A.visitInt(v, "CAL"); assert.ok(r.error && r.value === undefined, "should reject " + v); assert.match(r.error, re, v); }
});
test("ALK/CAL optional: blank becomes null, nonblank still strict", () => {
  for (const v of ["", "   ", null, undefined]) assert.equal(A.visitInt(v, "ALK", true).value, null);
  assert.equal(A.visitValidate({ ...good, alk: "", cal: " " }).body.alk, null);
  assert.equal(A.visitValidate({ ...good, alk: "", cal: " " }).body.cal, null);
  assert.match(A.visitValidate({ ...good, alk: "-1", cal: "" }).error, /negative/);
  assert.match(A.visitValidate({ ...good, alk: "", cal: "2.5" }).error, /CAL.*whole/);
  assert.match(A.visitValidate({ ...good, alk: "0" }).error, /greater than zero/);
  assert.match(A.visitInt("", "ALK").error, /required/);
});
test("photo validation and body", () => {
  const ok = "data:image/jpeg;base64,/9j/4AAQ", png = "data:image/png;base64,iVBORw0KGgo=";
  assert.equal(A.visitPhotoError(""), ""); assert.equal(A.visitPhotoError(undefined), ""); assert.equal(A.visitPhotoError(ok), ""); assert.equal(A.visitPhotoError(png), "");
  for (const bad of ["data:image/gif;base64,AAAA", "data:text/html;base64,AAAA", "http://x/y.png", "data:image/png;base64,", "data:image/png;base64,AA AA", 5])
    assert.ok(A.visitPhotoError(bad), String(bad));
  const big = "data:image/jpeg;base64," + "A".repeat(A.V_MAX_PHOTO);
  assert.match(A.visitPhotoError(big), /too large/);
  assert.equal(A.visitPhotoError("data:image/jpeg;base64," + "A".repeat(A.V_MAX_PHOTO - 23)), "");
  assert.equal(A.visitValidate({ ...good, photo_data: ok }).body.photo_data, ok);
  assert.equal(A.visitValidate(good).body.photo_data, "");
  assert.ok(A.visitValidate({ ...good, photo_data: big }).error);
});
test("photo scale and fit loop", () => {
  assert.deepEqual({ ...A.visitScale(4000, 2000, 1280) }, { w: 1280, h: 640 });
  assert.deepEqual({ ...A.visitScale(800, 600, 1280) }, { w: 800, h: 600 });
  const calls = [];
  const r = A.visitFitPhoto(4000, 3000, (w, h, q) => { calls.push([w, h, q]); return "data:image/jpeg;base64," + "A".repeat(w * h > 500000 ? 700000 : 1000); });
  assert.ok(r.data && r.data.length <= A.V_MAX_PHOTO);
  assert.ok(calls.every(([w, h]) => w <= 1280 && h <= 1280));
  assert.ok(calls.length > 1 && calls[1][2] < calls[0][2], "quality is lowered first");
  const fail = A.visitFitPhoto(100, 100, () => "data:image/jpeg;base64," + "A".repeat(700000));
  assert.ok(fail.error && !fail.data);
});
test("saved rows: null ALK/CAL and missing photo ok; bad photo errors", () => {
  const row = { ph: "7.4", chlo: "3-6", stb: "50", alk: null, cal: null, notes: "" };
  assert.equal(A.visitRowError(row), "");
  assert.equal(A.visitRowError({ ...row, photo_data: "" }), "");
  assert.match(A.visitRowError({ ...row, photo_data: "junk" }), /photo is invalid/);
  assert.match(A.visitRowError({ ...row, alk: 0 }), /ALK/);
});
test("capture inputs are wired", () => {
  assert.match(html, /id="v_cam"[^>]*accept="image\/\*"[^>]*capture="environment"/);
  assert.match(html, /id="v_file"[^>]*accept="image\/\*">/);
  assert.ok(!/id="v_file"[^>]*capture/.test(html));
});
test("notes limit 16000", () => {
  assert.ok(A.visitValidate({ ...good, notes: "a".repeat(16000) }).body);
  assert.match(A.visitValidate({ ...good, notes: "a".repeat(16001) }).error, /16000/);
});
test("visitValidate builds numeric body and rejects bad choices", () => {
  assert.deepEqual({ ...A.visitValidate(good).body }, { ph: "7.4", chlo: "3-6", stb: "50", salt: null, alk: 80, cal: 300, notes: "x", photo_data: "" });
  for (const k of ["ph", "chlo", "stb"]) assert.ok(A.visitValidate({ ...good, [k]: "nope" }).error);
});
test("invalid saved rows give explicit errors, no defaults", () => {
  const row = { ph: "7.4", chlo: "3-6", stb: "50", alk: 80, cal: 300, notes: "" };
  assert.equal(A.visitRowError(row), "");
  assert.match(A.visitRowError({ ...row, ph: "9.9" }), /invalid PH/);
  assert.match(A.visitRowError({ ...row, chlo: "" }), /invalid CHLO/);
  assert.match(A.visitRowError({ ...row, stb: undefined }), /invalid STB/);
  assert.match(A.visitRowError({ ...row, alk: 1.5 }), /ALK/);
  assert.match(A.visitRowError({ ...row, cal: -2 }), /CAL/);
  assert.ok(A.visitRowError(null));
  assert.ok(!script.includes("VT-START") && !script.includes("VT-END"));
});
test("visits sorted newest first, ties by id", () => {
  const s = A.visitSort([{ visit_id: 1, visited_at: "2024-01-01T00:00:00Z" }, { visit_id: 2, visited_at: "2024-03-01T00:00:00Z" }, { visit_id: 3, visited_at: "2024-03-01T00:00:00Z" }]);
  assert.deepEqual(s.map((v) => v.visit_id), [3, 2, 1]);
});
test("permission wiring present", () => {
  assert.match(script, /\["canAccessVisits","Access visits"\]/);
  assert.match(script, /id:"visits",label:"Visits",perm:function\(\)\{return can\("canAccessVisits"\)\}/);
});
