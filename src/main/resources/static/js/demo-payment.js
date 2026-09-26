"use strict";
(() => {
  const panel = document.getElementById("demo-checkout");
  if (!panel || ["PAID", "CLOSED"].includes(panel.dataset.state)) return;
  const button = document.getElementById("demo-pay-button");
  const message = document.getElementById("demo-message");
  let failures = 0;
  let submitting = false;
  document.getElementById("demo-confirm").addEventListener("submit", () => {
    submitting = true;
    button.disabled = true;
    button.textContent = "Đang ghi nhận mô phỏng…";
  });
  async function refresh() {
    try {
      const response = await fetch(panel.dataset.statusUrl, { cache: "no-store", headers: { Accept: "application/json" } });
      if (submitting) return;
      if (response.status === 404) {
        button.disabled = true;
        message.textContent = "Phiên QR không còn khả dụng. Mở lượt đặt để kiểm tra.";
        return;
      }
      if (!response.ok) throw new Error("Không kết nối được máy chủ");
      const state = await response.json();
      if (submitting) return;
      failures = 0;
      message.textContent = state.message;
      button.disabled = state.status !== "READY";
      button.textContent = state.status === "READY" ? "Mô phỏng thanh toán thành công"
        : `Mở nút xác nhận sau ${state.remainingSeconds} giây`;
      if (["PAID", "CLOSED"].includes(state.status)) {
        document.getElementById("demo-confirm").hidden = true;
        document.getElementById("demo-qr").hidden = true;
        return;
      }
    } catch (_) {
      button.disabled = true;
      message.textContent = "Mất kết nối. Đang kiểm tra lại trạng thái; chưa xác nhận thanh toán.";
      if (++failures >= 10) {
        message.textContent = "Chưa kết nối được máy chủ. Vui lòng tải lại trang hoặc mở lượt đặt để kiểm tra.";
        return;
      }
    }
    if (!submitting) window.setTimeout(refresh, 1000);
  }
  refresh();
})();
