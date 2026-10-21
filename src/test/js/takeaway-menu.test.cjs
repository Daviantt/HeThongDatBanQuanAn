const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../.tools/ui-test/node_modules/jsdom');
const root = path.resolve(__dirname, '../../..');
const key = 'giavien-takeaway-cart';
const dish = { id: 1, name: 'Bò lúc lắc', price: 189000, image: '/images/beef.png' };

function setup(t, initial) {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'src/main/resources/templates/menu.html'), 'utf8'), {
    runScripts: 'outside-only', url: 'http://localhost:8080/menu',
  });
  t.after(() => dom.window.close());
  const { window } = dom;
  const doc = window.document;
  const $ = id => doc.getElementById(id);
  $('takeaway-menu').dataset.signedIn = 'true';
  const first = doc.querySelector('.menu-dish-button');
  Object.assign(first.dataset, dish);
  const second = first.cloneNode(true);
  Object.assign(second.dataset, { id: 2, name: 'Bánh flan', price: 39000 });
  first.parentNode.append(second);
  const dialog = $('order-choice');
  // jsdom has no modal top layer; keep native open/close semantics for event tests.
  dialog.showModal = () => { dialog.open = true; };
  dialog.close = () => { dialog.open = false; dialog.dispatchEvent(new window.Event('close')); };
  if (initial !== undefined) window.localStorage.setItem(key, typeof initial === 'string' ? initial : JSON.stringify(initial));
  window.eval(fs.readFileSync(path.join(root, 'src/main/resources/static/js/takeaway-menu.js'), 'utf8'));
  const choose = (button = first) => { button.click(); $('add-takeaway').click(); };
  const setQuantity = value => {
    $('takeaway-quantity').value = String(value);
    $('takeaway-quantity').dispatchEvent(new window.Event('input'));
  };
  const submit = () => $('takeaway-quantity-form').dispatchEvent(new window.Event('submit', { cancelable: true }));
  const cart = () => JSON.parse(window.localStorage.getItem(key));
  return { window, doc, $, first, second, dialog, choose, setQuantity, submit, cart };
}

test('only confirmation saves the chosen quantity; highlighted cart has checkout at bottom', t => {
  const v = setup(t);
  const { $, choose, setQuantity, submit, cart, dialog } = v;
  assert.equal($('takeaway-cart').hidden, true);
  choose();
  assert.equal(dialog.open, true);
  assert.equal($('takeaway-quantity-form').hidden, false);
  assert.equal(cart(), null);
  assert.equal($('takeaway-quantity-minus').disabled, true);
  $('takeaway-quantity-plus').click();
  $('takeaway-quantity-plus').click();
  assert.equal($('takeaway-quantity').value, '3');
  assert.equal($('takeaway-quantity-total').textContent, '567.000 ₫');
  $('takeaway-quantity-minus').click();
  setQuantity(3);
  submit();
  assert.deepEqual(cart(), [{ ...dish, quantity: 3 }]);
  assert.equal(dialog.open, false);
  assert.equal($('takeaway-cart').hidden, false);
  assert.equal($('takeaway-cart-count').textContent, '3 món');
  assert.equal($('takeaway-cart-total').textContent, '567.000 ₫');
  assert.match($('takeaway-cart-items').textContent, /3 × Bò lúc lắc/);
  assert.match($('takeaway-cart-message').textContent, /Đã thêm 3 phần/);
  assert.ok($('takeaway-cart').classList.contains('cart-updated'));
  assert.equal($('takeaway-cart').lastElementChild.id, 'takeaway-checkout-link');
  assert.equal($('takeaway-checkout-link').getAttribute('href'), '/takeaway');
  assert.match($('takeaway-checkout-link').textContent, /Chốt đơn/);
  submit();
  assert.equal(cart()[0].quantity, 3, 'duplicate submission after close cannot add again');
});

test('closing or going back discards unconfirmed quantity and reopens at one', t => {
  const { $, choose, setQuantity, cart, first, dialog, submit } = setup(t);
  choose();
  setQuantity(5);
  $('takeaway-quantity-back').click();
  assert.equal($('order-choice-options').hidden, false);
  assert.equal($('takeaway-quantity-form').hidden, true);
  submit();
  assert.equal(cart(), null);
  $('add-takeaway').click();
  assert.equal($('takeaway-quantity').value, '1');
  setQuantity(6);
  dialog.querySelector('.modal-close').click();
  assert.equal(cart(), null);
  first.click();
  assert.equal($('order-choice-options').hidden, false);
  $('add-takeaway').click();
  assert.equal($('takeaway-quantity').value, '1');
  // Native Escape also closes the dialog without ever submitting the form.
  dialog.close();
  assert.equal(cart(), null);
});

test('empty, fractional, nonpositive and excessive quantities never write to storage', t => {
  const { $, choose, setQuantity, submit, cart, dialog } = setup(t);
  choose();
  for (const invalid of ['', 0, -1, 1.5, 21, 'abc']) {
    setQuantity(invalid);
    submit();
    assert.equal(cart(), null);
    assert.equal(dialog.open, true);
    assert.equal($('takeaway-quantity-error').hidden, false);
    assert.equal($('takeaway-quantity').getAttribute('aria-invalid'), 'true');
  }
  setQuantity(20);
  assert.equal($('takeaway-quantity-plus').disabled, true);
  submit();
  assert.equal(cart()[0].quantity, 20);
});

test('existing quantities count toward the per-dish limit and full dishes cannot be added', t => {
  const { $, choose, setQuantity, submit, cart } = setup(t, [{ ...dish, quantity: 18 }]);
  choose();
  assert.equal($('takeaway-quantity').max, '2');
  setQuantity(3);
  submit();
  assert.equal(cart()[0].quantity, 18);
  setQuantity(2);
  submit();
  assert.equal(cart()[0].quantity, 20);
  choose();
  assert.equal($('confirm-takeaway').disabled, true);
  assert.equal($('takeaway-quantity').disabled, true);
  assert.match($('takeaway-quantity-help').textContent, /đủ 20 phần/);
  submit();
  assert.equal(cart()[0].quantity, 20);
});

test('restored cart and multiple dishes show correct counts and totals without HTML injection', t => {
  const { $, choose, setQuantity, submit, cart, second, doc } = setup(t, [{ ...dish, name: '<img src=x onerror=alert(1)>', quantity: 2 }]);
  assert.equal($('takeaway-cart').hidden, false);
  assert.equal($('takeaway-cart-total').textContent, '378.000 ₫');
  assert.equal($('takeaway-cart-items').querySelector('img'), null);
  choose(second);
  setQuantity(3);
  submit();
  assert.equal(cart().length, 2);
  assert.equal($('takeaway-cart-count').textContent, '5 món');
  assert.equal($('takeaway-cart-total').textContent, '495.000 ₫');
  assert.ok(doc.body.classList.contains('has-takeaway-cart'));
});

test('cart changes in another tab update the preview and are revalidated on confirmation', t => {
  const { $, window, choose, setQuantity, submit, cart } = setup(t, [{ ...dish, quantity: 18 }]);
  choose();
  setQuantity(2);
  window.localStorage.setItem(key, JSON.stringify([{ ...dish, quantity: 19 }]));
  submit();
  assert.equal(cart()[0].quantity, 19);
  assert.equal($('takeaway-quantity-error').hidden, false);
  window.dispatchEvent(new window.StorageEvent('storage', { key }));
  assert.equal($('takeaway-cart-count').textContent, '19 món');
  window.localStorage.clear();
  window.dispatchEvent(new window.StorageEvent('storage', { key: null }));
  assert.equal($('takeaway-cart').hidden, true);
});

test('malformed saved data does not break the picker', t => {
  for (const invalid of ['{', '{}', '[null, {"id":1}]']) {
    const { $, choose, submit, cart } = setup(t, invalid);
    assert.equal($('takeaway-cart').hidden, true);
    choose();
    submit();
    assert.deepEqual(cart(), [{ ...dish, quantity: 1 }]);
  }
});

test('storage failure keeps popup open and does not claim success', t => {
  const { $, window, choose, submit, cart, dialog } = setup(t);
  choose();
  window.Storage.prototype.setItem = () => { throw new Error('quota'); };
  submit();
  assert.equal(dialog.open, true);
  assert.equal(cart(), null);
  assert.equal($('takeaway-cart').hidden, true);
  assert.match($('takeaway-quantity-error').textContent, /Không lưu được giỏ hàng/);
});
