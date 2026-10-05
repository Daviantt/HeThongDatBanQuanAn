const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../.tools/ui-test/node_modules/jsdom');
const script = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/payment.js'), 'utf8');

function page(fetch) {
  const dom = new JSDOM('<section id="payment-result" data-status-url="/api/bookings/test/payment-status"><h1 id="payment-title"></h1><p id="payment-message"></p></section>', { runScripts: 'outside-only' });
  const tasks = [];
  dom.window.fetch = fetch;
  dom.window.setTimeout = callback => { tasks.push(callback); };
  dom.window.eval(script);
  return { dom, tasks, message: () => dom.window.document.getElementById('payment-message').textContent };
}
const settle = () => new Promise(resolve => setImmediate(resolve));

test('payment result waits for the server and stops after confirmation', async () => {
  let requests = 0;
  const view = page(async (url, options) => {
    assert.equal(url, '/api/bookings/test/payment-status');
    assert.equal(options.cache, 'no-store');
    const waiting = requests++ === 0;
    return { ok: true, status: 200, json: async () => ({ title: waiting ? 'Đang chờ' : 'Đã nhận cọc', message: waiting ? 'Chờ xác nhận' : 'Đã nhận tiền', waiting }) };
  });
  try {
    await settle();
    assert.equal(view.message(), 'Chờ xác nhận');
    assert.equal(view.tasks.length, 1);
    await view.tasks.shift()();
    assert.equal(view.message(), 'Đã nhận tiền');
    assert.equal(view.tasks.length, 0);
    assert.equal(requests, 2);
  } finally { view.dom.window.close(); }
});

test('expired session stops polling and asks the customer to open their booking', async () => {
  const view = page(async () => ({ status: 401 }));
  try {
    await settle();
    assert.match(view.message(), /đăng nhập/);
    assert.equal(view.tasks.length, 0);
  } finally { view.dom.window.close(); }
});

test('network failures stop after the retry limit without claiming payment succeeded', async () => {
  let requests = 0;
  const view = page(async () => { requests++; throw new Error('offline'); });
  try {
    await settle();
    while (view.tasks.length) await view.tasks.shift()();
    assert.equal(requests, 30);
    assert.match(view.message(), /Chưa nhận được xác nhận/);
    assert.match(view.message(), /không thanh toán thêm/);
  } finally { view.dom.window.close(); }
});
