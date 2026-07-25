import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders the Xixi Travel experience", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>嘻嘻出行｜一句话，轻松出发<\/title>/i);
  assert.match(html, /今天想去哪儿/);
  assert.match(html, /XIXI AI ASSISTANT/);
  assert.match(html, /当前时间/);
  assert.match(html, /预计到达/);
  assert.match(html, /© OpenStreetMap contributors/);
  assert.doesNotMatch(html, /codex-preview|SkeletonPreview|AI小滴/);
});

test("wires navigation, live time, trips and invoices", async () => {
  const source = await readFile(
    new URL("../app/XixiTravelApp.tsx", import.meta.url),
    "utf8",
  );

  assert.match(source, /type AppView = "ride" \| "trips" \| "invoices"/);
  assert.match(source, /setActiveView\(view\)/);
  assert.match(source, /window\.setInterval\(tick, 1000\)/);
  assert.match(source, /formatCountdown\(driverCountdown\)/);
  assert.match(source, /我的行程/);
  assert.match(source, /行程发票/);
  assert.match(source, /requestInvoice/);
  assert.match(source, /window\.localStorage/);
  assert.doesNotMatch(source, />21:06</);
});
