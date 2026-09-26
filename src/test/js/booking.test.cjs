const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../.tools/ui-test/node_modules/jsdom');

test('floor switching filters the map, clears selection and submits the visible floor', async () => {
  const root = path.resolve(__dirname, '../../..');
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'src/main/resources/templates/book.html'), 'utf8'), {
    runScripts: 'outside-only', url: 'http://localhost:8080/book',
  });
  const { window } = dom;
  const doc = window.document;
  const $ = (id) => doc.getElementById(id);
  doc.head.innerHTML = '<meta name="_csrf_header" content="X-CSRF-TOKEN"><meta name="_csrf" content="test">';
  $('guests').innerHTML = '<option value="8">8 người</option>';
  $('visit-date').value = '2026-10-01';
  const coordinates = [[0,0],[1,0],[2,0],[0,1],[2,1],[0,2],[2,2],[0,3],[1,3],[2,3]];
  const groundIds = [1,2,3,4,6,7,9,10,11,12];
  const tables = [1,2].flatMap(floor => coordinates.map(([mapX,mapY], index) => ({
    id: floor === 1 ? groundIds[index] : 13 + index,
    code: 'B' + String(index + (floor === 1 ? 1 : 11)).padStart(2, '0'),
    floor, mapX, mapY, zone: 'Tầng ' + floor,
  }))).filter(table => table.code !== 'B09');
  let options = [
    { tableIds: [1,4], label: 'B01 + B04', capacity: 8, deposit: 200000 },
    { tableIds: [13,16], label: 'B11 + B14', capacity: 8, deposit: 200000 },
  ];
  let sent;
  window.fetch = async (url, request) => {
    if (url === '/api/bookings') {
      sent = JSON.parse(request.body);
      return { ok: false, status: 409, json: async () => ({ error: 'Bàn vừa được đặt.' }) };
    }
    return { ok: true, json: async () => ({ tables, options, unavailable: [] }) };
  };
  window.eval(fs.readFileSync(path.join(root, 'src/main/resources/static/js/booking.js'), 'utf8'));
  const settle = () => new Promise(resolve => setImmediate(resolve));
  const search = async () => {
    const event = new window.Event('submit', { cancelable: true });
    Object.defineProperty(event, 'submitter', { value: doc.querySelector('#availability-form button') });
    $('availability-form').dispatchEvent(event);
    await settle();
  };
  const floor = n => doc.querySelector(`[data-floor="${n}"]`).click();
  try {
    await search();
    assert.equal(doc.querySelectorAll('.table-seat').length, 9);
    assert.equal(doc.querySelector('.table-seat[data-id="11"]'), null);
    assert.equal($('floor-capacity').textContent, '9 bàn · 36 chỗ');
    assert.equal($('entrance-aisle').hidden, false);
    assert.equal(doc.querySelector('.table-seat strong').textContent, 'B01');
    doc.querySelector('[data-option="0"]').click();
    assert.equal($('selected-label').textContent, 'B01 + B04');
    $('accept-policy').checked = true;
    $('accept-policy').dispatchEvent(new window.Event('change'));
    assert.equal($('create-booking').disabled, false);
    floor(2);
    assert.equal($('create-booking').disabled, true);
    assert.equal($('selected-label').textContent, 'Chưa chọn bàn');
    assert.equal(doc.querySelectorAll('.table-seat').length, 10);
    assert.equal(doc.querySelector('.table-seat strong').textContent, 'B11');
    assert.equal($('floor-capacity').textContent, '10 bàn · 40 chỗ');
    assert.equal(doc.querySelector('.table-seat[data-id="21"] strong').textContent, 'B19');
    assert.equal($('entrance-aisle').hidden, true);
    assert.equal($('courtyard-title').textContent, 'Khoảng thông tầng');
    doc.querySelector('.table-seat[data-id="13"]').click();
    assert.equal($('selected-label').textContent, 'B11 + B14');
    assert.match($('selected-capacity').textContent, /Tầng 2/);
    $('create-booking').click();
    await settle();
    assert.deepEqual(sent.tableIds, [13,16]);
    assert.match($('booking-error').textContent, /Bàn vừa/);
    options = options.slice(0,1);
    await search();
    assert.equal($('combination-options').children.length, 0);
    assert.match($('map-caption').textContent, /Tầng 2 chưa có bàn/);
    assert.equal($('create-booking').disabled, true);
    floor(1);
    assert.equal($('combination-options').children.length, 1);
    $('start-time').dispatchEvent(new window.Event('change'));
    floor(2);
    assert.equal($('floor-plan').hidden, true);
    assert.equal($('combination-options').children.length, 0);
  } finally {
    window.close();
  }
});
