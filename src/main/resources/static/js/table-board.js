"use strict";
(() => {
  const message = document.getElementById("table-board-sync");
  if (!message) return;
  let loading = false, submitting = false;
  document.addEventListener("click", event => {
    const opener = event.target.closest("[data-open-table]");
    if (opener) document.getElementById(opener.dataset.openTable)?.showModal();
    if (event.target.closest("[data-close-table]")) {
      event.target.closest("dialog")?.close();
      refresh();
    }
  });
  document.addEventListener("submit", event => {
    if (!event.target.closest("#table-board-content")) return;
    const prompt = event.target.dataset.boardConfirm;
    if (prompt && !window.confirm(prompt)) { event.preventDefault(); return; }
    submitting = true;
  });
  async function refresh() {
    const current = document.getElementById("table-board-content");
    if (loading || submitting || document.hidden || !current || current.querySelector("dialog[open]")) return;
    loading = true;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(current.dataset.refreshUrl, {
        cache: "no-store", headers: { Accept: "text/html" }, signal: controller.signal,
      });
      if (!response.ok || response.redirected) throw new Error("Phiên đăng nhập hoặc kết nối không còn hợp lệ.");
      const parsed = new DOMParser().parseFromString(await response.text(), "text/html");
      const replacement = parsed.getElementById("table-board-content");
      if (!replacement) throw new Error("Không nhận được trạng thái bàn.");
      // Do not replace an action the employee focused while the request was in flight.
      if (submitting || current.querySelector("dialog[open]")) return;
      const expanded = new Set(Array.from(current.querySelectorAll("[data-table-id]"))
        .filter(card => card.querySelector("details")?.open).map(card => card.dataset.tableId));
      replacement.querySelectorAll("[data-table-id]").forEach(card => {
        const detail = card.querySelector("details");
        if (detail && expanded.has(card.dataset.tableId)) detail.open = true;
      });
      current.replaceWith(replacement);
      message.textContent = "Đã cập nhật trạng thái bàn. Tự kiểm tra lại sau 5 giây.";
      message.classList.remove("sync-error");
    } catch (_) {
      message.textContent = "Chưa cập nhật được. Dữ liệu đang hiển thị có thể đã cũ; hệ thống sẽ thử lại. Nếu phiên hết hạn, hãy đăng nhập lại.";
      message.classList.add("sync-error");
    } finally {
      clearTimeout(timeout);
      loading = false;
    }
  }
  document.getElementById("refresh-table-board").addEventListener("click", refresh);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
  setInterval(refresh, 5000);
})();
