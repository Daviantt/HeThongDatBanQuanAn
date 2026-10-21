const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../.tools/ui-test/node_modules/jsdom');
const script = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/demo-payment.js'), 'utf8');
function page(fetch) {
  const dom = new JSDOM('<section id="demo-checkout" data-state="WAITING" data-status-url="/payment/demo/token/status"><div id="demo-qr"></div><p id="demo-message"></p><form id="demo-confirm"><button id="demo-pay-button" disabled></button></form></section>', {runScripts:'outside-only'});
  const tasks = [];
  dom.window.fetch = fetch;
  dom.window.setTimeout = callback => tasks.push(callback);
  dom.window.eval(script);
  return { dom, tasks, button:dom.window.document.getElementById('demo-pay-button') };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
test('only server readiness enables manual confirmation; elapsed time never submits payment', async () => {
  let calls = 0;
  const view = page(async (url, options) => {
    assert.equal(url, '/payment/demo/token/status');
    assert.equal(options.method, undefined);
    assert.equal(options.cache, 'no-store');
    const ready = calls++ > 0;
    return {ok:true, json:async () => ({status:ready ? 'READY':'WAITING', remainingSeconds:ready ? 0:1, message:'demo'})};
  });
  try {
    let submitted = false;
    view.dom.window.document.getElementById('demo-confirm').addEventListener('submit', () => submitted=true);
    await settle();
    assert.equal(view.button.disabled,true);
    await view.tasks.shift()();
    assert.equal(view.button.disabled,false);
    assert.match(view.button.textContent, /Mô phỏng/);
    assert.equal(submitted,false);
  } finally { view.dom.window.close(); }
});
test('confirmation from the phone hides QR and button on the other device and stops polling', async () => {
  const view = page(async () => ({ok:true, json:async () => ({status:'PAID', message:'Đã mô phỏng thành công'})}));
  try {
    await settle();
    assert.equal(view.dom.window.document.getElementById('demo-confirm').hidden,true);
    assert.equal(view.dom.window.document.getElementById('demo-qr').hidden,true);
    assert.match(view.dom.window.document.getElementById('demo-message').textContent,/thành công/);
    assert.equal(view.tasks.length,0);
  } finally { view.dom.window.close(); }
});
test('closed hold and lost connection never enable confirmation', async () => {
  const closed = page(async () => ({ok:true, json:async () => ({status:'CLOSED', message:'Hết hạn'})}));
  const offline = page(async () => { throw new Error('offline'); });
  try {
    await settle();
    assert.equal(closed.button.disabled,true);
    assert.equal(closed.tasks.length,0);
    while (offline.tasks.length) await offline.tasks.shift()();
    assert.equal(offline.button.disabled,true);
    assert.match(offline.dom.window.document.getElementById('demo-message').textContent,/tải lại/);
  } finally { closed.dom.window.close(); offline.dom.window.close(); }
});
