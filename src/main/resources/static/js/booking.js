"use strict";
(() => {
  const $ = (id) => document.getElementById(id);
  const money = (value) => new Intl.NumberFormat("vi-VN").format(value) + " ₫";
  const state = {
    search: null,
    options: [],
    selected: null,
    submitting: false,
    request: 0,
  };
  const error = (id, message) => {
    $(id).textContent = message;
    $(id).hidden = !message;
  };
  function updateSummary() {
    const option = state.selected;
    $("selected-label").textContent = option ? option.label : "Chưa chọn bàn";
    $("selected-capacity").textContent = option
      ? `${option.tableIds.length} bàn · Tối đa ${option.capacity} người`
      : "Một chỗ ngồi thật vừa ý đang đợi bạn.";
    $("summary-deposit").textContent = option ? money(option.deposit) : "—";
    let total = 0;
    document.querySelectorAll(".dish-quantity").forEach((input) => {
      total += (Number(input.value) || 0) * Number(input.dataset.price);
    });
    $("summary-food").textContent = money(total);
    $("summary-guests").textContent = state.search
      ? `${state.search.guests} người`
      : "—";
    if (state.search) {
      const [date, start] = state.search.startAt.split("T");
      const [y, m, d] = date.split("-");
      $("summary-time").textContent =
        `${d}/${m}/${y} · ${start}–${state.search.endAt.split("T")[1]}`;
    } else $("summary-time").textContent = "—";
    $("late-warning").hidden =
      !state.search ||
      new Date(state.search.startAt + "+07:00") - Date.now() >= 3 * 3600000;
    $("create-booking").disabled =
      !option || !$("accept-policy").checked || state.submitting;
    document.querySelectorAll(".table-seat").forEach((button) => {
      const selected =
        option?.tableIds.includes(Number(button.dataset.id)) || false;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    document
      .querySelectorAll("[data-option]")
      .forEach((button) =>
        button.classList.toggle(
          "active",
          state.options[Number(button.dataset.option)] === option,
        ),
      );
  }
  function choose(option) {
    state.selected = option;
    error("booking-error", "");
    updateSummary();
  }
  function showAvailability(data) {
    state.options = data.options;
    $("table-grid").replaceChildren();
    $("combination-options").replaceChildren();
    data.tables.forEach((table) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "table-seat";
      button.dataset.id = table.id;
      button.style.gridColumn = table.mapX + 1;
      button.style.gridRow = table.mapY + 1;
      const name = document.createElement("strong");
      name.textContent = table.code;
      const seats = document.createElement("small");
      seats.textContent = "4 chỗ";
      button.append(name, seats);
      button.disabled =
        data.unavailable.includes(table.id) ||
        !data.options.some((o) => o.tableIds.includes(table.id));
      button.setAttribute(
        "aria-label",
        `${table.code}, 4 chỗ, ${table.zone}${button.disabled ? ", không khả dụng" : ""}`,
      );
      button.addEventListener("click", () =>
        choose(data.options.find((o) => o.tableIds.includes(table.id))),
      );
      $("table-grid").append(button);
    });
    if (state.search.guests > 4) {
      data.options.forEach((option, i) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "chip";
        button.dataset.option = i;
        button.textContent = `${option.label} · ${option.capacity} chỗ`;
        button.addEventListener("click", () => choose(option));
        $("combination-options").append(button);
      });
    }
    $("map-placeholder").hidden = true;
    $("floor-plan").hidden = false;
    $("map-caption").textContent = data.options.length
      ? `${data.options.length} phương án còn trống. ${state.search.guests > 4 ? "Chọn tổ hợp bên dưới hoặc nhấn vào bàn trên sơ đồ." : "Nhấn vào một bàn để chọn."}`
      : "Chưa có bàn phù hợp trong khung giờ này. Bạn thử đổi giờ hoặc ngày nhé.";
    updateSummary();
  }
  $("availability-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    state.selected = null;
    state.search = null;
    updateSummary();
    const requestId = ++state.request;
    const search = {
      startAt: $("visit-date").value + "T" + $("start-time").value,
      endAt: $("visit-date").value + "T" + $("end-time").value,
      guests: Number($("guests").value),
    };
    const button = event.submitter;
    button.disabled = true;
    button.textContent = "Đang tìm bàn…";
    error("availability-error", "");
    $("floor-plan").hidden = true;
    $("combination-options").replaceChildren();
    try {
      const response = await fetch(
        "/api/availability?" + new URLSearchParams(search),
        { headers: { Accept: "application/json" } },
      );
      const data = await response.json();
      if (requestId !== state.request) return;
      if (!response.ok)
        throw new Error(
          data.error ||
            "Chưa thể tìm bàn. Vui lòng kiểm tra thông tin hoặc đăng nhập lại.",
        );
      state.search = search;
      showAvailability(data);
    } catch (err) {
      if (requestId === state.request)
        error(
          "availability-error",
          err.message || "Không kết nối được máy chủ.",
        );
    } finally {
      button.disabled = false;
      button.textContent = "Tìm bàn trống ↗";
    }
  });
  ["visit-date", "start-time", "end-time", "guests"].forEach((id) =>
    $(id).addEventListener("change", () => {
      ++state.request;
      state.selected = null;
      state.search = null;
      state.options = [];
      $("floor-plan").hidden = true;
      $("map-placeholder").hidden = false;
      $("combination-options").replaceChildren();
      $("map-caption").textContent =
        "Thông tin đã thay đổi. Nhấn “Tìm bàn trống” để cập nhật.";
      updateSummary();
    }),
  );
  document.querySelectorAll("[data-quantity]").forEach((button) =>
    button.addEventListener("click", () => {
      const input = button.parentElement.querySelector("input");
      input.value = Math.max(
        0,
        Math.min(
          20,
          (Number(input.value) || 0) + Number(button.dataset.quantity),
        ),
      );
      updateSummary();
    }),
  );
  document.querySelectorAll(".dish-quantity").forEach((input) =>
    input.addEventListener("input", () => {
      input.value = Math.max(
        0,
        Math.min(20, Math.floor(Number(input.value) || 0)),
      );
      updateSummary();
    }),
  );
  $("accept-policy").addEventListener("change", updateSummary);
  $("create-booking").addEventListener("click", async () => {
    if (
      !state.selected ||
      !state.search ||
      state.submitting ||
      !$("accept-policy").checked
    )
      return;
    state.submitting = true;
    updateSummary();
    error("booking-error", "");
    $("create-booking").textContent = "Đang giữ bàn…";
    const items = {};
    document.querySelectorAll(".dish-quantity").forEach((input) => {
      if (Number(input.value) > 0)
        items[input.dataset.dishId] = Number(input.value);
    });
    const headers = {
      "Content-Type": "application/json",
      Accept: "application/json",
    };
    headers[document.querySelector('meta[name="_csrf_header"]').content] =
      document.querySelector('meta[name="_csrf"]').content;
    try {
      const response = await fetch("/api/bookings", {
        method: "POST",
        headers,
        body: JSON.stringify({
          ...state.search,
          tableIds: state.selected.tableIds,
          items,
          notes: $("booking-notes").value,
        }),
      });
      if (response.status === 403)
        throw new Error(
          "Phiên làm việc đã hết hạn. Vui lòng tải lại trang và đăng nhập.",
        );
      const data = await response.json();
      if (!response.ok)
        throw new Error(
          data.error || "Chưa tạo được lượt đặt. Vui lòng thử lại.",
        );
      location.assign(data.url);
    } catch (err) {
      error(
        "booking-error",
        err.message ||
          "Không kết nối được máy chủ. Hãy kiểm tra Lịch hẹn của tôi trước khi thử lại.",
      );
      state.submitting = false;
      $("create-booking").textContent = "Giữ bàn & đặt cọc →";
      updateSummary();
    }
  });
})();
