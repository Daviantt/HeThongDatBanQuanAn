"use strict";
document.querySelectorAll(".site-header nav a").forEach((link) => {
  if (location.pathname === link.getAttribute("href"))
    link.classList.add("current");
});
document.querySelectorAll("form[data-confirm]").forEach((form) =>
  form.addEventListener("submit", (event) => {
    if (!window.confirm(form.dataset.confirm)) event.preventDefault();
  }),
);
document.querySelectorAll("[data-filter]").forEach((button) =>
  button.addEventListener("click", () => {
    document
      .querySelectorAll("[data-filter]")
      .forEach((b) => b.classList.toggle("active", b === button));
    document.querySelectorAll("[data-category]").forEach((card) => {
      card.hidden =
        button.dataset.filter !== "all" &&
        card.dataset.category !== button.dataset.filter;
    });
  }),
);
document
  .getElementById("booking-search")
  ?.addEventListener("input", (event) => {
    const query = event.target.value.toLocaleLowerCase("vi").trim();
    document.querySelectorAll("[data-search-row]").forEach((row) => {
      row.hidden = !row.textContent.toLocaleLowerCase("vi").includes(query);
    });
  });
document.querySelectorAll("[data-countdown]").forEach((container) => {
  const deadline = new Date(container.dataset.countdown + "+07:00").getTime();
  let interval;
  const update = () => {
    const seconds = Math.max(0, Math.floor((deadline - Date.now()) / 1000));
    container.querySelector("strong").textContent =
      seconds > 0
        ? `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`
        : "Đã hết hạn";
    if (seconds === 0) {
      container
        .closest(".payment-panel")
        .querySelectorAll("form button")
        .forEach((button) => {
          button.disabled = true;
        });
      if (interval) clearInterval(interval);
    }
  };
  update();
  interval = setInterval(update, 1000);
});
