const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('../../../.tools/ui-test/node_modules/jsdom');
const root = path.resolve(__dirname, '../../..');
const settle = () => new Promise(resolve => setImmediate(resolve));
function setup() {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'src/main/resources/templates/book.html'), 'utf8'), {
    runScripts:'outside-only', url:'http://localhost:8080/book',
  });
  const {window} = dom;
  const doc = window.document;
  doc.head.innerHTML = '<meta name="_csrf_header" content="X-CSRF-TOKEN"><meta name="_csrf" content="test">';
  const template = doc.querySelector('.preorder-item').cloneNode(true);
  doc.getElementById('preorder-grid').replaceChildren();
  [['1','Bò lúc lắc','Món chính',189000],['2','Bánh flan caramel','Tráng miệng',39000]].forEach(([id,name,category,price]) => {
    const card=template.cloneNode(true);
    card.dataset.menuCategory=category;
    card.dataset.menuSearch=name;
    card.querySelector('h3').textContent=name;
    const input=card.querySelector('input');
    Object.assign(input.dataset,{dishId:id,dishName:name,price:String(price)});
    doc.getElementById('preorder-grid').append(card);
  });
  const $=id=>doc.getElementById(id);
  let sent;
  window.fetch = async (url,request) => {
    if(url==='/api/bookings') {
      sent=JSON.parse(request.body);
      return {ok:false,status:409,json:async()=>({error:'Test giữ nguyên trang'})};
    }
    return {ok:true,json:async()=>({tables:[{id:1,code:'B01',floor:1,mapX:0,mapY:0,zone:'Vườn'}],
      options:[{tableIds:[1],label:'B01',capacity:4,deposit:100000}],unavailable:[]})};
  };
  window.eval(fs.readFileSync(path.join(root,'src/main/resources/static/js/booking.js'),'utf8'));
  return {dom,window,doc,$,sent:()=>sent};
}
test('accent-insensitive search and category filters preserve selected quantities and submission',async()=>{
  const v=setup();
  const {doc,$,window}=v;
  try {
    const beef=doc.querySelectorAll('.preorder-item')[0];
    const flan=doc.querySelectorAll('.preorder-item')[1];
    beef.querySelector('[data-quantity="1"]').click();
    beef.querySelector('[data-quantity="1"]').click();
    assert.equal($('summary-food').textContent,'378.000 ₫');
    assert.equal($('selected-dish-count').textContent,'1');
    assert.match($('selected-food-list').textContent,/2 × Bò lúc lắc/);
    $('dish-search').value='bo luc lac';
    $('dish-search').dispatchEvent(new window.Event('input'));
    assert.equal(beef.hidden,false);
    assert.equal(flan.hidden,true);
    doc.querySelector('[data-menu-filter="Tráng miệng"]').click();
    assert.equal($('dish-empty').hidden,false);
    $('dish-search').value='';
    $('dish-search').dispatchEvent(new window.Event('input'));
    assert.equal(beef.hidden,true);
    assert.equal(flan.hidden,false);
    assert.equal(beef.querySelector('input').value,'2');
    assert.equal($('summary-food').textContent,'378.000 ₫');
    $('guests').innerHTML='<option value="2">2</option>';
    $('visit-date').value='2026-10-01';
    const search=new window.Event('submit',{cancelable:true});
    Object.defineProperty(search,'submitter',{value:doc.querySelector('#availability-form button')});
    $('availability-form').dispatchEvent(search);
    await settle();
    doc.querySelector('.table-seat').click();
    assert.equal($('summary-table-deposit').textContent,'100.000 ₫');
    assert.equal($('summary-food-deposit').textContent,'75.600 ₫');
    assert.equal($('summary-deposit').textContent,'175.600 ₫');
    $('accept-policy').checked=true;
    $('accept-policy').dispatchEvent(new window.Event('change'));
    $('create-booking').click();
    await settle();
    assert.deepEqual(v.sent().items,{'1':2});
    doc.querySelector('[data-menu-filter="selected"]').click();
    assert.equal(beef.hidden,false);
    assert.equal(flan.hidden,true);
    doc.querySelector('.remove-dish').click();
    assert.equal(beef.querySelector('input').value,'0');
    assert.equal($('summary-food-deposit').textContent,'0 ₫');
    assert.equal($('summary-deposit').textContent,'100.000 ₫');
    assert.equal($('selected-food-summary').hidden,true);
    assert.equal($('dish-empty').hidden,false);
    $('reset-menu-filter').click();
    assert.equal(beef.hidden,false);
    assert.equal(flan.hidden,false);
  } finally {window.close();}
});
test('quantity input clamps invalid values and quantity buttons stop at zero and twenty',()=>{
  const {window,doc,$}=setup();
  try {
    const card=doc.querySelector('.preorder-item');
    const input=card.querySelector('input');
    assert.equal(card.querySelector('[data-quantity="-1"]').disabled,true);
    input.value='99'; input.dispatchEvent(new window.Event('input'));
    assert.equal(input.value,'20');
    assert.equal(card.querySelector('[data-quantity="1"]').disabled,true);
    input.value='-1'; input.dispatchEvent(new window.Event('input'));
    assert.equal(input.value,'0');
    input.value='2.8'; input.dispatchEvent(new window.Event('input'));
    assert.equal(input.value,'2');
    assert.equal($('summary-food').textContent,'378.000 ₫');
  } finally {window.close();}
});

test('food deposit is twenty percent of the combined total rounded up to a whole dong',async()=>{
  const {window,doc,$}=setup();
  try {
    const inputs=doc.querySelectorAll('.dish-quantity');
    inputs[0].dataset.price='10001';
    inputs[1].dataset.price='10002';
    inputs.forEach(input=>{input.value='1';input.dispatchEvent(new window.Event('input'));});
    assert.equal($('summary-food').textContent.replace(/[^0-9]/g,''),'20003');
    assert.equal($('summary-food-deposit').textContent.replace(/[^0-9]/g,''),'4001');
    $('guests').innerHTML='<option value="2">2</option>';
    $('visit-date').value='2026-10-04';
    const search=new window.Event('submit',{cancelable:true});
    Object.defineProperty(search,'submitter',{value:doc.querySelector('#availability-form button')});
    $('availability-form').dispatchEvent(search);
    await settle();
    doc.querySelector('.table-seat').click();
    assert.equal($('summary-deposit').textContent.replace(/[^0-9]/g,''),'104001');
  } finally {window.close();}
});
