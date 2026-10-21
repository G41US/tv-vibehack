(function () {
  "use strict";
  const apps = [
    { name: "YouTube", glyph: "▶", color: "#ff4b55", scheme: null },
    { name: "Netflix", glyph: "N", color: "#e50914", scheme: null },
    { name: "Prime Video", glyph: "◢", color: "#48a8ff", scheme: null },
    { name: "Spotify", glyph: "♫", color: "#1ed760", scheme: null },
    { name: "Settings", glyph: "⚙", color: "#b4bfd0", scheme: null }
  ];
  const grid = document.getElementById("app-grid");
  const status = document.getElementById("platform-status");
  const clock = document.getElementById("clock");
  let selected = 0;
  const columns = () => window.matchMedia("(max-width:560px)").matches ? 2 :
    window.matchMedia("(max-width:900px)").matches ? 3 : 5;

  function render() {
    grid.innerHTML = "";
    apps.forEach((app, index) => {
      const button = document.createElement("button");
      button.className = "app-card";
      button.type = "button";
      button.setAttribute("role", "listitem");
      button.innerHTML = '<span class="glyph" aria-hidden="true"></span><span class="name"></span>';
      button.querySelector(".glyph").textContent = app.glyph;
      button.querySelector(".glyph").style.color = app.color;
      button.querySelector(".name").textContent = app.name;
      button.addEventListener("focus", () => { selected = index; });
      button.addEventListener("click", () => launch(app));
      grid.appendChild(button);
    });
    focusSelected();
  }
  function focusSelected() {
    const buttons = grid.querySelectorAll("button");
    if (!buttons.length) return;
    selected = Math.max(0, Math.min(selected, buttons.length - 1));
    buttons.forEach((b, i) => b.classList.toggle("selected", i === selected));
    buttons[selected].focus({ preventScroll: true });
  }
  function launch(app) {
    // Platform-specific app launching must be implemented by a verified adapter.
    // Never invent package IDs or invoke undocumented system APIs from shared code.
    status.textContent = app.name + " selected — app launch needs a platform adapter";
  }
  function keydown(event) {
    const key = event.key;
    const col = columns();
    let next = selected;
    if (key === "ArrowRight") next = Math.min(apps.length - 1, selected + 1);
    else if (key === "ArrowLeft") next = Math.max(0, selected - 1);
    else if (key === "ArrowDown") next = Math.min(apps.length - 1, selected + col);
    else if (key === "ArrowUp") next = Math.max(0, selected - col);
    else if (key === "Escape" || key === "Backspace") { status.textContent = "You're on the TV VibeHack home app"; event.preventDefault(); return; }
    else return;
    event.preventDefault();
    selected = next;
    focusSelected();
  }
  function updateClock() {
    clock.textContent = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(new Date());
  }
  document.addEventListener("keydown", keydown);
  window.addEventListener("resize", render);
  status.textContent = "Shared UI · platform adapter not connected";
  updateClock();
  setInterval(updateClock, 15000);
  render();
}());
