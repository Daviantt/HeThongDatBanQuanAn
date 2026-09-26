"use strict";
(() => {
  const panel = document.getElementById("payment-result");
  const url = panel?.dataset.statusUrl;
  if (!url) return;
  let tries = 0;
  async function refresh() {
    try {
      const response = await fetch(url, { headers: { Accept: "application/json" }, cache: "no-store" });
      if (response.status === 401 || response.status === 403) {
        document.getElementById("payment-message").textContent = "Mở lượt đặt và đăng nhập để kiểm tra trạng thái cọc.";
        return;
      }
      if (!response.ok) throw new Error("Không kết nối được máy chủ");
      const result = await response.json();
      document.getElementById("payment-title").textContent = result.title;
      document.getElementById("payment-message").textContent = result.message;
      if (!result.waiting) return;
    } catch (_) { /* Keep the booking link available when the network is interrupted. */ }
    if (++tries < 30) window.setTimeout(refresh, 2000);
    else document.getElementById("payment-message").textContent = "Chưa nhận được xác nhận cuối cùng. Mở lượt đặt để kiểm tra lại; nếu đã thanh toán, vui lòng không thanh toán thêm.";
  }
  refresh();
})();
