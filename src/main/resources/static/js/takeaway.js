"use strict";
(() => {
  const root = document.getElementById("takeaway-checkout"); if (!root) return;
  const storageKey = "giavien-takeaway-cart";
  const read = () => { try { return JSON.parse(localStorage.getItem(storageKey)) || []; } catch (_) { return []; } };
  const write = (cart) => localStorage.setItem(storageKey, JSON.stringify(cart));
  const money = (amount) => `${new Intl.NumberFormat("vi-VN").format(amount)} ₫`;
  const lines = document.getElementById("checkout-lines"), empty = document.getElementById("checkout-empty");
  const cart = read();
  const render = () => {
    const total = cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
    lines.innerHTML = cart.map((item, index) => `<article class="checkout-line"><img src="${item.image}" alt=""/><div><h3>${item.name}</h3><span>${money(item.price)}</span><div class="cart-quantity"><button type="button" data-change="-1" data-index="${index}" aria-label="Giảm số lượng">−</button><b>${item.quantity}</b><button type="button" data-change="1" data-index="${index}" aria-label="Tăng số lượng">+</button></div></div><strong>${money(item.price * item.quantity)}</strong></article>`).join("");
    lines.hidden = cart.length === 0; empty.hidden = cart.length !== 0;
    document.getElementById("takeaway-total").textContent = money(total);
    document.getElementById("takeaway-submit").disabled = cart.length === 0;
  };
  lines.addEventListener("click", (event) => { const btn = event.target.closest("button[data-index]"); if (!btn) return; const i = Number(btn.dataset.index); cart[i].quantity += Number(btn.dataset.change); if (cart[i].quantity < 1) cart.splice(i, 1); if (cart[i]?.quantity > 20) cart[i].quantity = 20; write(cart); render(); });
  document.getElementById("takeaway-name").value = root.dataset.name || ""; document.getElementById("takeaway-phone").value = root.dataset.phone || "";
  const time = document.getElementById("takeaway-time"), localValue = (date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  const earliest = new Date(Date.now() + 15 * 60000); time.min = localValue(earliest); time.value = localValue(new Date(Date.now() + 30 * 60000));
  document.getElementById("takeaway-form").addEventListener("submit", async (event) => {
    event.preventDefault(); const error = document.getElementById("takeaway-error"), submit = document.getElementById("takeaway-submit"); error.hidden = true; submit.disabled = true;
    const csrf = document.querySelector('meta[name="_csrf"]'), csrfHeader = document.querySelector('meta[name="_csrf_header"]'), csrfParameter = document.querySelector('meta[name="_csrf_parameter"]');
    try { const response = await fetch("/api/takeaway-orders", { method: "POST", headers: { "Content-Type": "application/json", [csrfHeader.content]: csrf.content }, body: JSON.stringify({ customerName: document.getElementById("takeaway-name").value, phone: document.getElementById("takeaway-phone").value, pickupAt: time.value, paymentMethod: document.querySelector('input[name="method"]:checked').value, items: Object.fromEntries(cart.map((item) => [item.id, item.quantity])) }) }); const body = await response.json(); if (!response.ok) throw new Error(body.error || "Không thể tạo đơn."); write([]); if (document.querySelector('input[name="method"]:checked').value === "TRANSFER") { const form = document.createElement("form"); form.method = "post"; form.action = body.paymentUrl; const token = document.createElement("input"); token.type = "hidden"; token.name = csrfParameter.content; token.value = csrf.content; form.append(token); document.body.append(form); form.submit(); } else window.location.href = body.url; } catch (failure) { error.textContent = failure.message; error.hidden = false; submit.disabled = false; }
  });
  render();
})();
