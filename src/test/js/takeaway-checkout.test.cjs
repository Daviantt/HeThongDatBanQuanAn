const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../.tools/ui-test/node_modules/jsdom');
const root = path.resolve(__dirname, '../../..');
const key = 'giavien-takeaway-cart';
const dish = { id: 1, name: 'Cơm chiên', price: 65000, quantity: 1, image: '/images/menu/com-nieu.jpg' };
const settle = () => new Promise(resolve => setImmediate(resolve));
const response = (status, data) => ({ status, ok: status >= 200 && status < 300, json: async () => data });
const quote = (price = 65000, quantity = 1, name = dish.name) => ({
  lines: [{ menuItemId: 1, itemName: name, unitPrice: price, quantity }], total: price * quantity,
});
function setup(t, saved = [dish], handler = () => response(200, quote())) {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'src/main/resources/templates/takeaway.html'), 'utf8'), {
    runScripts: 'outside-only', url: 'http://localhost/takeaway',
  });
  t.after(() => dom.window.close());
  const { window } = dom;
  const doc = window.document;
  for (const [name, content] of Object.entries({ _csrf: 'token', _csrf_header: 'X-CSRF-TOKEN', _csrf_parameter: '_csrf' })) {
    const meta = doc.createElement('meta'); meta.name = name; meta.content = content; doc.head.append(meta);
  }
  window.localStorage.setItem(key, JSON.stringify(saved));
  const calls = [];
  window.fetch = async (url, options) => {
    const body = JSON.parse(options.body);
    calls.push({ url, body });
    return handler(url, body, calls.length);
  };
  window.eval(fs.readFileSync(path.join(root, 'src/main/resources/static/js/takeaway.js'), 'utf8'));
  return {
    window, doc, calls, $: id => doc.getElementById(id),
    submit: () => doc.getElementById('takeaway-form').dispatchEvent(new window.Event('submit', { cancelable: true })),
  };
}

test('checkout treats saved and server dish names as text and rejects unsafe image URLs', async t => {
  const name = '<img src=x onerror="window.injected=true"><b>Cơm</b>';
  const v = setup(t, [{ ...dish, name, image: 'javascript:alert(1)' }], () => response(200, quote(65000, 1, name)));
  assert.equal(v.doc.querySelector('#checkout-lines h3').textContent, name);
  assert.equal(v.doc.querySelector('#checkout-lines h3 img'), null);
  assert.equal(v.doc.querySelector('#checkout-lines img').getAttribute('src'), '/images/dish.svg');
  await settle();
  assert.equal(v.doc.querySelector('#checkout-lines h3').textContent, name);
  assert.equal(v.doc.querySelector('#checkout-lines [onerror]'), null);
  assert.equal(v.window.injected, undefined);
});

test('malformed saved cart cannot crash checkout or send invalid items', async t => {
  const v = setup(t, { unexpected: 'object' });
  await settle();
  assert.equal(v.$('checkout-empty').hidden, false);
  assert.equal(v.$('takeaway-submit').disabled, true);
  assert.equal(v.calls.length, 0);
});

test('fresh server quote replaces stale prices and failed checkout retains the cart', async t => {
  const v = setup(t, [dish], (url) => url.endsWith('/quote')
    ? response(200, quote(80000)) : response(400, { error: 'Giờ lấy không hợp lệ' }));
  assert.equal(v.$('takeaway-submit').disabled, true);
  v.submit();
  assert.equal(v.calls.length, 1);
  await settle();
  assert.equal(v.$('takeaway-total').textContent, '80.000 ₫');
  assert.match(v.$('takeaway-error').textContent, /cập nhật/);
  v.submit();
  await settle();
  assert.deepEqual(v.calls[1].body.expectedUnitPrices, { 1: 80000 });
  assert.equal(v.$('takeaway-error').textContent, 'Giờ lấy không hợp lệ');
  assert.equal(JSON.parse(v.window.localStorage.getItem(key)).length, 1);
});

test('price change during submit shows new total and requires a second explicit confirmation', async t => {
  let submitted = 0;
  const v = setup(t, [dish], url => {
    if (url.endsWith('/quote')) return response(200, quote());
    submitted++;
    return submitted === 1 ? response(409, { error: 'Giá món đã thay đổi. Xác nhận lại.', quote: quote(90000) })
      : response(400, { error: 'Stopped after verifying request' });
  });
  await settle();
  v.submit();
  v.submit(); // The pending request must block duplicate clicks.
  await settle();
  assert.equal(submitted, 1);
  assert.equal(v.$('takeaway-total').textContent, '90.000 ₫');
  assert.equal(v.$('takeaway-submit').disabled, false);
  assert.match(v.$('takeaway-error').textContent, /Xác nhận lại/);
  v.submit();
  await settle();
  assert.equal(submitted, 2);
  assert.deepEqual(v.calls[2].body.expectedUnitPrices, { 1: 90000 });
});

test('older quote responses cannot overwrite a newer quantity', async t => {
  const pending = [];
  const v = setup(t, [dish], () => new Promise(resolve => pending.push(resolve)));
  v.doc.querySelector('[data-change="1"]').click();
  pending[1](response(200, quote(65000, 2)));
  await settle();
  pending[0](response(200, quote(65000, 1)));
  await settle();
  assert.equal(v.$('takeaway-total').textContent, '130.000 ₫');
  assert.equal(JSON.parse(v.window.localStorage.getItem(key))[0].quantity, 2);
});

test('unavailable dishes or network errors cannot enable placing an unquoted order', async t => {
  const v = setup(t, [dish], () => response(400, { error: 'Món đã tạm hết' }));
  await settle();
  assert.equal(v.$('takeaway-submit').disabled, true);
  assert.equal(v.$('takeaway-error').textContent, 'Món đã tạm hết');
  v.submit();
  assert.equal(v.calls.length, 1);
});
