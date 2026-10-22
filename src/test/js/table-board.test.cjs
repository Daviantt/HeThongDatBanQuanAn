const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../.tools/ui-test/node_modules/jsdom');
const settle = () => new Promise(resolve => setImmediate(resolve));

function setup() {
  const dom = new JSDOM(`<!doctype html><p id="table-board-sync"></p>
    <button id="refresh-table-board">Cập nhật ngay</button>
    <div id="table-board-content" data-refresh-url="/staff/tables/fragment?date=2026-09-21">
      <article data-table-id="1"><button data-open-table="table-dialog-1">B01</button>
        <dialog id="table-dialog-1"><button data-close-table>Đóng</button>
          <form data-board-confirm="Khách đã dùng xong?"><button>Đã sử dụng xong</button></form>
        </dialog><details open><summary>Lịch tiếp theo</summary></details>
      </article>
    </div>`, { runScripts: 'outside-only', url: 'http://localhost:8080/staff/tables', pretendToBeVisual: true });
  const { window } = dom;
  let poll, requests = 0, failure = false;
  window.setInterval = (callback, milliseconds) => { assert.equal(milliseconds, 5000); poll = callback; return 1; };
  window.HTMLDialogElement.prototype.showModal = function() { this.open = true; };
  window.HTMLDialogElement.prototype.close = function() { this.open = false; };
  window.fetch = async (url, options) => {
    requests++;
    assert.equal(url, '/staff/tables/fragment?date=2026-09-21');
    assert.equal(options.cache, 'no-store');
    if (failure) throw new Error('offline');
    return { ok: true, text: async () => `<div id="table-board-content" data-refresh-url="${url}">
      <article data-table-id="1" data-state="HELD"><p>Khách vừa đặt bàn</p>
        <button data-open-table="table-dialog-1">B01</button>
        <dialog id="table-dialog-1"><button data-close-table>Đóng</button></dialog>
        <details><summary>Lịch tiếp theo</summary></details>
      </article></div>` };
  };
  window.eval(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/table-board.js'), 'utf8'));
  return { dom, window, doc: window.document, poll: () => poll(),
    requests: () => requests, fail: () => { failure = true; } };
}

test('polling shows a new customer reservation and preserves expanded schedules', async () => {
  const v = setup();
  try {
    await v.poll();
    assert.equal(v.doc.querySelector('[data-table-id="1"]').dataset.state, 'HELD');
    assert.match(v.doc.getElementById('table-board-content').textContent, /Khách vừa đặt bàn/);
    assert.equal(v.doc.querySelector('details').open, true);
    assert.match(v.doc.getElementById('table-board-sync').textContent, /Đã cập nhật/);
    v.fail();
    await v.poll();
    assert.equal(v.doc.querySelector('[data-table-id="1"]').dataset.state, 'HELD');
    assert.match(v.doc.getElementById('table-board-sync').textContent, /có thể đã cũ/);
  } finally { v.dom.window.close(); }
});

test('table icon opens actions; live refresh cannot replace a dialog while staff is using it', async () => {
  const v = setup();
  try {
    v.doc.querySelector('[data-open-table]').click();
    assert.equal(v.doc.querySelector('dialog').open, true);
    await v.poll();
    assert.equal(v.requests(), 0);
    v.doc.querySelector('[data-close-table]').click();
    await settle();
    assert.equal(v.requests(), 1);
    // Delegated handlers also work on newly fetched icons.
    v.doc.querySelector('[data-open-table]').click();
    assert.equal(v.doc.querySelector('dialog').open, true);
  } finally { v.dom.window.close(); }
});

test('cancelling the finish confirmation prevents submission', () => {
  const v = setup();
  try {
    v.window.confirm = () => false;
    const event = new v.window.Event('submit', { bubbles: true, cancelable: true });
    v.doc.querySelector('form').dispatchEvent(event);
    assert.equal(event.defaultPrevented, true);
  } finally { v.dom.window.close(); }
});
