"use strict";
(() => {
  const storageKey = "giavien-takeaway-cart";
  const menu = document.getElementById("takeaway-menu");
  const dialog = document.getElementById("order-choice");
  if (!menu || !dialog) return;
  const money = (amount) => `${new Intl.NumberFormat("vi-VN").format(amount)} ₫`;
  const read = () => { try { return JSON.parse(localStorage.getItem(storageKey)) || []; } catch (_) { return []; } };
  const write = (cart) => localStorage.setItem(storageKey, JSON.stringify(cart));
  const renderCart = () => {
    const cart = read(); const count = cart.reduce((sum, item) => sum + item.quantity, 0);
    const total = cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
    document.getElementById("takeaway-cart").hidden = count === 0;
    document.getElementById("takeaway-cart-count").textContent = `${count} món`;
    document.getElementById("takeaway-cart-total").textContent = money(total);
  };
  let selected;
  menu.querySelectorAll(".menu-dish-button").forEach((button) => button.addEventListener("click", () => {
    selected = { id: Number(button.dataset.id), name: button.dataset.name, price: Number(button.dataset.price), image: button.dataset.image };
    document.getElementById("choice-dish-name").textContent = selected.name;
    document.getElementById("choice-dish-price").textContent = money(selected.price);
    dialog.showModal();
  }));
  dialog.querySelector(".modal-close").addEventListener("click", () => dialog.close());
  document.getElementById("add-takeaway").addEventListener("click", () => {
    if (menu.dataset.signedIn !== "true") { window.location.href = "/login"; return; }
    const cart = read(); const existing = cart.find((item) => item.id === selected.id);
    if (existing) existing.quantity = Math.min(20, existing.quantity + 1);
    else cart.push({ ...selected, quantity: 1 });
    write(cart); renderCart(); dialog.close();
  });
  renderCart();
})();
