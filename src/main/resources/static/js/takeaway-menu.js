"use strict";
(() => {
  const storageKey = "giavien-takeaway-cart";
  const menu = document.getElementById("takeaway-menu");
  const dialog = document.getElementById("order-choice");
  if (!menu || !dialog) return;
  const basket = document.getElementById("takeaway-cart");
  const options = document.getElementById("order-choice-options");
  const form = document.getElementById("takeaway-quantity-form");
  const quantity = document.getElementById("takeaway-quantity");
  const minus = document.getElementById("takeaway-quantity-minus");
  const plus = document.getElementById("takeaway-quantity-plus");
  const confirm = document.getElementById("confirm-takeaway");
  const error = document.getElementById("takeaway-quantity-error");
  const heading = document.getElementById("choice-heading");
  const choiceHeading = heading.textContent;
  const money = (amount) => `${new Intl.NumberFormat("vi-VN").format(amount)} ₫`;
  let selected = null;

  const read = () => {
    try {
      const cart = JSON.parse(localStorage.getItem(storageKey));
      if (!Array.isArray(cart)) return [];
      return cart.filter((item) => item && Number.isInteger(item.id) && item.id > 0
        && typeof item.name === "string" && Number.isFinite(item.price) && item.price >= 0
        && Number.isInteger(item.quantity) && item.quantity >= 1 && item.quantity <= 20);
    } catch (_) { return []; }
  };

  const renderCart = () => {
    const cart = read();
    const count = cart.reduce((sum, item) => sum + item.quantity, 0);
    basket.hidden = count === 0;
    document.body.classList.toggle("has-takeaway-cart", count > 0);
    document.getElementById("takeaway-cart-count").textContent = `${count} món`;
    document.getElementById("takeaway-cart-total").textContent = money(cart.reduce((sum, item) => sum + item.price * item.quantity, 0));
    const list = document.getElementById("takeaway-cart-items");
    list.replaceChildren();
    cart.forEach((item) => {
      const line = document.createElement("li");
      const name = document.createElement("span");
      const total = document.createElement("strong");
      name.textContent = `${item.quantity} × ${item.name}`;
      total.textContent = money(item.price * item.quantity);
      line.append(name, total);
      list.append(line);
    });
  };

  const showError = (message = "") => {
    error.textContent = message;
    error.hidden = !message;
  };

  const updateQuantity = () => {
    if (!selected) return;
    const existing = read().find((item) => item.id === selected.id);
    const remaining = Math.max(0, 20 - (existing?.quantity || 0));
    const value = quantity.valueAsNumber;
    quantity.max = String(remaining);
    quantity.disabled = remaining === 0;
    confirm.disabled = remaining === 0;
    minus.disabled = remaining === 0 || !Number.isFinite(value) || value <= 1;
    plus.disabled = remaining === 0 || !Number.isFinite(value) || value >= remaining;
    document.getElementById("takeaway-quantity-help").textContent = remaining === 0
      ? "Giỏ đã có đủ 20 phần món này. Bạn có thể điều chỉnh số lượng ở trang chốt đơn."
      : existing ? `Đã có ${existing.quantity} phần trong giỏ. Bạn có thể thêm tối đa ${remaining} phần.`
        : "Tối đa 20 phần cho mỗi món trong giỏ.";
    const valid = Number.isInteger(value) && value >= 1 && value <= remaining;
    quantity.setAttribute("aria-invalid", String(!valid && remaining > 0));
    document.getElementById("takeaway-quantity-total").textContent = valid ? money(selected.price * value) : "—";
    return valid;
  };

  const showOptions = () => {
    options.hidden = false;
    form.hidden = true;
    heading.textContent = choiceHeading;
    showError();
  };

  menu.querySelectorAll(".menu-dish-button").forEach((button) => button.addEventListener("click", () => {
    selected = { id: Number(button.dataset.id), name: button.dataset.name, price: Number(button.dataset.price), image: button.dataset.image };
    document.getElementById("choice-dish-name").textContent = selected.name;
    document.getElementById("choice-dish-price").textContent = `${money(selected.price)} / phần`;
    quantity.value = "1";
    showOptions();
    dialog.showModal();
  }));
  dialog.querySelector(".modal-close").addEventListener("click", () => dialog.close());
  dialog.addEventListener("close", () => { if (!dialog.open) selected = null; });
  document.getElementById("add-takeaway").addEventListener("click", () => {
    if (menu.dataset.signedIn !== "true") { window.location.href = "/login"; return; }
    options.hidden = true;
    form.hidden = false;
    heading.textContent = "CHỌN SỐ LƯỢNG · ĐẶT ĐẾN LẤY";
    quantity.value = "1";
    updateQuantity();
    if (quantity.disabled) document.getElementById("takeaway-quantity-back").focus();
    else { quantity.focus(); quantity.select(); }
  });
  document.getElementById("takeaway-quantity-back").addEventListener("click", () => {
    showOptions();
    document.getElementById("add-takeaway").focus();
  });
  quantity.addEventListener("input", () => { showError(); updateQuantity(); });
  minus.addEventListener("click", () => { quantity.stepDown(); showError(); updateQuantity(); });
  plus.addEventListener("click", () => { quantity.stepUp(); showError(); updateQuantity(); });

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (!selected || !dialog.open || form.hidden) return;
    // Re-read the cart in case another tab changed it while this popup was open.
    if (!updateQuantity()) {
      showError(quantity.disabled ? "Món này đã đủ 20 phần trong giỏ." : `Vui lòng nhập số nguyên từ 1 đến ${quantity.max}.`);
      quantity.focus();
      return;
    }
    const amount = quantity.valueAsNumber;
    const cart = read();
    const existing = cart.find((item) => item.id === selected.id);
    if (existing) existing.quantity += amount;
    else cart.push({ ...selected, quantity: amount });
    try { localStorage.setItem(storageKey, JSON.stringify(cart)); }
    catch (_) { showError("Không lưu được giỏ hàng. Vui lòng cho phép trình duyệt lưu dữ liệu rồi thử lại."); return; }
    renderCart();
    document.getElementById("takeaway-cart-message").textContent = `Đã thêm ${amount} phần ${selected.name} vào giỏ.`;
    basket.classList.remove("cart-updated");
    void basket.offsetWidth;
    basket.classList.add("cart-updated");
    selected = null;
    dialog.close();
  });
  window.addEventListener("storage", (event) => {
    if (event.key !== storageKey && event.key !== null) return;
    renderCart();
    document.getElementById("takeaway-cart-message").textContent = "";
    if (!form.hidden) updateQuantity();
  });
  renderCart();
})();
