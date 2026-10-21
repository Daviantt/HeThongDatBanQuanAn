"use strict";
(() => {
  const root = document.getElementById("takeaway-checkout");
  if (!root) return;
  const storageKey = "giavien-takeaway-cart";
  const $ = (id) => document.getElementById(id);
  const read = () => {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey));
      if (!Array.isArray(saved)) return [];
      const seen = new Set();
      return saved.filter((item) => {
        if (!item || !Number.isSafeInteger(item.id) || item.id <= 0 || seen.has(item.id)
            || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 20
            || typeof item.name !== "string" || !Number.isSafeInteger(item.price) || item.price < 0) return false;
        seen.add(item.id);
        return true;
      }).slice(0, 30);
    } catch (_) { return []; }
  };
  const write = () => {
    try { localStorage.setItem(storageKey, JSON.stringify(cart)); } catch (_) { /* The current page still holds the cart. */ }
  };
  const money = (amount) => `${new Intl.NumberFormat("vi-VN").format(amount)} ₫`;
  const safeImage = (path) => typeof path === "string" && /^\/images\/(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9_-]+\.(?:jpg|jpeg|png|webp|svg)$/.test(path)
    ? path : "/images/dish.svg";
  const element = (tag, className, text) => {
    const node = document.createElement(tag);
    node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  };
  let cart = read();
  let quoted = false;
  let submitting = false;
  let quoteVersion = 0;
  const message = (text = "") => { $("takeaway-error").textContent = text; $("takeaway-error").hidden = !text; };
  const render = () => {
    const lines = $("checkout-lines");
    lines.replaceChildren();
    cart.forEach((item, index) => {
      const article = element("article", "checkout-line");
      const image = element("img", "");
      image.src = safeImage(item.image);
      image.alt = "";
      const info = element("div", "");
      const controls = element("div", "cart-quantity");
      const button = (change, label, text) => {
        const node = element("button", "", text);
        node.type = "button";
        node.dataset.index = index;
        node.dataset.change = change;
        node.setAttribute("aria-label", label);
        node.disabled = submitting || (change === 1 && item.quantity >= 20);
        return node;
      };
      controls.append(button(-1, "Giảm số lượng", "−"), element("b", "", item.quantity), button(1, "Tăng số lượng", "+"));
      info.append(element("h3", "", item.name), element("span", "", money(item.price)), controls);
      article.append(image, info, element("strong", "", money(item.price * item.quantity)));
      lines.append(article);
    });
    lines.hidden = cart.length === 0;
    $("checkout-empty").hidden = cart.length !== 0;
    $("takeaway-total").textContent = money(cart.reduce((sum, item) => sum + item.price * item.quantity, 0));
    $("takeaway-submit").disabled = submitting || !quoted || cart.length === 0;
    $("takeaway-submit").textContent = submitting ? "Đang tạo đơn…" : "Xác nhận đặt món →";
  };
  const items = () => Object.fromEntries(cart.map((item) => [item.id, item.quantity]));
  const post = async (url, body) => {
    const csrf = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    const response = await fetch(url, {
      method: "POST", headers: { "Content-Type": "application/json", [header.content]: csrf.content }, body: JSON.stringify(body),
    });
    const data = await response.json();
    return { response, data };
  };
  const applyQuote = (quote) => {
    const saved = new Map(cart.map((item) => [item.id, item]));
    const changed = quote.lines.some((line) => saved.get(line.menuItemId)?.price !== line.unitPrice);
    cart = quote.lines.map((line) => ({
      id: line.menuItemId, name: line.itemName, price: line.unitPrice,
      quantity: line.quantity, image: safeImage(saved.get(line.menuItemId)?.image),
    }));
    quoted = true;
    write();
    return changed;
  };
  const refreshQuote = async () => {
    const version = ++quoteVersion;
    quoted = false;
    render();
    if (!cart.length) { message(); return; }
    message("Đang kiểm tra giá và tình trạng món…");
    try {
      const { response, data } = await post("/api/takeaway-orders/quote", { items: items() });
      if (version !== quoteVersion) return;
      if (!response.ok) throw new Error(data.error || "Không thể cập nhật giá. Vui lòng tải lại trang.");
      message(applyQuote(data) ? "Giá món đã được cập nhật. Vui lòng kiểm tra tổng tiền trước khi xác nhận." : "");
    } catch (failure) {
      if (version === quoteVersion) message(failure.message);
    } finally { if (version === quoteVersion) render(); }
  };
  $("checkout-lines").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-index]");
    if (!button || submitting) return;
    const index = Number(button.dataset.index);
    cart[index].quantity += Number(button.dataset.change);
    if (cart[index].quantity < 1) cart.splice(index, 1);
    write();
    refreshQuote();
  });
  $("takeaway-name").value = root.dataset.name || "";
  $("takeaway-phone").value = root.dataset.phone || "";
  const localValue = (date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  $("takeaway-time").min = localValue(new Date(Date.now() + 16 * 60000));
  $("takeaway-time").value = localValue(new Date(Date.now() + 30 * 60000));
  $("takeaway-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitting || !quoted || !cart.length) return;
    submitting = true;
    message();
    render();
    const method = document.querySelector('input[name="method"]:checked').value;
    try {
      const { response, data } = await post("/api/takeaway-orders", {
        customerName: $("takeaway-name").value, phone: $("takeaway-phone").value,
        pickupAt: $("takeaway-time").value, paymentMethod: method, items: items(),
        expectedUnitPrices: Object.fromEntries(cart.map((item) => [item.id, item.price])),
      });
      if (response.status === 409 && data.quote) applyQuote(data.quote);
      if (!response.ok) throw new Error(data.error || "Không thể tạo đơn.");
      cart = [];
      write();
      if (method === "TRANSFER") {
        const form = document.createElement("form");
        form.method = "post";
        form.action = data.paymentUrl;
        const token = document.createElement("input");
        token.type = "hidden";
        token.name = document.querySelector('meta[name="_csrf_parameter"]').content;
        token.value = document.querySelector('meta[name="_csrf"]').content;
        form.append(token);
        document.body.append(form);
        form.submit();
      } else window.location.href = data.url;
    } catch (failure) {
      message(failure.message);
      submitting = false;
      render();
    }
  });
  refreshQuote();
})();
